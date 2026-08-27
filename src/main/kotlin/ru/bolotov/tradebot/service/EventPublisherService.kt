package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.TradeEvent
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

/** Публикует доменные изменения бота в RabbitMQ для backend и WebSocket-клиентов. */
@Service
class EventPublisherService(
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper
) {

    /** Сообщает о подтверждённом исполнении открытия или закрытия позиции. */
    fun publishTradeExecuted(trade: TradeEvent) {
        try {
            val message = objectMapper.writeValueAsString(
                mapOf(
                    "eventType" to "TRADE_EXECUTED",
                    "data" to trade
                )
            )
            rabbitTemplate.convertAndSend("trade.events", "trade.executed", message)
            logger.debug { "Опубликовано событие trade.executed" }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка публикации события trade.executed" }
        }
    }

    /** Отправляет обновлённые значения портфеля и свободных средств. */
    fun publishPortfolioChanged(
        totalValue: BigDecimal? = null,
        availableCash: BigDecimal? = null,
        blockedCash: BigDecimal? = null
    ) {
        val message = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "PORTFOLIO_CHANGED",
                "data" to mapOf(
                    "totalValue" to totalValue,
                    "availableCash" to availableCash,
                    "blockedCash" to blockedCash
                ).filterValues { it != null }
            )
        )
        rabbitTemplate.convertAndSend("trade.events", "portfolio.changed", message)
        logger.debug { "Опубликовано событие portfolio.changed" }
    }

    /** Публикует текущую цену и нереализованный PnL открытой позиции. */
    fun publishPositionPriceUpdated(
        position: OpenPosition,
        currentPrice: BigDecimal
    ) {
        val entryValue = position.entryPrice * position.quantity.toBigDecimal() * position.lotSize.toBigDecimal()
        val unrealizedPnl = position.calculateUnrealizedPnl(currentPrice)
        val pnlPercent = if (entryValue > BigDecimal.ZERO) {
            unrealizedPnl * BigDecimal(100) / entryValue
        } else {
            BigDecimal.ZERO
        }
        val message = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "POSITION_PRICE_UPDATED",
                "data" to mapOf(
                    "positionId" to position.positionId,
                    "instrumentId" to position.instrumentId,
                    "currentPrice" to currentPrice,
                    "unrealizedPnl" to unrealizedPnl,
                    "pnlPercent" to pnlPercent
                )
            )
        )
        rabbitTemplate.convertAndSend("trade.events", "position.price.updated", message)
    }

    /** Уведомляет потребителей, что состав открытых позиций изменился. */
    fun publishPositionsChanged() {
        val message = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "POSITIONS_CHANGED",
                "data" to emptyMap<String, Any>()
            )
        )
        rabbitTemplate.convertAndSend("trade.events", "positions.changed", message)
    }

    /** Публикует переход бота в RUNNING или STOPPED. */
    fun publishBotStatusChanged(status: String) {
        val message = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "BOT_STATUS_CHANGED",
                "data" to mapOf("status" to status)
            )
        )
        rabbitTemplate.convertAndSend("trade.events", "bot.status.changed", message)
        logger.info { "Опубликовано событие bot.status.changed: $status" }
    }

    /** Публикует статус доступности торгов по активным инструментам. */
    fun publishTradingAvailabilityChanged(
        tradingAvailability: Map<String, Boolean>,
        allTradingUnavailable: Boolean
    ) {
        val message = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "TRADING_AVAILABILITY_CHANGED",
                "data" to mapOf(
                    "instruments" to tradingAvailability.map { (instrumentId, tradingAvailable) ->
                        mapOf(
                            "instrumentId" to instrumentId,
                            "tradingAvailable" to tradingAvailable
                        )
                    },
                    "allTradingUnavailable" to allTradingUnavailable
                )
            )
        )
        rabbitTemplate.convertAndSend("trade.events", "trading.availability.changed", message)
    }

    /** Периодически подтверждает доступность процесса бота. */
    fun publishBotHeartbeat(isRunning: Boolean) {
        val message = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "BOT_HEARTBEAT",
                "data" to mapOf("running" to isRunning)
            )
        )
        rabbitTemplate.convertAndSend("trade.events", "bot.heartbeat", message)
    }

    /** Предупреждает клиент о достижении ограничения загрузки капитала. */
    fun publishCapitalUsageLimitReached(usagePercent: Double, limitPercent: Double) {
        val message = objectMapper.writeValueAsString(mapOf("eventType" to "CAPITAL_USAGE_LIMIT_REACHED", "data" to mapOf("usagePercent" to usagePercent, "limitPercent" to limitPercent)))
        rabbitTemplate.convertAndSend("trade.events", "capital.usage.limit.reached", message)
    }
}
