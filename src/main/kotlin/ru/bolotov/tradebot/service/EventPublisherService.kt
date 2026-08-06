package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.TradeEvent

private val logger = KotlinLogging.logger {}

@Service
class EventPublisherService(
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper
) {

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

    fun publishPortfolioChanged() {
        val message = objectMapper.writeValueAsString(
            mapOf(
                "eventType" to "PORTFOLIO_CHANGED",
                "data" to emptyMap<String, Any>()
            )
        )
        rabbitTemplate.convertAndSend("trade.events", "portfolio.changed", message)
        logger.debug { "Опубликовано событие portfolio.changed" }
    }

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

    fun publishCapitalUsageLimitReached(usagePercent: Double, limitPercent: Double) {
        val message = objectMapper.writeValueAsString(mapOf("eventType" to "CAPITAL_USAGE_LIMIT_REACHED", "data" to mapOf("usagePercent" to usagePercent, "limitPercent" to limitPercent)))
        rabbitTemplate.convertAndSend("trade.events", "capital.usage.limit.reached", message)
    }
}
