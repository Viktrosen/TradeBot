package ru.bolotov.tradebot.config

import org.springframework.amqp.core.*
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RabbitMQConfig {

    @Bean
    fun tradeEventsExchange(): TopicExchange = TopicExchange("trade.events")

    @Bean
    fun queueTradeExecuted(): Queue = Queue("trade.executed.queue", true)

    @Bean
    fun queuePortfolioChanged(): Queue = Queue("portfolio.changed.queue", true)

    @Bean
    fun queueBotStatusChanged(): Queue = Queue("bot.status.changed.queue", true)

    @Bean
    fun queueBotHeartbeat(): Queue = Queue("bot.heartbeat.queue", true)

    @Bean
    fun queuePositionPriceUpdated(): Queue = Queue("position.price.updated.queue", true)

    @Bean
    fun queuePositionsChanged(): Queue = Queue("positions.changed.queue", true)

    @Bean
    fun bindingTradeExecuted(): Binding = BindingBuilder
        .bind(queueTradeExecuted())
        .to(tradeEventsExchange())
        .with("trade.executed")

    @Bean
    fun bindingPortfolioChanged(): Binding = BindingBuilder
        .bind(queuePortfolioChanged())
        .to(tradeEventsExchange())
        .with("portfolio.changed")

    @Bean
    fun bindingBotStatusChanged(): Binding = BindingBuilder
        .bind(queueBotStatusChanged())
        .to(tradeEventsExchange())
        .with("bot.status.changed")

    @Bean
    fun bindingBotHeartbeat(): Binding = BindingBuilder
        .bind(queueBotHeartbeat())
        .to(tradeEventsExchange())
        .with("bot.heartbeat")

    @Bean
    fun bindingPositionPriceUpdated(): Binding = BindingBuilder
        .bind(queuePositionPriceUpdated())
        .to(tradeEventsExchange())
        .with("position.price.updated")

    @Bean
    fun bindingPositionsChanged(): Binding = BindingBuilder
        .bind(queuePositionsChanged())
        .to(tradeEventsExchange())
        .with("positions.changed")

    @Bean
    fun jackson2JsonMessageConverter(): Jackson2JsonMessageConverter = Jackson2JsonMessageConverter()

    @Bean
    fun rabbitTemplate(connectionFactory: ConnectionFactory): RabbitTemplate {
        val template = RabbitTemplate(connectionFactory)
        template.messageConverter = jackson2JsonMessageConverter()
        return template
    }
}
