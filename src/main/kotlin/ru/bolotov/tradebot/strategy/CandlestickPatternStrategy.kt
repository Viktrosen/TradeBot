package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import ru.tinkoff.piapi.core.MarketDataService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
class CandlestickPatternStrategy(
    private val marketDataService: MarketDataService
) : TradingStrategy {

    override var name = "CandlestickPatterns"
    override var description = "Стратегия на основе свечных паттернов (Engulfing, Hammer, Doji, Morning/Evening Star и др.)"

    // Настройки стратегии
    var minConfidence: Double = 0.6
    var lookbackCandles: Int = 30

    // Поддерживаемые интервалы свечей
    enum class CandleTimeframe(val interval: CandleInterval, val minutes: Int) {
        M1(CandleInterval.CANDLE_INTERVAL_1_MIN, 1),
        M2(CandleInterval.CANDLE_INTERVAL_2_MIN, 2),
        M3(CandleInterval.CANDLE_INTERVAL_3_MIN, 3),
        M5(CandleInterval.CANDLE_INTERVAL_5_MIN, 5),
        M10(CandleInterval.CANDLE_INTERVAL_10_MIN, 10),
        M15(CandleInterval.CANDLE_INTERVAL_15_MIN, 15),
        M30(CandleInterval.CANDLE_INTERVAL_30_MIN, 30),
        H1(CandleInterval.CANDLE_INTERVAL_HOUR, 60),
        H2(CandleInterval.CANDLE_INTERVAL_2_HOUR, 120),
        H4(CandleInterval.CANDLE_INTERVAL_4_HOUR, 240),
        DAY(CandleInterval.CANDLE_INTERVAL_DAY, 1440),
        WEEK(CandleInterval.CANDLE_INTERVAL_WEEK, 10080),
        MONTH(CandleInterval.CANDLE_INTERVAL_MONTH, 43200)
    }

    var currentTimeframe: CandleTimeframe = CandleTimeframe.M5

    enum class PatternType {
        BULLISH_ENGULFING,
        BEARISH_ENGULFING,
        HAMMER,
        INVERTED_HAMMER,
        DOJI,
        MORNING_STAR,
        EVENING_STAR,
        PIERCING_LINE,
        DARK_CLOUD_COVER,
        THREE_WHITE_SOLDIERS,
        THREE_BLACK_CROWS,
        SHOOTING_STAR,
        HANGING_MAN,
        MARUBOZU,
        SPINNING_TOP
    }

    data class PatternResult(
        val pattern: PatternType?,
        val direction: OrderDirection,
        val confidence: Double,
        val description: String
    )

    override fun analyze(data: MarketData): Signal {
        // Для свечных паттернов нужны исторические свечи, поэтому возвращаем HOLD
        // Паттерны проверяются через analyzeWithCandles()
        return Signal.HOLD
    }

    /**
     * Анализ с получением свечей (основной метод)
     */
    suspend fun analyzeWithCandles(instrumentUid: String): Signal {
        return try {
            val candles = fetchCandles(instrumentUid)
            if (candles.size < 3) {
                return Signal.HOLD
            }

            val patternResult = detectPatterns(candles)

            if (patternResult.pattern != null && patternResult.confidence >= minConfidence) {
                logger.info { "🔍 Обнаружен паттерн: ${patternResult.description} (confidence: ${patternResult.confidence})" }
                Signal(
                    direction = patternResult.direction,
                    confidence = patternResult.confidence,
                    reason = patternResult.description
                )
            } else {
                Signal.HOLD
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка анализа свечных паттернов для $instrumentUid" }
            Signal.HOLD
        }
    }

    /**
     * Получение исторических свечей с текущим интервалом
     */
    private suspend fun fetchCandles(instrumentUid: String): List<HistoricCandle> {
        val now = Instant.now()
        val interval = currentTimeframe.interval
        val minutes = currentTimeframe.minutes

        // Рассчитываем from с запасом (lookbackCandles + 10 для индикаторов)
        val from = now.minusSeconds((lookbackCandles + 10) * minutes * 60L)

        return try {
            marketDataService.getCandlesSync(instrumentUid, from, now, interval)
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения свечей для $instrumentUid (интервал: $minutes мин)" }
            emptyList()
        }
    }

    /**
     * Смена таймфрейма для анализа
     */
    fun setTimeframe(timeframe: CandleTimeframe) {
        currentTimeframe = timeframe
        logger.info { "🕯️ Таймфрейм свечной стратегии изменён на ${timeframe.name} (${timeframe.minutes} мин)" }
    }

    private fun detectPatterns(candles: List<HistoricCandle>): PatternResult {
        if (candles.size < 3) return PatternResult(null, OrderDirection.HOLD, 0.0, "")

        val last = candles.last()
        val prev = candles[candles.size - 2]
        val prev2 = if (candles.size >= 3) candles[candles.size - 3] else null

        // 1. Поглощения (Engulfing)
        if (isBullishEngulfing(prev, last)) {
            return PatternResult(PatternType.BULLISH_ENGULFING, OrderDirection.BUY, 0.85, "Бычье поглощение")
        }
        if (isBearishEngulfing(prev, last)) {
            return PatternResult(PatternType.BEARISH_ENGULFING, OrderDirection.SELL, 0.85, "Медвежье поглощение")
        }

        // 2. Молот / Повешенный / Падающая звезда
        val hammerResult = detectHammerAndRelated(last, candles)
        if (hammerResult != null) return hammerResult

        // 3. Доджи
        if (isDoji(last)) {
            val trend = detectTrend(candles, 5)
            return when (trend) {
                OrderDirection.SELL -> PatternResult(PatternType.DOJI, OrderDirection.BUY, 0.6, "Доджи после нисходящего тренда")
                OrderDirection.BUY -> PatternResult(PatternType.DOJI, OrderDirection.SELL, 0.6, "Доджи после восходящего тренда")
                else -> PatternResult(PatternType.DOJI, OrderDirection.HOLD, 0.5, "Доджи (тренд не определён)")
            }
        }

        // 4. Утренняя / Вечерняя звезда
        if (prev2 != null && isMorningStar(prev2, prev, last)) {
            return PatternResult(PatternType.MORNING_STAR, OrderDirection.BUY, 0.8, "Утренняя звезда")
        }
        if (prev2 != null && isEveningStar(prev2, prev, last)) {
            return PatternResult(PatternType.EVENING_STAR, OrderDirection.SELL, 0.8, "Вечерняя звезда")
        }

        // 5. Пробивающая линия / Тёмное облако
        val piercingResult = detectPiercingAndDarkCloud(prev, last)
        if (piercingResult != null) return piercingResult

        // 6. Три белых солдата / Три чёрных ворона
        val soldiersResult = detectThreeSoldiersOrCrows(candles)
        if (soldiersResult != null) return soldiersResult

        // 7. Марудзо (сплошная свеча)
        val marubozuResult = detectMarubozu(last)
        if (marubozuResult != null) return marubozuResult

        // 8. Волчок (Spinning Top) - неопределённость
        val spinningTopResult = detectSpinningTop(last)
        if (spinningTopResult != null) return spinningTopResult

        return PatternResult(null, OrderDirection.HOLD, 0.0, "")
    }

    private fun isBullishEngulfing(prev: HistoricCandle, curr: HistoricCandle): Boolean {
        val prevOpen = candleOpen(prev)
        val prevClose = candleClose(prev)
        val currOpen = candleOpen(curr)
        val currClose = candleClose(curr)

        // Предыдущая свеча медвежья, текущая бычья, тело текущей полностью перекрывает тело предыдущей
        return prevClose < prevOpen &&
                currClose > currOpen &&
                currOpen < prevClose &&
                currClose > prevOpen
    }

    private fun isBearishEngulfing(prev: HistoricCandle, curr: HistoricCandle): Boolean {
        val prevOpen = candleOpen(prev)
        val prevClose = candleClose(prev)
        val currOpen = candleOpen(curr)
        val currClose = candleClose(curr)

        return prevClose > prevOpen &&
                currClose < currOpen &&
                currOpen > prevClose &&
                currClose < prevOpen
    }

    private fun detectHammerAndRelated(candle: HistoricCandle, context: List<HistoricCandle>): PatternResult? {
        val open = candleOpen(candle)
        val close = candleClose(candle)
        val high = candleHigh(candle)
        val low = candleLow(candle)

        val body = (open - close).abs()
        val upperShadow = (high - maxOf(open, close)).abs()
        val lowerShadow = (minOf(open, close) - low).abs()

        // Молот: длинная нижняя тень (≥ 2× тела), короткая верхняя
        val isHammerShape = lowerShadow >= body * BigDecimal("2") && upperShadow <= body * BigDecimal("0.3")

        // Падающая звезда: длинная верхняя тень (≥ 2× тела), короткая нижняя
        val isShootingStarShape = upperShadow >= body * BigDecimal("2") && lowerShadow <= body * BigDecimal("0.3")

        if (!isHammerShape && !isShootingStarShape) return null

        val trend = detectTrend(context, 10)

        return when {
            isHammerShape && trend == OrderDirection.SELL ->
                PatternResult(PatternType.HAMMER, OrderDirection.BUY, 0.75, "Молот (разворот вверх)")
            isHammerShape && trend == OrderDirection.BUY ->
                PatternResult(PatternType.HANGING_MAN, OrderDirection.SELL, 0.7, "Повешенный (разворот вниз)")
            isShootingStarShape && trend == OrderDirection.BUY ->
                PatternResult(PatternType.SHOOTING_STAR, OrderDirection.SELL, 0.75, "Падающая звезда (разворот вниз)")
            else -> null
        }
    }

    private fun isDoji(candle: HistoricCandle): Boolean {
        val open = candleOpen(candle)
        val close = candleClose(candle)
        val body = (open - close).abs()
        val high = candleHigh(candle)
        val low = candleLow(candle)
        val totalRange = high - low

        return totalRange > BigDecimal.ZERO && body / totalRange <= BigDecimal("0.1")
    }

    private fun isMorningStar(c1: HistoricCandle, c2: HistoricCandle, c3: HistoricCandle): Boolean {
        val firstBearish = candleClose(c1) < candleOpen(c1)
        val secondSmall = (candleOpen(c2) - candleClose(c2)).abs() <
                (candleOpen(c1) - candleClose(c1)).abs() * BigDecimal("0.5")
        val thirdBullish = candleClose(c3) > candleOpen(c3)
        val thirdClosesAbove = candleClose(c3) > (candleOpen(c1) + candleClose(c1)) / BigDecimal("2")

        return firstBearish && secondSmall && thirdBullish && thirdClosesAbove
    }

    private fun isEveningStar(c1: HistoricCandle, c2: HistoricCandle, c3: HistoricCandle): Boolean {
        val firstBullish = candleClose(c1) > candleOpen(c1)
        val secondSmall = (candleOpen(c2) - candleClose(c2)).abs() <
                (candleOpen(c1) - candleClose(c1)).abs() * BigDecimal("0.5")
        val thirdBearish = candleClose(c3) < candleOpen(c3)
        val thirdClosesBelow = candleClose(c3) < (candleOpen(c1) + candleClose(c1)) / BigDecimal("2")

        return firstBullish && secondSmall && thirdBearish && thirdClosesBelow
    }

    private fun detectPiercingAndDarkCloud(prev: HistoricCandle, curr: HistoricCandle): PatternResult? {
        val prevOpen = candleOpen(prev)
        val prevClose = candleClose(prev)
        val currOpen = candleOpen(curr)
        val currClose = candleClose(curr)

        // Пробивающая линия (бычий)
        val isPiercing = prevClose < prevOpen &&
                currClose > currOpen &&
                currOpen < prevClose &&
                currClose > (prevOpen + prevClose) / BigDecimal("2")

        if (isPiercing) {
            return PatternResult(PatternType.PIERCING_LINE, OrderDirection.BUY, 0.7, "Пробивающая линия")
        }

        // Тёмное облако (медвежий)
        val isDarkCloud = prevClose > prevOpen &&
                currClose < currOpen &&
                currOpen > prevClose &&
                currClose < (prevOpen + prevClose) / BigDecimal("2")

        if (isDarkCloud) {
            return PatternResult(PatternType.DARK_CLOUD_COVER, OrderDirection.SELL, 0.7, "Тёмное облако")
        }

        return null
    }

    private fun detectThreeSoldiersOrCrows(candles: List<HistoricCandle>): PatternResult? {
        if (candles.size < 3) return null

        val last3 = candles.takeLast(3)

        // Три белых солдата (бычий)
        val allBullish = last3.all { candleClose(it) > candleOpen(it) }
        val increasingCloses = candleClose(last3[0]) < candleClose(last3[1]) &&
                candleClose(last3[1]) < candleClose(last3[2])
        val bodiesGrowing = (candleClose(last3[0]) - candleOpen(last3[0])).abs() <
                (candleClose(last3[1]) - candleOpen(last3[1])).abs() &&
                (candleClose(last3[1]) - candleOpen(last3[1])).abs() <
                (candleClose(last3[2]) - candleOpen(last3[2])).abs()

        if (allBullish && increasingCloses && bodiesGrowing) {
            return PatternResult(PatternType.THREE_WHITE_SOLDIERS, OrderDirection.BUY, 0.85, "Три белых солдата")
        }

        // Три чёрных ворона (медвежий)
        val allBearish = last3.all { candleClose(it) < candleOpen(it) }
        val decreasingCloses = candleClose(last3[0]) > candleClose(last3[1]) &&
                candleClose(last3[1]) > candleClose(last3[2])

        if (allBearish && decreasingCloses) {
            return PatternResult(PatternType.THREE_BLACK_CROWS, OrderDirection.SELL, 0.85, "Три чёрных ворона")
        }

        return null
    }

    private fun detectMarubozu(candle: HistoricCandle): PatternResult? {
        val open = candleOpen(candle)
        val close = candleClose(candle)
        val high = candleHigh(candle)
        val low = candleLow(candle)

        val body = (open - close).abs()
        val upperShadow = (high - maxOf(open, close)).abs()
        val lowerShadow = (minOf(open, close) - low).abs()

        // Марудзо: тени не более 5% от тела
        val isMarubozu = upperShadow <= body * BigDecimal("0.05") && lowerShadow <= body * BigDecimal("0.05")

        if (!isMarubozu) return null

        return if (close > open) {
            PatternResult(PatternType.MARUBOZU, OrderDirection.BUY, 0.65, "Белое марудзо (сильный бычий импульс)")
        } else {
            PatternResult(PatternType.MARUBOZU, OrderDirection.SELL, 0.65, "Чёрное марудзо (сильный медвежий импульс)")
        }
    }

    private fun detectSpinningTop(candle: HistoricCandle): PatternResult? {
        val open = candleOpen(candle)
        val close = candleClose(candle)
        val high = candleHigh(candle)
        val low = candleLow(candle)

        val body = (open - close).abs()
        val upperShadow = (high - maxOf(open, close)).abs()
        val lowerShadow = (minOf(open, close) - low).abs()

        // Волчок: тело маленькое (≤ 20% от диапазона), тени примерно равны
        val totalRange = high - low
        val isSpinningTop = totalRange > BigDecimal.ZERO &&
                body / totalRange <= BigDecimal("0.2") &&
                (upperShadow / totalRange) in BigDecimal("0.3")..BigDecimal("0.7") &&
                (lowerShadow / totalRange) in BigDecimal("0.3")..BigDecimal("0.7")

        return if (isSpinningTop) {
            PatternResult(PatternType.SPINNING_TOP, OrderDirection.HOLD, 0.3, "Волчок (неопределённость)")
        } else {
            null
        }
    }

    private fun detectTrend(candles: List<HistoricCandle>, period: Int): OrderDirection {
        if (candles.size < period) return OrderDirection.HOLD

        val closes = candles.takeLast(period).map { candleClose(it) }
        val sma = closes.reduce { a, b -> a + b } / BigDecimal(period)
        val lastClose = closes.last()

        return if (lastClose > sma) OrderDirection.BUY
        else if (lastClose < sma) OrderDirection.SELL
        else OrderDirection.HOLD
    }

    override fun getExplanation(data: MarketData): String {
        return """
            |🕯️ СВЕЧНАЯ СТРАТЕГИЯ
            |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            |📊 Таймфрейм: ${currentTimeframe.name} (${currentTimeframe.minutes} мин)
            |🎯 Мин. уверенность: ${"%.0f".format(minConfidence * 100)}%
            |
            |📋 Обнаруживаемые паттерны:
            |  • Бычье/Медвежье поглощение (Engulfing)
            |  • Молот / Повешенный / Падающая звезда
            |  • Доджи (Doji)
            |  • Утренняя / Вечерняя звезда
            |  • Пробивающая линия / Тёмное облако
            |  • Три белых солдата / Три чёрных ворона
            |  • Марудзо (Marubozu)
            |  • Волчок (Spinning Top)
            |
            |💡 Для анализа требуется вызов analyzeWithCandles()
        """.trimMargin()
    }

    // Хелперы для извлечения значений из свечи
    private fun candleOpen(candle: HistoricCandle): BigDecimal =
        candle.open.units.toBigDecimal() + candle.open.nano.toBigDecimal().divide(BigDecimal("1e9"), 8, RoundingMode.HALF_UP)

    private fun candleClose(candle: HistoricCandle): BigDecimal =
        candle.close.units.toBigDecimal() + candle.close.nano.toBigDecimal().divide(BigDecimal("1e9"), 8, RoundingMode.HALF_UP)

    private fun candleHigh(candle: HistoricCandle): BigDecimal =
        candle.high.units.toBigDecimal() + candle.high.nano.toBigDecimal().divide(BigDecimal("1e9"), 8, RoundingMode.HALF_UP)

    private fun candleLow(candle: HistoricCandle): BigDecimal =
        candle.low.units.toBigDecimal() + candle.low.nano.toBigDecimal().divide(BigDecimal("1e9"), 8, RoundingMode.HALF_UP)
}