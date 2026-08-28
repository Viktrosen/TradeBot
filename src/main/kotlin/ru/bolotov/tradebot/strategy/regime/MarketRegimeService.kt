package ru.bolotov.tradebot.strategy.regime

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.regime.data.RegimeState
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private val marketRegimeLogger = KotlinLogging.logger {}

/**
 * Определяет режим каждого инструмента и защищает выбор стратегии от частого
 * переключения: новый режим становится активным только после нескольких
 * последовательных закрытых M5-свечей.
 */
@Service
class MarketRegimeService(
    @Value("\${market-regime.confirmation-candles:3}")
    private val confirmationCandles: Int,
    @Value("\${market-regime.strong-trend-ema-spread-percent:0.60}")
    private val strongTrendEmaSpreadPercent: Double,
    @Value("\${market-regime.flat-ema-spread-percent:0.20}")
    private val flatEmaSpreadPercent: Double,
    @Value("\${market-regime.volatile-atr-percent:0.70}")
    private val volatileAtrPercent: Double,
    @Value("\${market-regime.flat-atr-percent:0.25}")
    private val flatAtrPercent: Double
) {
    private val states = ConcurrentHashMap<String, RegimeState>()

    init {
        require(confirmationCandles >= 1) { "Для подтверждения режима нужна хотя бы одна свеча" }
        require(flatEmaSpreadPercent <= strongTrendEmaSpreadPercent) {
            "Порог боковика не может быть выше порога сильного тренда"
        }
        require(flatAtrPercent <= volatileAtrPercent) {
            "Порог боковика по ATR не может быть выше порога высокой волатильности"
        }
    }

    /**
     * Возвращает подтверждённый режим. Вызывать метод следует один раз для
     * закрытой свечи инструмента, чтобы счётчик подтверждений не рос на тиках.
     */
    fun evaluate(marketData: MarketData): MarketRegimeDecision {
        val candidate = classify(marketData)
        val previous = states[marketData.instrumentId] ?: RegimeState()
        val consecutiveCandles = if (previous.candidate == candidate) {
            previous.consecutiveCandles + 1
        } else {
            1
        }
        val confirmedRegime = confirmRegime(previous.regime, candidate, consecutiveCandles)
        val summary = buildSummary(marketData, candidate)

        states[marketData.instrumentId] = RegimeState(
            regime = confirmedRegime,
            candidate = candidate,
            consecutiveCandles = consecutiveCandles
        )

        if (confirmedRegime != previous.regime) {
            marketRegimeLogger.info {
                "Режим рынка изменён: ${marketData.instrumentName}, " +
                    "${previous.regime} -> $confirmedRegime; $summary"
            }
        } else {
            marketRegimeLogger.debug {
                "Режим рынка: ${marketData.instrumentName}, подтверждён=$confirmedRegime, " +
                    "кандидат=$candidate, свечей=$consecutiveCandles; $summary"
            }
        }

        return MarketRegimeDecision(
            regime = confirmedRegime,
            candidate = candidate,
            consecutiveCandles = consecutiveCandles,
            summary = summary
        )
    }

    private fun classify(marketData: MarketData): MarketRegime {
        val ema50 = marketData.ema50 ?: return MarketRegime.UNCERTAIN
        val ema200 = marketData.ema200 ?: return MarketRegime.UNCERTAIN
        val atr = marketData.atr ?: return MarketRegime.UNCERTAIN
        if (ema200 <= BigDecimal.ZERO || marketData.currentPrice <= BigDecimal.ZERO) {
            return MarketRegime.UNCERTAIN
        }

        val emaSpreadPercent = relativeDifferencePercent(ema50, ema200)
        val atrPercent = percentageOf(atr, marketData.currentPrice)
        if (atrPercent >= volatileAtrPercent) return MarketRegime.VOLATILE

        return when {
            emaSpreadPercent >= strongTrendEmaSpreadPercent &&
                marketData.currentPrice > ema50 && ema50 > ema200 -> MarketRegime.STRONG_UPTREND

            emaSpreadPercent <= -strongTrendEmaSpreadPercent &&
                marketData.currentPrice < ema50 && ema50 < ema200 -> MarketRegime.STRONG_DOWNTREND

            abs(emaSpreadPercent) <= flatEmaSpreadPercent &&
                atrPercent <= flatAtrPercent -> MarketRegime.FLAT

            else -> MarketRegime.UNCERTAIN
        }
    }

    private fun confirmRegime(
        currentRegime: MarketRegime,
        candidate: MarketRegime,
        consecutiveCandles: Int
    ): MarketRegime = when {
        candidate == currentRegime -> currentRegime
        consecutiveCandles >= confirmationCandles -> candidate
        else -> currentRegime
    }

    private fun buildSummary(marketData: MarketData, candidate: MarketRegime): String {
        val ema50 = marketData.ema50 ?: return "недостаточно EMA(50)"
        val ema200 = marketData.ema200 ?: return "недостаточно EMA(200)"
        val atr = marketData.atr ?: return "недостаточно ATR"
        return "кандидат=$candidate, EMA50/EMA200=${format(relativeDifferencePercent(ema50, ema200))}%, " +
            "ATR=${format(percentageOf(atr, marketData.currentPrice))}%"
    }

    private fun relativeDifferencePercent(value: BigDecimal, base: BigDecimal): Double =
        value.subtract(base)
            .multiply(BigDecimal(100))
            .divide(base, PERCENT_SCALE, RoundingMode.HALF_UP)
            .toDouble()

    private fun percentageOf(value: BigDecimal, base: BigDecimal): Double =
        value.multiply(BigDecimal(100))
            .divide(base, PERCENT_SCALE, RoundingMode.HALF_UP)
            .toDouble()

    private fun format(value: Double): String = "%.2f".format(value)

    private companion object {
        const val PERCENT_SCALE = 8
    }
}
