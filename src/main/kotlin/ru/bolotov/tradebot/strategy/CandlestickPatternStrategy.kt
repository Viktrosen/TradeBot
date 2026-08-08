package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import ru.tinkoff.piapi.core.MarketDataService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class CandlestickPatternStrategy(
    private val marketDataService: MarketDataService
) : TradingStrategy {

    override var name = "CandlestickPatterns"
    override var description = "Стратегия на основе свечных паттернов (Engulfing, Hammer, Doji, Morning/Evening Star и др.)"

    // Настройки стратегии
    var minConfidence: Double = 0.70
    var lookbackCandles: Int = 30
    private val loggedPatternKeys = ConcurrentHashMap<String, String>()
    private val candleCache = ConcurrentHashMap<String, CachedCandles>()

    private data class CachedCandles(
        val candles: List<HistoricCandle>,
        val loadedAt: Instant
    )

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
        val description: String,
        val candleKey: String? = null
    )

    data class PatternBacktestStats(
        val signals: Int,
        val profitableSignals: Int,
        val winRate: Double,
        val averageReturnPercent: Double
    )

    data class CandlestickBacktestReport(
        val timeframe: CandleTimeframe,
        val signals: Int,
        val winRate: Double,
        val averageReturnPercent: Double,
        val byPattern: Map<PatternType, PatternBacktestStats>
    )

    override fun analyze(data: MarketData): Signal {
        val patternResult = data.candlestickPattern
        if (patternResult == null || patternResult.pattern == null) {
            return Signal.HOLD
        }

        if (patternResult.confidence >= minConfidence) {
            return Signal(
                direction = patternResult.direction,
                confidence = patternResult.confidence,
                reason = patternResult.description
            )
        }

        return Signal.HOLD
    }

    /**
     * Анализ с получением свечей (основной метод)
     */
    suspend fun analyzePatternWithCandles(
        instrumentUid: String,
        confirmationPrice: BigDecimal? = null
    ): PatternResult {
        return try {
            val candles = fetchCandles(instrumentUid)
            if (candles.size < 3) {
                return PatternResult(null, OrderDirection.HOLD, 0.0, "Недостаточно свечей")
            }

            val candleKey = "${currentTimeframe.name}:${candles.last().time.seconds}:${candles.last().time.nanos}"
            val patternResult = scorePattern(detectPatterns(candles), candles).copy(candleKey = candleKey)

            if (!isConfirmedByPrice(patternResult, candles.last(), confirmationPrice)) {
                return PatternResult(
                    pattern = null,
                    direction = OrderDirection.HOLD,
                    confidence = patternResult.confidence,
                    description = "Паттерн ожидает подтверждения пробоем экстремума свечи"
                )
            }

            if (patternResult.pattern != null && patternResult.confidence >= minConfidence) {
                if (loggedPatternKeys.put(instrumentUid, candleKey) != candleKey) {
                    logger.info {
                        "Обнаружен паттерн для $instrumentUid: ${patternResult.description} " +
                            "(уверенность: ${patternResult.confidence})"
                    }
                }
                patternResult
            } else {
                PatternResult(null, OrderDirection.HOLD, patternResult.confidence, patternResult.description)
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка анализа свечных паттернов для $instrumentUid" }
            PatternResult(null, OrderDirection.HOLD, 0.0, "Ошибка анализа свечных паттернов")
        }
    }

    suspend fun analyzeWithCandles(instrumentUid: String): Signal {
        val patternResult = analyzePatternWithCandles(instrumentUid)
        return if (patternResult.pattern != null && patternResult.confidence >= minConfidence) {
            Signal(
                direction = patternResult.direction,
                confidence = patternResult.confidence,
                reason = patternResult.description
            )
        } else {
            Signal.HOLD
        }
    }

    fun backtest(
        candles: List<HistoricCandle>,
        holdingCandles: Int = DEFAULT_BACKTEST_HOLDING_CANDLES
    ): CandlestickBacktestReport {
        require(holdingCandles > 0) { "Количество свечей удержания должно быть положительным" }

        val returnsByPattern = mutableMapOf<PatternType, MutableList<Double>>()
        val firstSignalIndex = maxOf(lookbackCandles, 3)
        for (signalIndex in firstSignalIndex until candles.size - holdingCandles) {
            val context = candles.take(signalIndex + 1)
            val result = scorePattern(detectPatterns(context), context)
            if (result.pattern == null || result.direction == OrderDirection.HOLD || result.confidence < minConfidence) {
                continue
            }

            val entryPrice = candleClose(candles[signalIndex])
            val exitPrice = candleClose(candles[signalIndex + holdingCandles])
            val returnPercent = calculateBacktestReturn(result.direction, entryPrice, exitPrice)
            returnsByPattern.getOrPut(result.pattern) { mutableListOf() }.add(returnPercent)
        }

        val byPattern = returnsByPattern.mapValues { (_, returns) -> returns.toBacktestStats() }
        val allReturns = returnsByPattern.values.flatten()
        return CandlestickBacktestReport(
            timeframe = currentTimeframe,
            signals = allReturns.size,
            winRate = allReturns.winRate(),
            averageReturnPercent = allReturns.averageOrZero(),
            byPattern = byPattern
        )
    }

    private fun calculateBacktestReturn(
        direction: OrderDirection,
        entryPrice: BigDecimal,
        exitPrice: BigDecimal
    ): Double {
        if (entryPrice <= BigDecimal.ZERO) return 0.0
        val multiplier = if (direction == OrderDirection.BUY) BigDecimal.ONE else BigDecimal.ONE.negate()
        return (exitPrice - entryPrice)
            .multiply(multiplier)
            .multiply(BigDecimal(100))
            .divide(entryPrice, 8, RoundingMode.HALF_UP)
            .toDouble()
    }

    private fun List<Double>.toBacktestStats(): PatternBacktestStats = PatternBacktestStats(
        signals = size,
        profitableSignals = count { it > 0.0 },
        winRate = winRate(),
        averageReturnPercent = averageOrZero()
    )

    private fun List<Double>.winRate(): Double =
        if (isEmpty()) 0.0 else count { it > 0.0 } * 100.0 / size

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    /**
     * Получение исторических свечей с текущим интервалом
     */
    private suspend fun fetchCandles(instrumentUid: String): List<HistoricCandle> {
        val now = Instant.now()
        val cacheKey = "$instrumentUid:${currentTimeframe.name}"
        candleCache[cacheKey]
            ?.takeIf { cache -> now.minusSeconds(CANDLE_CACHE_TTL_SECONDS).isBefore(cache.loadedAt) }
            ?.let { return it.candles }
        val interval = currentTimeframe.interval
        val minutes = currentTimeframe.minutes

        // Рассчитываем from с запасом (lookbackCandles + 10 для индикаторов)
        val from = now.minusSeconds((lookbackCandles + 10) * minutes * 60L)

        return try {
            val candles = marketDataService.getCandlesSync(instrumentUid, from, now, interval)
                .filter { candle ->
                    val candleStart = Instant.ofEpochSecond(candle.time.seconds, candle.time.nanos.toLong())
                    !candleStart.plusSeconds(minutes * 60L).isAfter(now)
                }
            candleCache[cacheKey] = CachedCandles(candles = candles, loadedAt = now)
            candles
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
        candleCache.clear()
        logger.info { "🕯️ Таймфрейм свечной стратегии изменён на ${timeframe.name} (${timeframe.minutes} мин)" }
    }

    fun configureMinConfidence(value: Double) {
        require(value in 0.70..1.0) { "Минимальная уверенность свечной стратегии должна быть от 0.70 до 1" }
        minConfidence = value
        logger.info { "Минимальная уверенность свечной стратегии изменена на $value" }
    }

    private fun isConfirmedByPrice(
        patternResult: PatternResult,
        signalCandle: HistoricCandle,
        currentPrice: BigDecimal?
    ): Boolean = when (patternResult.direction) {
        OrderDirection.BUY -> currentPrice == null || currentPrice > candleHigh(signalCandle)
        OrderDirection.SELL -> currentPrice == null || currentPrice < candleLow(signalCandle)
        OrderDirection.HOLD -> true
    }

    private fun scorePattern(
        patternResult: PatternResult,
        candles: List<HistoricCandle>
    ): PatternResult {
        val pattern = patternResult.pattern ?: return patternResult
        if (pattern in setOf(PatternType.DOJI, PatternType.SPINNING_TOP)) {
            return patternResult.copy(
                direction = OrderDirection.HOLD,
                confidence = minOf(patternResult.confidence, INFORMATIONAL_PATTERN_CONFIDENCE)
            )
        }

        val trend = detectTrend(candles, minOf(lookbackCandles, 10))
        val score = patternResult.confidence + trendAdjustment(pattern, patternResult.direction, trend) +
            volumeAdjustment(candles) + rangeAdjustment(candles) +
            supportResistanceAdjustment(patternResult.direction, candles)

        return patternResult.copy(confidence = score.coerceIn(0.0, MAX_PATTERN_CONFIDENCE))
    }

    private fun trendAdjustment(
        pattern: PatternType,
        direction: OrderDirection,
        trend: OrderDirection
    ): Double {
        if (trend == OrderDirection.HOLD) return -0.05
        val expectedTrend = if (pattern in CONTINUATION_PATTERNS) direction else opposite(direction)
        return if (trend == expectedTrend) 0.10 else -0.15
    }

    private fun volumeAdjustment(candles: List<HistoricCandle>): Double {
        if (candles.size < 6) return 0.0
        val averageVolume = candles.dropLast(1).takeLast(5).map { it.volume }.average()
        if (averageVolume <= 0.0) return 0.0
        return when {
            candles.last().volume >= averageVolume * 1.2 -> 0.08
            candles.last().volume < averageVolume * 0.7 -> -0.10
            else -> 0.0
        }
    }

    private fun rangeAdjustment(candles: List<HistoricCandle>): Double {
        if (candles.size < 6) return 0.0
        val averageRange = averageRange(candles.dropLast(1).takeLast(5))
        if (averageRange <= BigDecimal.ZERO) return 0.0
        return if (candleRange(candles.last()) >= averageRange * BigDecimal("0.6")) 0.05 else -0.10
    }

    private fun supportResistanceAdjustment(
        direction: OrderDirection,
        candles: List<HistoricCandle>
    ): Double {
        if (direction == OrderDirection.HOLD || candles.size < 6) return 0.0
        val context = candles.dropLast(1).takeLast(lookbackCandles)
        val tolerance = averageRange(context) * BigDecimal("1.5")
        if (tolerance <= BigDecimal.ZERO) return 0.0

        val close = candleClose(candles.last())
        val nearestSupport = context.minOf(::candleLow)
        val nearestResistance = context.maxOf(::candleHigh)
        return when (direction) {
            OrderDirection.BUY -> if (close - nearestSupport <= tolerance) 0.05 else 0.0
            OrderDirection.SELL -> if (nearestResistance - close <= tolerance) 0.05 else 0.0
            OrderDirection.HOLD -> 0.0
        }
    }

    private fun candleRange(candle: HistoricCandle): BigDecimal = candleHigh(candle) - candleLow(candle)

    private fun averageRange(candles: List<HistoricCandle>): BigDecimal {
        if (candles.isEmpty()) return BigDecimal.ZERO
        return candles.map(::candleRange).reduce(BigDecimal::add) / candles.size.toBigDecimal()
    }

    private fun opposite(direction: OrderDirection): OrderDirection = when (direction) {
        OrderDirection.BUY -> OrderDirection.SELL
        OrderDirection.SELL -> OrderDirection.BUY
        OrderDirection.HOLD -> OrderDirection.HOLD
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
        val range = high - low

        if (range <= BigDecimal.ZERO || body < range * MIN_BODY_TO_RANGE_RATIO) return null

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

        if (body <= BigDecimal.ZERO) return null

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
        val patternResult = data.candlestickPattern
        if (patternResult?.pattern != null) {
            return """
                |🕯️ Анализ по свечной стратегии
                |Таймфрейм: ${currentTimeframe.name} (${currentTimeframe.minutes} мин)
                |
                |Инструмент: ${data.instrumentName}
                |Цена: ${data.currentPrice}
                |Паттерн: ${patternResult.description}
                |Тип: ${patternResult.pattern}
                |Сигнал: ${patternResult.direction}
                |Уверенность: ${"%.0f".format(patternResult.confidence * 100)}%
                |
                |Причина: ${patternResult.description} → ${patternResult.direction}
            """.trimMargin()
        }

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

    private companion object {
        const val CANDLE_CACHE_TTL_SECONDS = 5L
        const val DEFAULT_BACKTEST_HOLDING_CANDLES = 5
        const val INFORMATIONAL_PATTERN_CONFIDENCE = 0.65
        const val MAX_PATTERN_CONFIDENCE = 0.95
        val MIN_BODY_TO_RANGE_RATIO = BigDecimal("0.05")
        val CONTINUATION_PATTERNS = setOf(
            PatternType.THREE_WHITE_SOLDIERS,
            PatternType.THREE_BLACK_CROWS,
            PatternType.MARUBOZU
        )
    }
}
