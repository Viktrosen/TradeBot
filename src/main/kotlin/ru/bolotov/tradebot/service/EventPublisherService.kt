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
            val message = objectMapper.writeValueAsString(trade)
            if (rabbitTemplate == null) {
                logger.debug { "RabbitMQ отключен, событие не отправлено" }
                return
            }
            logger.debug { "Опубликовано trade.executed" }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка публикации trade.executed" }
        }
    }

    fun publishPortfolioChanged() {
        if (rabbitTemplate == null) return
        rabbitTemplate.convertAndSend("trade.events", "portfolio.changed", "{}")
        logger.debug { "Опубликовано portfolio.changed" }
    }

    fun publishBotStatusChanged(status: String) {
        if (rabbitTemplate == null) return
        val message = "{\"status\":\"$status\"}"
        rabbitTemplate.convertAndSend("trade.events", "bot.status.changed", message)
        logger.info { "Опубликовано bot.status.changed: $status" }
    }
}