package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.EventStatus
import ru.bolotov.tradebot.domain.model.EventType
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.TradeEvent
import ru.bolotov.tradebot.domain.repository.TradeEventRepository
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.Signal
import java.math.BigDecimal
import java.time.Instant

private val tradeEventLogger = KotlinLogging.logger {}

@Service
class TradeEventService(
    private val tradeEventRepository: TradeEventRepository,
    private val candlestickPatternStrategy: ru.bolotov.tradebot.strategy.CandlestickPatternStrategy
) {

    fun hasCloseEvent(positionId: String): Boolean =
        tradeEventRepository.existsByPositionIdAndEventType(positionId, EventType.CLOSE)

    fun findLastOpenPositionId(instrumentId: String, direction: OrderDirection): String? =
        tradeEventRepository.findFirstByInstrumentIdAndDirectionAndEventTypeOrderByCreatedAtDesc(
            instrumentId,
            direction,
            EventType.OPEN
        )?.positionId

    fun createPendingOpenEvent(
        positionId: String,
        marketData: MarketData,
        direction: OrderDirection,
        quantity: Long,
        totalValue: BigDecimal,
        strategyName: String,
        signal: Signal,
        strategyExplanation: String
    ): TradeEvent {
        val event = TradeEvent(
            instrumentId = marketData.instrumentId,
            instrumentName = marketData.instrumentName,
            direction = direction,
            price = marketData.currentPrice,
            quantity = quantity,
            totalValue = totalValue,
            reason = buildOpenTradeReason(strategyName, signal),
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
        totalValue: BigDecimal
    ) {
        event.brokerOrderId = orderResult.orderId
        event.executionStatus = fill.executionStatus ?: orderResult.executionStatus
        event.brokerOrderState = fill.brokerOrderState
        event.price = entryPrice
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

    fun saveCloseEventOnce(
        position: OpenPosition,
        closePrice: BigDecimal,
        pnl: BigDecimal,
        reason: String,
        explanation: String = "Закрытие позиции, P&L: $pnl RUB"
    ): Boolean {
        if (hasCloseEvent(position.positionId)) {
            tradeEventLogger.warn {
                "CLOSE-событие уже существует, дубль не сохраняем: ${position.positionId}"
            }
            return false
        }

        val closeEvent = TradeEvent(
            instrumentId = position.instrumentId,
            instrumentName = position.instrumentName,
            direction = position.direction,
            price = closePrice,
            quantity = position.quantity,
            totalValue = closePrice * position.quantity.toBigDecimal() * position.lotSize.toBigDecimal(),
            reason = reason,
            pnl = pnl,
            eventType = EventType.CLOSE,
            positionId = position.positionId,
            explanation = explanation,
            status = EventStatus.PROCESSED,
            processedAt = Instant.now()
        )
        tradeEventRepository.save(closeEvent)
        return true
    }

    private fun buildOpenTradeReason(strategyName: String, signal: Signal): String =
        if (strategyName == candlestickPatternStrategy.name && !signal.reason.isNullOrBlank()) {
            "$strategyName: ${signal.reason}"
        } else {
            strategyName
        }
}

