package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

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

    private val states = ConcurrentHashMap<String, SuperTrendState>()

    override fun analyze(data: MarketData): Signal {
        val atr = data.atr ?: return Signal.HOLD
        if (atr <= BigDecimal.ZERO || data.currentPrice <= BigDecimal.ZERO) {
            return Signal.HOLD
        }

        val previous = states[data.instrumentId]
        // При отсутствии предыдущей закрытой свечи направление ещё не определено.
        // Это исключает вход на первом же тике после старта приложения.
        if (previous == null) {
            states[data.instrumentId] = SuperTrendState(TrendDirection.UPTREND, BigDecimal.ZERO, BigDecimal.ZERO)
            return Signal.HOLD
        }

        val middle = data.currentPrice

        val multiplierBD = BigDecimal.valueOf(multiplier)
        var upperBand = middle + (atr.multiply(multiplierBD))
        var lowerBand = middle - (atr.multiply(multiplierBD))

        // Adjust bands based on previous values
        var direction = previous.direction
        when (direction) {
            TrendDirection.UPTREND -> {
                if (lowerBand < previous.lowerBand) {
                    lowerBand = previous.lowerBand
                }
                if (previous.upperBand > BigDecimal.ZERO && data.currentPrice < previous.lowerBand) {
                    direction = TrendDirection.DOWNTREND
                    logger.info { "SuperTrend: смена направления на DOWNTREND для ${data.instrumentName}" }
                }
            }
            TrendDirection.DOWNTREND -> {
                if (previous.upperBand > BigDecimal.ZERO && upperBand > previous.upperBand) {
                    upperBand = previous.upperBand
                }
                if (data.currentPrice > previous.upperBand) {
                    direction = TrendDirection.UPTREND
                    logger.info { "SuperTrend: смена направления на UPTREND для ${data.instrumentName}" }
                }
            }
        }

        states[data.instrumentId] = SuperTrendState(direction, upperBand, lowerBand)

        return when {
            previous.direction != direction && direction == TrendDirection.UPTREND -> Signal(OrderDirection.BUY, 0.75, "SuperTrend: смена на восходящий тренд")
            previous.direction != direction && direction == TrendDirection.DOWNTREND -> Signal(OrderDirection.SELL, 0.75, "SuperTrend: смена на нисходящий тренд")
            else -> Signal.HOLD
        }
    }

    override fun getExplanation(data: MarketData): String {
        val direction = states[data.instrumentId]?.direction?.name ?: "UNDEFINED"
        return "SuperTrend ATR bands; направление: $direction; период: $atrPeriod; множитель: $multiplier"
    }

    override fun getAiDetails(data: MarketData): StrategyAiDetails? =
        states[data.instrumentId]?.let { state ->
            StrategyAiDetails(
                trendDirection = state.direction.name,
                upperBand = state.upperBand.takeIf { it > BigDecimal.ZERO },
                lowerBand = state.lowerBand.takeIf { it > BigDecimal.ZERO }
            )
        }
}

private data class SuperTrendState(
    val direction: TrendDirection,
    val upperBand: BigDecimal,
    val lowerBand: BigDecimal
)

enum class TrendDirection {
    UPTREND,
    DOWNTREND
}
