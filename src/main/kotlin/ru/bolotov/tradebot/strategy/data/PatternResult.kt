package ru.bolotov.tradebot.strategy.data

import ru.bolotov.tradebot.strategy.CandlestickPatternStrategy.PatternType
import ru.bolotov.tradebot.strategy.OrderDirection

/** Результат обнаружения паттерна по последним закрытым свечам. */
data class PatternResult(
    val pattern: PatternType?,
    val direction: OrderDirection,
    val confidence: Double,
    val description: String,
    val candleKey: String? = null
)
