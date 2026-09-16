package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.EventStatus
import ru.bolotov.tradebot.domain.model.EventType
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.domain.model.TradeEvent
import ru.bolotov.tradebot.domain.repository.TradeEventRepository
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.Signal
import ru.bolotov.tradebot.strategy.regime.MarketRegime
import java.math.BigDecimal
import java.time.Instant

private val tradeEventLogger = KotlinLogging.logger {}

/** Ведёт журнал торговых событий и предоставляет его для восстановления состояния и dashboard. */
@Service
class TradeEventService(
    private val tradeEventRepository: TradeEventRepository,
    private val candlestickPatternStrategy: ru.bolotov.tradebot.strategy.CandlestickPatternStrategy
) {

    /** Проверяет, есть ли уже финальное закрытие позиции. */
    fun hasCloseEvent(positionId: String): Boolean =
        tradeEventRepository.existsByPositionIdAndEventType(positionId, EventType.CLOSE)

    /** Возвращает исполненные закрытия для расчёта истории и метрик. */
    fun findProcessedCloseEvents(): List<TradeEvent> =
        tradeEventRepository.findByStatusAndEventTypeOrderByProcessedAtDesc(
            EventStatus.PROCESSED,
            EventType.CLOSE
        )

    /** Находит исходное событие открытия позиции. */
    fun findOpenEvent(positionId: String): TradeEvent? =
        tradeEventRepository.findByPositionIdAndEventType(positionId, EventType.OPEN)

    /** Возвращает идентификатор стратегии, открывшей позицию. */
    fun findEntryStrategyId(positionId: String): String? =
        findOpenEvent(positionId)?.let { event -> entryStrategyId(event.reason) }

    /** Восстанавливает незакрытые позиции из журнала событий. */
    fun findUnclosedPositions(): List<OpenPosition> =
        tradeEventRepository.findByStatusAndEventTypeOrderByProcessedAtDesc(EventStatus.PROCESSED, EventType.OPEN)
            .asSequence()
            .filter { event -> event.positionId != null && !hasCloseEvent(requireNotNull(event.positionId)) }
            .map { event ->
                OpenPosition(
                    positionId = requireNotNull(event.positionId),
                    instrumentId = event.instrumentId,
                    instrumentName = event.instrumentName,
                    direction = event.direction,
                    side = event.positionSide,
                    entryPrice = event.price,
                    quantity = event.quantity,
                    lotSize = event.lotSize,
                    entryTime = event.processedAt ?: event.createdAt,
                    entryStrategyId = entryStrategyId(event.reason)
                )
            }
            .toList()

    fun findLastOpenPositionId(instrumentId: String, direction: OrderDirection): String? =
        tradeEventRepository.findFirstByInstrumentIdAndDirectionAndEventTypeOrderByCreatedAtDesc(
            instrumentId,
            direction,
            EventType.OPEN
        )?.positionId

    /**
     * Finds an opening which may safely be reconciled with an actual broker
     * holding. A generic failed order is deliberately excluded; only a pending
     * order or one for which the broker already reported FILL is recoverable.
     */
    fun findRestorableOpenPositionId(instrumentId: String, direction: OrderDirection): String? =
        tradeEventRepository.findByInstrumentId(instrumentId)
            .asSequence()
            .filter { it.eventType == EventType.OPEN && it.direction == direction && it.positionId != null }
            .filter { event ->
                event.status == EventStatus.PROCESSED ||
                    event.status == EventStatus.PENDING ||
                    (event.status == EventStatus.FAILED &&
                        event.executionStatus == "EXECUTION_REPORT_STATUS_FILL")
            }
            .filterNot { event -> hasCloseEvent(requireNotNull(event.positionId)) }
            .maxByOrNull(TradeEvent::createdAt)
            ?.positionId

    /** Marks a previously uncertain opening as processed using confirmed portfolio data. */
    fun reconcileOpenEventFromBroker(
        positionId: String,
        entryPrice: BigDecimal,
        quantity: Long,
        lotSize: Int
    ) {
        val event = findOpenEvent(positionId) ?: return
        if (event.status == EventStatus.PROCESSED) return

        event.status = EventStatus.PROCESSED
        event.processedAt = Instant.now()
        event.price = entryPrice
        event.quantity = quantity
        event.lotSize = lotSize
        event.totalValue = entryPrice * quantity.toBigDecimal() * lotSize.toBigDecimal()
        event.executionStatus = "RECONCILED_BROKER_POSITION"
        event.errorMessage = null
        tradeEventRepository.save(event)
        tradeEventLogger.warn {
            "OPEN-событие восстановлено по подтверждённой позиции брокера: " +
                "${event.instrumentName}, positionId=$positionId"
        }
    }

    fun createPendingOpenEvent(
        positionId: String,
        marketData: MarketData,
        direction: OrderDirection,
        positionSide: PositionSide = PositionSide.LONG,
        quantity: Long,
        lotSize: Int,
        totalValue: BigDecimal,
        strategyId: String,
        strategyName: String,
        signal: Signal,
        strategyExplanation: String,
        marketRegime: MarketRegime
    ): TradeEvent {
        val event = TradeEvent(
            instrumentId = marketData.instrumentId,
            instrumentName = marketData.instrumentName,
            direction = direction,
            positionSide = positionSide,
            price = marketData.currentPrice,
            quantity = quantity,
            lotSize = lotSize,
            totalValue = totalValue,
            reason = buildOpenTradeReason(strategyId, strategyName, marketRegime, signal),
            explanation = strategyExplanation,
            pnl = null,
            eventType = EventType.OPEN,
            positionId = positionId,
            status = EventStatus.PENDING
        )
        return tradeEventRepository.save(event)
    }

    fun markOpenEventProcessed(
        event: TradeEvent,
        orderResult: OrderResult,
        fill: OrderFillResult,
        entryPrice: BigDecimal,
        quantity: Long,
        totalValue: BigDecimal
    ) {
        event.brokerOrderId = orderResult.orderId
        event.executionStatus = fill.executionStatus ?: orderResult.executionStatus
        event.brokerOrderState = fill.brokerOrderState
        event.price = entryPrice
        event.quantity = quantity
        event.totalValue = totalValue
        event.status = EventStatus.PROCESSED
        event.processedAt = Instant.now()
        event.errorMessage = null
        tradeEventRepository.save(event)
    }

    fun markOpenEventFailed(event: TradeEvent, orderResult: OrderResult, fill: OrderFillResult? = null) {
        event.status = EventStatus.FAILED
        event.brokerOrderId = orderResult.orderId
        event.executionStatus = fill?.executionStatus ?: orderResult.executionStatus
        event.brokerOrderState = fill?.brokerOrderState
        event.errorMessage = fill?.errorMessage ?: orderResult.error
            ?: "Заявка на открытие не была исполнена до таймаута или была отклонена"
        event.processedAt = Instant.now()
        tradeEventRepository.save(event)
    }

    /** Keeps an opening visible for later broker reconciliation instead of misclassifying it as failed. */
    fun markOpenEventAwaitingReconciliation(event: TradeEvent, orderResult: OrderResult, fill: OrderFillResult) {
        event.status = EventStatus.PENDING
        event.brokerOrderId = orderResult.orderId
        event.executionStatus = fill.executionStatus
        event.brokerOrderState = fill.brokerOrderState
        event.errorMessage = "Ожидается сверка с брокером: ${fill.errorMessage ?: fill.executionStatus ?: "неизвестный статус"}"
        event.processedAt = null
        tradeEventRepository.save(event)
    }

    /**
     * Persists the fact that the local position must no longer be traded. Its
     * broker closing price is unknown, therefore P&L intentionally remains null.
     */
    fun saveBrokerReconciliationCloseOnce(position: OpenPosition, brokerQuantity: BigDecimal): TradeEvent? {
        if (hasCloseEvent(position.positionId)) return null
        return tradeEventRepository.save(
            TradeEvent(
                instrumentId = position.instrumentId,
                instrumentName = position.instrumentName,
                direction = position.direction,
                positionSide = position.side,
                price = position.entryPrice,
                quantity = position.quantity,
                lotSize = position.lotSize,
                totalValue = position.entryPrice * position.quantity.toBigDecimal() * position.lotSize.toBigDecimal(),
                reason = "BROKER_POSITION_RECONCILIATION",
                explanation = "Локальная позиция снята с сопровождения: брокер сообщает количество " +
                    "$brokerQuantity, несовместимое со стороной ${position.side}. Цена закрытия и P&L не определены.",
                pnl = null,
                eventType = EventType.CLOSE,
                positionId = position.positionId,
                status = EventStatus.PROCESSED,
                processedAt = Instant.now(),
                executionStatus = "BROKER_POSITION_MISMATCH"
            )
        )
    }

    fun saveCloseEventOnce(
        position: OpenPosition,
        closePrice: BigDecimal,
        pnl: BigDecimal,
        reason: String,
        explanation: String = "Закрытие позиции, P&L: $pnl RUB",
        brokerOrderId: String? = null,
        executionStatus: String? = null,
        brokerOrderState: String? = null
    ): TradeEvent? {
        if (hasCloseEvent(position.positionId)) {
            tradeEventLogger.warn {
                "CLOSE-событие уже существует, дубль не сохраняем: ${position.positionId}"
            }
            return null
        }

        val closeEvent = TradeEvent(
            instrumentId = position.instrumentId,
            instrumentName = position.instrumentName,
            direction = position.direction,
            positionSide = position.side,
            price = closePrice,
            quantity = position.quantity,
            lotSize = position.lotSize,
            totalValue = closePrice * position.quantity.toBigDecimal() * position.lotSize.toBigDecimal(),
            reason = reason,
            pnl = pnl,
            eventType = EventType.CLOSE,
            positionId = position.positionId,
            explanation = explanation,
            status = EventStatus.PROCESSED,
            processedAt = Instant.now(),
            brokerOrderId = brokerOrderId,
            executionStatus = executionStatus,
            brokerOrderState = brokerOrderState
        )
        return tradeEventRepository.save(closeEvent)
    }

    private fun buildOpenTradeReason(
        strategyId: String,
        strategyName: String,
        marketRegime: MarketRegime,
        signal: Signal
    ): String {
        val context = "strategyId=$strategyId; regime=$marketRegime"
        return if (strategyName == candlestickPatternStrategy.name && !signal.reason.isNullOrBlank()) {
            "$context; $strategyName: ${signal.reason}"
        } else {
            "$context; $strategyName"
        }
    }

    private fun entryStrategyId(reason: String): String? =
        STRATEGY_ID_PATTERN.find(reason)?.groupValues?.getOrNull(1)

    private companion object {
        val STRATEGY_ID_PATTERN = Regex("(?:^|;)\\s*strategyId=([^;\\s]+)")
    }
}
