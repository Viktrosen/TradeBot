package ru.bolotov.tradebot.service.data

import ru.bolotov.tradebot.strategy.StrategyAiDetails

/** Данные активной стратегии, передаваемые AI без технических объектов стратегии. */
data class AiStrategyContext(
    /** Стабильный внутренний ID выбранной стратегии. */
    val id: String,
    val name: String,
    val explanation: String,
    val details: StrategyAiDetails?,
    val candlestickPattern: String?,
    val candlestickConfidence: Double?,
    val candlestickTimeframe: String?
)
