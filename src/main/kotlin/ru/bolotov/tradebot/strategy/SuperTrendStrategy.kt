package ru.bolotov.tradebot.strategy

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * SuperTrend — трендовая стратегия на основе ATR-полос.
 *
 * Логика:
 * - При нахождении цены выше верхней полосы — UPTREND (BUY)
 * - При нахождении цены ниже нижней полосы — DOWNTREND (SELL)
 * - Смена направления происходит при пробое противоположной полосы
 *
 * Стратегия чувствительна к волатильности и адаптируется через ATR.
 * Рекомендуется для режимов WEAK_TREND.
 */
@Component
class SuperTrendStrategy(
    @Value("\${strategy.supertrend.atr-period:10}") private val atrPeriod: Int,
    @Value("\${strategy.supertrend.multiplier:3.0}") private val multiplier: Double
) : TradingStrategy {

    override val name = "SuperTrend"
    override val description = "ATR-based trend following с динамическими полосами"

    private var previousDirection: TrendDirection = TrendDirection.UPTREND
    private var previousUpperBand: BigDecimal = BigDecimal.ZERO
    private var previousLowerBand: BigDecimal = BigDecimal.ZERO

    override fun analyze(data: MarketData): Signal {
        val atr = data.atr ?: return Signal.HOLD
        if (atr <= BigDecimal.ZERO || data.currentPrice <= BigDecimal.ZERO) {
            return Signal.HOLD
        }

        // Middle band = currentPrice + 0.5 * ATR
        val middle = data.currentPrice + (atr.multiply(BigDecimal("0.5")))

        val multiplierBD = BigDecimal.valueOf(multiplier)
        var upperBand = middle + (atr.multiply(multiplierBD))
        var lowerBand = middle - (atr.multiply(multiplierBD))

        // Adjust bands based on previous values
        when (previousDirection) {
            TrendDirection.UPTREND -> {
                if (lowerBand < previousLowerBand) {
                    lowerBand = previousLowerBand
                }
                if (data.currentPrice > previousUpperBand) {
                    previousDirection = TrendDirection.DOWNTREND
                    upperBand = lowerBand
                }
            }
            TrendDirection.DOWNTREND -> {
                if (upperBand > previousUpperBand) {
                    upperBand = previousUpperBand
                }
                if (data.currentPrice < previousLowerBand) {
                    previousDirection = TrendDirection.UPTREND
                    lowerBand = upperBand
                }
            }
        }

        previousUpperBand = upperBand
        previousLowerBand = lowerBand

        return when {
            previousDirection == TrendDirection.UPTREND && data.currentPrice > upperBand ->
                Signal(
                    OrderDirection.BUY,
                    0.75,
                    "SuperTrend: цена выше верхней полосы (UPTREND)"
                )
            previousDirection == TrendDirection.DOWNTREND && data.currentPrice < lowerBand ->
                Signal(
                    OrderDirection.SELL,
                    0.75,
                    "SuperTrend: цена ниже нижней полосы (DOWNTREND)"
                )
            else -> Signal.HOLD
        }
    }

    override fun getExplanation(data: MarketData): String {
        val atr = data.atr ?: return "SuperTrend: нет данных ATR"
        val direction = previousDirection.name
        return "SuperTrend ATR bands; направление: $direction; период: $atrPeriod; множитель: $multiplier"
    }
}

enum class TrendDirection {
    UPTREND,
    DOWNTREND
}
