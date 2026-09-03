package ru.bolotov.tradebot.strategy.data

import ru.bolotov.tradebot.strategy.CandlestickPatternStrategy.PatternType
import ru.bolotov.tradebot.strategy.OrderDirection

/** Результат обнаружения паттерна по последним закрытым свечам. */
data class PatternResult(
    val pattern: PatternType?,
    val direction: OrderDirection,
    val confidence: Double,
    val description: String,
    val candleKey: String? = null,
    val volumeRatio: Double? = null,  // НОВОЕ: ratio объёма к SMA(20)
    val volumeConfirmed: Boolean = false  // НОВОЕ: подтверждение объёмом
)
