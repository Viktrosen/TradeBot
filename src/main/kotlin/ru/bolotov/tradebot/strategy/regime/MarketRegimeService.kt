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
    private val flatAtrPercent: Double,
    // НОВЫЕ параметры
    @Value("\${market-regime.chop-threshold:61.8}")
    private val chopThreshold: Double,
    @Value("\${market-regime.atr-extreme-percent:2.0}")
    private val extremeAtrPercent: Double,
    @Value("\${market-regime.atr-high-percent:1.0}")
    private val highAtrPercent: Double,
    @Value("\${market-regime.atr-normal-percent:0.5}")
    private val normalAtrPercent: Double,
    @Value("\${market-regime.atr-low-percent:0.25}")
    private val lowAtrPercent: Double,
    @Value("\${market-regime.adx-threshold:25.0}")
    private val adxThreshold: Double
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
        require(chopThreshold in 0.0..100.0) { "Choppiness threshold должен быть от 0 до 100" }
        require(extremeAtrPercent > highAtrPercent) { "EXTREME ATR должен быть выше HIGH ATR" }
        require(highAtrPercent > normalAtrPercent) { "HIGH ATR должен быть выше NORMAL ATR" }
        require(normalAtrPercent > lowAtrPercent) { "NORMAL ATR должен быть выше LOW ATR" }
        require(adxThreshold in 0.0..100.0) { "ADX threshold должен быть от 0 до 100" }
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

        // НОВОЕ: 1. Проверка экстремальной волатильности (приоритет №1)
        val atrPercent = percentageOf(atr, marketData.currentPrice)
        if (atrPercent >= extremeAtrPercent) {
            return MarketRegime.EXTREME_VOLATILE
        }

        // НОВОЕ: 2. Проверка choppiness (хаотичность)
        val choppiness = calculateChoppinessIndex(marketData)
        if (choppiness > chopThreshold) {
            return if (atrPercent >= normalAtrPercent) {
                MarketRegime.FLAT_HIGH_VOL
            } else {
                MarketRegime.FLAT_LOW_VOL
            }
        }

        // НОВОЕ: 3. Проверка силы тренда (ADX)
        val adx = marketData.adx ?: 0.0
        if (adx < adxThreshold) {
            return MarketRegime.WEAK_TREND
        }

        // 4. Определяем направление (EMA) — существующая логика
        val emaSpreadPercent = relativeDifferencePercent(ema50, ema200)
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

    // НОВОЕ: Choppiness Index — прямое измерение хаотичности рынка
    private fun calculateChoppinessIndex(marketData: MarketData): Double {
        val atrSum = sumRecentATR(marketData.atr, 14)
        val high14 = marketData.high14 ?: marketData.currentPrice
        val low14 = marketData.low14 ?: marketData.currentPrice
        val priceRange = high14 - low14

        return if (priceRange > BigDecimal.ZERO && atrSum > 0) {
            -Math.log10(atrSum / priceRange.toDouble()) / Math.log10(14.0)
        } else 50.0
    }

    private fun sumRecentATR(atr: BigDecimal?, period: Int): Double {
        // Упрощённый расчёт: используем текущий ATR как proxy для SUM(ATR, period)
        // Точный расчёт требует истории True Range
        return atr?.toDouble() ?: 0.0 * period
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

        val atrPercent = percentageOf(atr, marketData.currentPrice)
        val choppiness = calculateChoppinessIndex(marketData)
        val adx = marketData.adx ?: 0.0

        return "кандидат=$candidate, EMA50/EMA200=${format(relativeDifferencePercent(ema50, ema200))}%, " +
            "ATR=${format(atrPercent)}%, CHOP=${format(choppiness)}, ADX=${format(adx)}"
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
