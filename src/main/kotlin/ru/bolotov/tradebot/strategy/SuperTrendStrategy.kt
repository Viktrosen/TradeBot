package ru.bolotov.tradebot.strategy

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext

/**
 * Deterministic SuperTrend calculated solely from completed M5 OHLC candles.
 *
 * The calculation has no process-local state: given the same candle sequence it
 * produces the same bands, direction and signal after a restart or in a replay.
 */
@Component
class SuperTrendStrategy(
    @Value("\${strategy.supertrend.atr-period:10}") private val atrPeriod: Int,
    @Value("\${strategy.supertrend.multiplier:3.0}") private val multiplier: Double
) : TradingStrategy {

    override val name = "SuperTrend"
    override val description = "ATR-based trend following по закрытым M5-свечам"

    override fun analyze(data: MarketData): Signal {
        val states = calculateStates(data.closedCandles) ?: return Signal.HOLD
        val previous = states[states.lastIndex - 1]
        val current = states.last()
        return when {
            previous.direction != current.direction && current.direction == TrendDirection.UPTREND ->
                Signal(OrderDirection.BUY, 0.75, "SuperTrend: смена на восходящий тренд")

            previous.direction != current.direction && current.direction == TrendDirection.DOWNTREND ->
                Signal(OrderDirection.SELL, 0.75, "SuperTrend: смена на нисходящий тренд")

            else -> Signal.HOLD
        }
    }

    override fun getExplanation(data: MarketData): String = calculateStates(data.closedCandles)
        ?.lastOrNull()
        ?.let { state ->
            "SuperTrend M5; направление: ${state.direction}; период: $atrPeriod; " +
                "множитель: $multiplier; свеча=${state.candleKey}"
        }
        ?: "SuperTrend M5: недостаточно закрытых свечей; период: $atrPeriod"

    override fun getAiDetails(data: MarketData): StrategyAiDetails? = calculateStates(data.closedCandles)
        ?.lastOrNull()
        ?.let { state ->
            StrategyAiDetails(
                trendDirection = state.direction.name,
                upperBand = state.upperBand,
                lowerBand = state.lowerBand
            )
        }

    private fun calculateStates(candles: List<StrategyCandle>): List<SuperTrendState>? {
        if (atrPeriod <= 0 || candles.size < atrPeriod + 2) return null

        val atrByIndex = calculateAtr(candles) ?: return null
        val states = mutableListOf<SuperTrendState>()
        for (index in candles.indices) {
            val atr = atrByIndex[index] ?: continue
            val candle = candles[index]
            val bandOffset = atr.multiply(multiplier.toBigDecimal(), MATH_CONTEXT)
            val basicUpper = midpoint(candle).add(bandOffset, MATH_CONTEXT)
            val basicLower = midpoint(candle).subtract(bandOffset, MATH_CONTEXT)
            val previous = states.lastOrNull()
            val previousClose = candles.getOrNull(index - 1)?.close
            val upper = when {
                previous == null || previousClose == null -> basicUpper
                basicUpper < previous.upperBand || previousClose > previous.upperBand -> basicUpper
                else -> previous.upperBand
            }
            val lower = when {
                previous == null || previousClose == null -> basicLower
                basicLower > previous.lowerBand || previousClose < previous.lowerBand -> basicLower
                else -> previous.lowerBand
            }
            val direction = when {
                previous == null -> TrendDirection.UPTREND
                previous.direction == TrendDirection.UPTREND && candle.close < lower -> TrendDirection.DOWNTREND
                previous.direction == TrendDirection.DOWNTREND && candle.close > upper -> TrendDirection.UPTREND
                else -> previous.direction
            }
            states += SuperTrendState(candle.key, direction, upper, lower)
        }
        return states.takeIf { it.size >= 2 }
    }

    /** Wilder ATR aligned with candle indices; values before the seed are unavailable. */
    private fun calculateAtr(candles: List<StrategyCandle>): List<BigDecimal?>? {
        val trueRanges = MutableList<BigDecimal?>(candles.size) { null }
        for (index in 1 until candles.size) {
            val candle = candles[index]
            val previousClose = candles[index - 1].close
            trueRanges[index] = listOf(
                candle.high.subtract(candle.low),
                candle.high.subtract(previousClose).abs(),
                candle.low.subtract(previousClose).abs()
            ).maxOrNull()
        }
        val seedRanges = (1..atrPeriod).mapNotNull(trueRanges::get)
        if (seedRanges.size != atrPeriod) return null

        val result = MutableList<BigDecimal?>(candles.size) { null }
        var atr = seedRanges.reduce(BigDecimal::add).divide(atrPeriod.toBigDecimal(), MATH_CONTEXT)
        result[atrPeriod] = atr
        for (index in atrPeriod + 1 until candles.size) {
            val range = requireNotNull(trueRanges[index])
            atr = atr.multiply((atrPeriod - 1).toBigDecimal(), MATH_CONTEXT)
                .add(range, MATH_CONTEXT)
                .divide(atrPeriod.toBigDecimal(), MATH_CONTEXT)
            result[index] = atr
        }
        return result
    }

    private fun midpoint(candle: StrategyCandle): BigDecimal = candle.high.add(candle.low)
        .divide(BigDecimal(2), MATH_CONTEXT)

    private companion object {
        val MATH_CONTEXT: MathContext = MathContext.DECIMAL64
    }
}

private data class SuperTrendState(
    val candleKey: String,
    val direction: TrendDirection,
    val upperBand: BigDecimal,
    val lowerBand: BigDecimal
)

enum class TrendDirection {
    UPTREND,
    DOWNTREND
}
