package ru.bolotov.tradebot.service.data

/** Данные активной стратегии, передаваемые AI без технических объектов стратегии. */
data class AiStrategyContext(
    val name: String,
    val explanation: String,
    val candlestickPattern: String?,
    val candlestickConfidence: Double?,
    val candlestickTimeframe: String?
)
