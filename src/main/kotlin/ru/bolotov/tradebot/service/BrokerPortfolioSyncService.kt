package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.EventType
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.MarketDataProvider
import ru.tinkoff.piapi.core.InstrumentsService
import ru.tinkoff.piapi.core.OperationsService
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private val portfolioSyncLogger = KotlinLogging.logger {}

@Service
class BrokerPortfolioSyncService(
    private val operationsService: OperationsService,
    private val instrumentsService: InstrumentsService,
    private val marketDataProvider: MarketDataProvider,
    private val tradeEventService: TradeEventService
) {

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
            val brokerPosition = operationsService.getPortfolioSync(accountId).positions
                .firstOrNull { it.instrumentUid == marketData.instrumentId }

            if (brokerPosition == null || brokerPosition.quantity <= BigDecimal.ZERO) {
                portfolioSyncLogger.info {
                    "Сигнал SELL для ${marketData.instrumentName} отклонён: длинной позиции в портфеле нет, шорты отключены"
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
                entryPrice = moneyToBigDecimal(brokerPosition.averagePositionPrice)
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

    suspend fun restorePositions(accountId: String?): BrokerPortfolioRestoreResult {
        if (accountId == null) {
            portfolioSyncLogger.warn { "Восстановление позиций пропущено: брокерский счёт не выбран" }
            return BrokerPortfolioRestoreResult(emptyMap(), emptyList())
        }

        return try {
            portfolioSyncLogger.info { "Синхронизация портфеля с брокером..." }
            val brokerPositions = operationsService.getPortfolioSync(accountId).positions
            if (brokerPositions.isEmpty()) {
                portfolioSyncLogger.info { "Нет открытых позиций на брокерском счёте" }
                return BrokerPortfolioRestoreResult(emptyMap(), emptyList())
            }

            val restoredPositions = mutableMapOf<String, OpenPosition>()
            var skippedPositions = 0

            for (pos in brokerPositions) {
                try {
                    val instrumentUid = pos.instrumentUid
                    val instrumentInfo = getRestorableInstrumentInfo(instrumentUid)
                    if (instrumentInfo == null) {
                        skippedPositions++
                        continue
                    }

                    val avgPrice = moneyToBigDecimal(pos.averagePositionPrice)
                    if (avgPrice <= BigDecimal.ZERO) {
                        portfolioSyncLogger.warn {
                            "Пропускаем позицию с нулевой средней ценой: $instrumentUid"
                        }
                        continue
                    }

                    val currentQuantity = pos.quantity
                    val direction = if (currentQuantity > BigDecimal.ZERO) OrderDirection.BUY else OrderDirection.SELL
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

                    val positionId = tradeEventService.findLastOpenPositionId(instrumentUid, direction)
                        ?: UUID.randomUUID().toString()
                    if (tradeEventService.hasCloseEvent(positionId)) {
                        portfolioSyncLogger.info {
                            "Позиция ${instrumentInfo.name} не восстановлена: для positionId=$positionId уже есть CLOSE-событие"
                        }
                        continue
                    }

                    val atr = marketData?.atr
                    val stopLossPrice = when {
                        direction == OrderDirection.BUY && atr != null -> avgPrice - atr * BigDecimal("1.5")
                        direction == OrderDirection.SELL && atr != null -> avgPrice + atr * BigDecimal("1.5")
                        else -> null
                    }

                    val restoredPosition = OpenPosition(
                        positionId = positionId,
                        instrumentId = instrumentUid,
                        instrumentName = instrumentInfo.name,
                        direction = direction,
                        entryPrice = avgPrice,
                        quantity = quantityLots,
                        lotSize = lotSize,
                        entryCommission = BigDecimal.ZERO,
                        entryTime = Instant.now().minusSeconds(3600),
                        stopLossPrice = stopLossPrice,
                        atr = atr
                    )

                    restoredPositions[instrumentUid] = restoredPosition
                    portfolioSyncLogger.info {
                        "Восстановлена позиция: ${restoredPosition.direction} ${restoredPosition.instrumentName} " +
                                "(${restoredPosition.quantity} лотов по ${restoredPosition.entryPrice} RUB)"
                    }
                } catch (e: Exception) {
                    portfolioSyncLogger.error(e) { "Ошибка восстановления позиции ${pos.instrumentUid}" }
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
                "Не удалось распознать инструмент $instrumentUid через getInstrumentByUIDSync, пробуем как акцию"
            }
            runCatching {
                val share = instrumentsService.getShareByUidSync(instrumentUid)
                RestorableInstrumentInfo(
                    ticker = share.ticker,
                    name = share.name.ifBlank { share.ticker.ifBlank { instrumentUid } },
                    instrumentType = "share",
                    lotSize = share.lot
                )
            }.onFailure { shareError ->
                portfolioSyncLogger.warn(shareError) {
                    "Пропускаем нераспознанную брокерскую позицию $instrumentUid: нет данных об инструменте"
                }
            }.getOrNull()
        }
    }

    private fun brokerUnitsToLots(quantity: BigDecimal, lotSize: Int): Long =
        quantity.abs()
            .divideToIntegralValue(lotSize.coerceAtLeast(1).toBigDecimal())
            .toLong()

    private fun moneyToBigDecimal(money: ru.tinkoff.piapi.core.models.Money?): BigDecimal =
        money?.value ?: BigDecimal.ZERO

    private data class RestorableInstrumentInfo(
        val ticker: String,
        val name: String,
        val instrumentType: String,
        val lotSize: Int
    )

    private companion object {
        val IGNORED_BROKER_POSITION_TYPES = setOf("currency")
        val IGNORED_BROKER_POSITION_UIDS = setOf("a92e2e25-a698-45cc-a781-167cf465257c")
    }
}

data class BrokerPortfolioRestoreResult(
    val positions: Map<String, OpenPosition>,
    val instrumentIds: List<String>
)

