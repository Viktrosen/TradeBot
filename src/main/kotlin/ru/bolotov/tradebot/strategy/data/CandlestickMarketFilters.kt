package ru.bolotov.tradebot.strategy.data

import ru.bolotov.tradebot.strategy.OrderDirection
import java.math.BigDecimal

/** Значения фильтров тренда, RSI и объёма для оценки свечного паттерна. */
data class CandlestickMarketFilters(
    val globalTrend: OrderDirection,
    val rsi: BigDecimal?,
    val volumeSpike: Boolean,
    val volumeRatio: Double = 1.0  // НОВОЕ: ratio текущего объёма к SMA(20)
)
