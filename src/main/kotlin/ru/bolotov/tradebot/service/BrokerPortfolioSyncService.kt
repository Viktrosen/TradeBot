package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.broker.getInstrumentByUIDSync
import ru.bolotov.tradebot.broker.getPortfolioSync
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.config.PositionSizingConfig
import ru.bolotov.tradebot.domain.model.EventType
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.service.data.BrokerPortfolioPosition
import ru.bolotov.tradebot.service.data.BrokerPortfolioRestoreResult
import ru.bolotov.tradebot.service.data.RestorableInstrumentInfo
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.MarketDataProvider
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.ttech.piapi.core.InstrumentsServiceSync
import ru.ttech.piapi.core.OperationsServiceSync
import java.math.BigDecimal
import java.time.Instant

private val portfolioSyncLogger = KotlinLogging.logger {}

/** Сверяет локальные позиции с портфелем брокера и восстанавливает их после запуска. */
@Service
class BrokerPortfolioSyncService(
    private val operationsService: OperationsServiceSync,
    private val instrumentsService: InstrumentsServiceSync,
    private val marketDataProvider: MarketDataProvider,
    private val tradeEventService: TradeEventService,
    private val positionSizingConfig: PositionSizingConfig
) {

    /** Возвращает количества всех инструментов брокерского портфеля одним пакетным запросом. */
    fun getBrokerPositionQuantities(accountId: String): Map<String, BigDecimal> = runCatching {
        brokerPositions(accountId)
            .asSequence()
            .associate { position -> position.instrumentId to position.quantity }
    }.onFailure { error ->
        portfolioSyncLogger.error(error) { "Не удалось получить пакетное состояние портфеля брокера" }
    }.getOrThrow()

    /** Находит LONG у брокера для SELL-сигнала, если локальное состояние ещё не содержит позицию. */
    fun synchronizeLongPositionForSell(
        accountId: String?,
        marketData: MarketData,
        existingPosition: OpenPosition?
    ): OpenPosition? {
        if (existingPosition?.direction == OrderDirection.BUY) return existingPosition
        if (accountId == null) {
            portfolioSyncLogger.warn {
                "Не удалось проверить позицию ${marketData.instrumentName}: брокерский счёт не выбран"
            }
            return null
        }

        return try {
            val brokerPosition = brokerPositions(accountId)
                .firstOrNull { it.instrumentId == marketData.instrumentId }

            if (brokerPosition == null || brokerPosition.quantity <= BigDecimal.ZERO) {
                portfolioSyncLogger.info {
                    "Для SELL ${marketData.instrumentName} не найдена длинная позиция в портфеле; " +
                        "сигнал может быть рассмотрен как открытие шорта"
                }
                return null
            }

            val quantityLots = brokerUnitsToLots(brokerPosition.quantity, marketData.lotSize)
            if (quantityLots <= 0) {
                portfolioSyncLogger.warn {
                    "Сигнал SELL для ${marketData.instrumentName} отклонён: количество позиции меньше одного лота"
                }
                return null
            }

            OpenPosition(
                instrumentId = marketData.instrumentId,
                instrumentName = marketData.instrumentName,
                direction = OrderDirection.BUY,
                entryPrice = brokerPosition.averagePositionPrice
                    .takeIf { it > BigDecimal.ZERO } ?: marketData.currentPrice,
                quantity = quantityLots,
                lotSize = marketData.lotSize,
                entryTime = Instant.now()
            ).also {
                portfolioSyncLogger.info {
                    "Позиция ${marketData.instrumentName} синхронизирована с портфелем для исполнения SELL"
                }
            }
        } catch (e: Exception) {
            portfolioSyncLogger.error(e) {
                "Не удалось проверить позицию в портфеле для SELL ${marketData.instrumentName}"
            }
            null
        }
    }

    /** Восстанавливает только распознанные позиции бота и не принимает внешние шорты за свои. */
    suspend fun restorePositions(accountId: String?): BrokerPortfolioRestoreResult {
        if (accountId == null) {
            portfolioSyncLogger.warn { "Восстановление позиций пропущено: брокерский счёт не выбран" }
            return BrokerPortfolioRestoreResult(emptyMap(), emptyList())
        }

        return try {
            portfolioSyncLogger.info { "Синхронизация портфеля с брокером..." }
            val brokerPositions = brokerPositions(accountId)
            if (brokerPositions.isEmpty()) {
                portfolioSyncLogger.info { "Нет открытых позиций на брокерском счёте" }
                return BrokerPortfolioRestoreResult(emptyMap(), emptyList())
            }

            val restoredPositions = mutableMapOf<String, OpenPosition>()
            var skippedPositions = 0

            for (pos in brokerPositions) {
                try {
                    val instrumentUid = pos.instrumentId
                    val instrumentInfo = getRestorableInstrumentInfo(instrumentUid)
                    if (instrumentInfo == null) {
                        skippedPositions++
                        continue
                    }

                    val currentQuantity = pos.quantity
                    val side = positionSideForRestoredPosition(instrumentInfo.name, instrumentUid, currentQuantity)
                    if (side == null) {
                        skippedPositions++
                        continue
                    }

                    val avgPrice = pos.averagePositionPrice
                    if (avgPrice <= BigDecimal.ZERO) {
                        portfolioSyncLogger.warn {
                            "Пропускаем позицию с нулевой средней ценой: $instrumentUid"
                        }
                        continue
                    }

                    val marketData = runCatching { marketDataProvider.fetchMarketData(instrumentUid) }
                        .onFailure { portfolioSyncLogger.warn(it) { "Не удалось получить ATR для $instrumentUid" } }
                        .getOrNull()
                    val lotSize = (marketData?.lotSize ?: instrumentInfo.lotSize).coerceAtLeast(1)
                    val quantityLots = brokerUnitsToLots(currentQuantity, lotSize)

                    if (quantityLots <= 0) {
                        portfolioSyncLogger.warn {
                            "Пропускаем позицию ${instrumentInfo.name}: количество ${currentQuantity.abs()} меньше одного лота ($lotSize)"
                        }
                        continue
                    }

                    val positionId = findRestoredPositionId(instrumentUid, side)
                    if (positionId == null) {
                        skippedPositions++
                        continue
                    }
                    if (tradeEventService.hasCloseEvent(positionId)) {
                        portfolioSyncLogger.info {
                            "Позиция ${instrumentInfo.name} не восстановлена: " +
                                "для positionId=$positionId уже есть CLOSE-событие"
                        }
                        continue
                    }

                    val atr = marketData?.atr
                    val stopLossPrice = atr?.let { value ->
                        if (side == PositionSide.LONG) avgPrice - value * BigDecimal("1.5")
                        else avgPrice + value * BigDecimal("1.5")
                    }

                    val restoredPosition = OpenPosition(
                        positionId = positionId,
                        instrumentId = instrumentUid,
                        instrumentName = instrumentInfo.name,
                        direction = side.openDirection(),
                        side = side,
                        entryPrice = avgPrice,
                        quantity = quantityLots,
                        lotSize = lotSize,
                        entryCommission = BigDecimal.ZERO,
                        entryTime = Instant.now().minusSeconds(3600),
                        entryStrategyId = tradeEventService.findEntryStrategyId(positionId),
                        stopLossPrice = stopLossPrice,
                        atr = atr
                    )

                    restoredPositions[instrumentUid] = restoredPosition
                    portfolioSyncLogger.info {
                        "Восстановлена позиция: ${restoredPosition.direction} ${restoredPosition.instrumentName} " +
                                "(${restoredPosition.quantity} лотов по ${restoredPosition.entryPrice} RUB)"
                    }
                } catch (e: Exception) {
                    portfolioSyncLogger.error(e) { "Ошибка восстановления позиции ${pos.instrumentId}" }
                }
            }

            portfolioSyncLogger.info {
                "Синхронизация завершена. Восстановлено ${restoredPositions.size} позиций, " +
                        "пропущено неторговых или нераспознанных: $skippedPositions"
            }
            BrokerPortfolioRestoreResult(restoredPositions, restoredPositions.keys.toList())
        } catch (e: Exception) {
            portfolioSyncLogger.error(e) { "Критическая ошибка синхронизации портфеля" }
            BrokerPortfolioRestoreResult(emptyMap(), emptyList())
        }
    }

    private fun getRestorableInstrumentInfo(instrumentUid: String): RestorableInstrumentInfo? {
        if (instrumentUid in IGNORED_BROKER_POSITION_UIDS) {
            portfolioSyncLogger.info { "Пропускаем валютную позицию при восстановлении: $instrumentUid" }
            return null
        }

        return try {
            val instrument = instrumentsService.getInstrumentByUIDSync(instrumentUid).instrument
            val instrumentType = instrument.instrumentType.lowercase()
            if (instrumentType in IGNORED_BROKER_POSITION_TYPES) {
                portfolioSyncLogger.info {
                    "Пропускаем валютную позицию при восстановлении: ${instrument.ticker.ifBlank { instrumentUid }}"
                }
                null
            } else {
                RestorableInstrumentInfo(
                    ticker = instrument.ticker,
                    name = instrument.name.ifBlank { instrument.ticker.ifBlank { instrumentUid } },
                    instrumentType = instrumentType,
                    lotSize = instrument.lot
                )
            }
        } catch (instrumentError: Exception) {
            portfolioSyncLogger.warn(instrumentError) {
                "Не удалось распознать инструмент $instrumentUid при синхронизации портфеля"
            }
            null
        }
    }

    private fun positionSideForRestoredPosition(
        instrumentName: String,
        instrumentId: String,
        quantity: BigDecimal
    ): PositionSide? {
        if (quantity > BigDecimal.ZERO) return PositionSide.LONG
        if (quantity == BigDecimal.ZERO) {
            portfolioSyncLogger.debug { "Нулевая позиция $instrumentName пропущена при синхронизации портфеля" }
            return null
        }

        if (!positionSizingConfig.shortTradingEnabled) {
            portfolioSyncLogger.warn {
                "Короткая позиция $instrumentName (${quantity.abs()} ед.) обнаружена у брокера и проигнорирована: " +
                    "шорты выключены в настройках риска. Закройте её вручную или включите шорты после проверки."
            }
            return null
        }
        if (tradeEventService.findLastOpenPositionId(instrumentId, OrderDirection.SELL) == null) {
            portfolioSyncLogger.error {
                "Короткая позиция $instrumentName (${quantity.abs()} ед.) не восстановлена: " +
                    "для неё нет OPEN-события бота. Это защита от принятия внешней позиции за позицию бота."
            }
            return null
        }
        return PositionSide.SHORT
    }

    private fun findRestoredPositionId(
        instrumentId: String,
        side: PositionSide
    ): String? {
        val direction = side.openDirection()
        val positionId = tradeEventService.findLastOpenPositionId(instrumentId, direction)
        if (positionId != null) return positionId

        if (side == PositionSide.LONG) {
            portfolioSyncLogger.error {
                "Длинная позиция $instrumentId не восстановлена: нет активного OPEN/BUY-события бота. " +
                    "Позиция считается внешней или остаточной после исполнения защитной заявки и не будет " +
                    "автоматически сопровождаться. Проверьте операции брокера."
            }
            return null
        }
        portfolioSyncLogger.error {
            "Короткая позиция $instrumentId не восстановлена: " +
                "не найдено OPEN-событие бота"
        }
        return null
    }

    private fun brokerUnitsToLots(quantity: BigDecimal, lotSize: Int): Long =
        quantity.abs()
            .divideToIntegralValue(lotSize.coerceAtLeast(1).toBigDecimal())
            .toLong()

    private fun brokerPositions(accountId: String): List<BrokerPortfolioPosition> =
        operationsService.getPortfolioSync(accountId).positionsList
            .filter { it.instrumentUid.isNotBlank() }
            .map { position ->
                BrokerPortfolioPosition(
                    instrumentId = position.instrumentUid,
                    quantity = position.quantity.toBigDecimal(),
                    averagePositionPrice = position.averagePositionPrice.toBigDecimal()
                )
            }

    private companion object {
        val IGNORED_BROKER_POSITION_TYPES = setOf("currency")
        val IGNORED_BROKER_POSITION_UIDS = setOf("a92e2e25-a698-45cc-a781-167cf465257c")
    }
}
