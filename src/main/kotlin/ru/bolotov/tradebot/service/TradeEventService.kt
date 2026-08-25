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

    fun findProcessedCloseEvents(): List<TradeEvent> =
        tradeEventRepository.findByStatusAndEventTypeOrderByProcessedAtDesc(
            EventStatus.PROCESSED,
            EventType.CLOSE
        )

    fun findOpenEvent(positionId: String): TradeEvent? =
        tradeEventRepository.findByPositionIdAndEventType(positionId, EventType.OPEN)

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
                    entryTime = event.processedAt ?: event.createdAt
                )
            }
            .toList()

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
        positionSide: PositionSide = PositionSide.LONG,
        quantity: Long,
        lotSize: Int,
        totalValue: BigDecimal,
        strategyName: String,
        signal: Signal,
        strategyExplanation: String
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

    fun saveCloseEventOnce(
        position: OpenPosition,
        closePrice: BigDecimal,
        pnl: BigDecimal,
        reason: String,
        explanation: String = "Закрытие позиции, P&L: $pnl RUB"
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
            processedAt = Instant.now()
        )
        return tradeEventRepository.save(closeEvent)
    }

    private fun buildOpenTradeReason(strategyName: String, signal: Signal): String =
        if (strategyName == candlestickPatternStrategy.name && !signal.reason.isNullOrBlank()) {
            "$strategyName: ${signal.reason}"
        } else {
            strategyName
        }
}
