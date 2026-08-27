package ru.bolotov.tradebot.strategy

/*
 * [Получение только закрытых свечей]
 *                 |
 *                 v
 * [Поиск свечного паттерна на последнем окне]
 *                 |
 *                 v
 * [Фильтры: EMA(200), RSI(14), объём > 1.5 * SMA(20)]
 *                 |
 *                 v
 * [Динамическая уверенность и подтверждение ценой]
 *                 |
 *                 v
 * [Сигнал действует две свечи, затем удаляется корутиной]
 */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import ru.bolotov.tradebot.broker.getCandlesSync
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import ru.ttech.piapi.core.MarketDataServiceSync
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class CandlestickPatternStrategy(
    private val marketDataService: MarketDataServiceSync
) : TradingStrategy {

    override var name = "CandlestickPatterns"
    override var description = "Стратегия на основе закрытых свечных паттернов"

    var minConfidence: Double = MIN_ENTRY_CONFIDENCE
    var lookbackCandles: Int = DEFAULT_LOOKBACK_CANDLES
    var currentTimeframe: CandleTimeframe = CandleTimeframe.M5

    private val strategyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val candleCache = ConcurrentHashMap<String, CachedCandles>()
    private val activeSignalKeys = ConcurrentHashMap<String, String>()
    private val signalExpiryJobs = ConcurrentHashMap<String, Job>()
    private val loggedPatternKeys = ConcurrentHashMap<String, String>()

    private data class CachedCandles(
        val candles: List<HistoricCandle>,
        val loadedAt: Instant
    )

    private data class MarketFilters(
        val globalTrend: OrderDirection,
        val rsi: BigDecimal?,
        val volumeSpike: Boolean
    )

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

    enum class PatternType {
        BULLISH_ENGULFING,
        BEARISH_ENGULFING,
        HAMMER,
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

    override fun analyze(data: MarketData): Signal {
        val result = data.candlestickPattern ?: return Signal.HOLD
        if (result.pattern == null || !isSignalActive(data.instrumentId, result.candleKey)) {
            return Signal.HOLD
        }
        if (result.confidence < minConfidence) return Signal.HOLD

        return Signal(
            direction = result.direction,
            confidence = result.confidence,
            reason = result.description
        )
    }

    /**
     * Анализирует только закрытые свечи выбранного таймфрейма, применяет
     * технические фильтры и возвращает сигнал, действующий ограниченное время.
     */
    suspend fun analyzePatternWithCandles(
        instrumentUid: String,
        confirmationPrice: BigDecimal? = null
    ): PatternResult {
        return try {
            val candles = fetchClosedCandles(instrumentUid)
            if (candles.size < MIN_REQUIRED_CANDLES) return insufficientCandlesResult()

            val candleKey = candleKey(candles.last())
            val rawPattern = detectPatterns(candles)
            val scoredPattern = scorePattern(rawPattern, candles).copy(candleKey = candleKey)
            val result = when {
                scoredPattern.pattern == null -> scoredPattern
                !isConfirmedByPrice(scoredPattern, candles.last(), confirmationPrice) -> pendingConfirmationResult(scoredPattern)
                scoredPattern.confidence < minConfidence -> belowConfidenceResult(scoredPattern)
                else -> scoredPattern
            }

            if (result.pattern != null) {
                registerSignalExpiry(instrumentUid, candleKey, candles.last())
                logPatternOnce(instrumentUid, result)
            }
            result
        } catch (error: Exception) {
            logger.error(error) { "Ошибка анализа свечных паттернов для $instrumentUid" }
            PatternResult(null, OrderDirection.HOLD, 0.0, "Ошибка анализа свечных паттернов")
        }
    }

    fun setTimeframe(timeframe: CandleTimeframe) {
        currentTimeframe = timeframe
        candleCache.clear()
        activeSignalKeys.clear()
        signalExpiryJobs.values.forEach(Job::cancel)
        signalExpiryJobs.clear()
        logger.info { "Таймфрейм свечной стратегии изменён на ${timeframe.name}" }
    }

    fun configureMinConfidence(value: Double) {
        require(value in MIN_ENTRY_CONFIDENCE..1.0) {
            "Минимальная уверенность свечной стратегии должна быть от $MIN_ENTRY_CONFIDENCE до 1"
        }
        minConfidence = value
    }

    private suspend fun fetchClosedCandles(instrumentUid: String): List<HistoricCandle> {
        val now = Instant.now()
        val cacheKey = "$instrumentUid:${currentTimeframe.name}"
        candleCache[cacheKey]
            ?.takeIf { now.minusSeconds(CANDLE_CACHE_TTL_SECONDS).isBefore(it.loadedAt) }
            ?.let { return it.candles }

        val requiredCandles = maxOf(lookbackCandles + INDICATOR_BUFFER_CANDLES, EMA_PERIOD + INDICATOR_BUFFER_CANDLES)
        val intervalSeconds = currentTimeframe.minutes.toLong() * SECONDS_IN_MINUTE
        val from = now.minusSeconds(requiredCandles.toLong() * intervalSeconds)
        val candles = marketDataService.getCandlesSync(instrumentUid, from, now, currentTimeframe.interval)
            .filter { isClosed(it, now, intervalSeconds) }

        candleCache[cacheKey] = CachedCandles(candles, now)
        return candles
    }

    private fun detectPatterns(candles: List<HistoricCandle>): PatternResult {
        val current = candles.lastOrNull() ?: return noPatternResult()
        val previous = candles.getOrNull(candles.lastIndex - 1) ?: return noPatternResult()

        detectEngulfing(previous, current)?.let { return it }
        detectHammerAndRelated(current, candles)?.let { return it }
        detectDoji(current, candles)?.let { return it }
        detectStar(candles)?.let { return it }
        detectPiercingAndDarkCloud(previous, current)?.let { return it }
        detectThreeSoldiersOrCrows(candles)?.let { return it }
        detectMarubozu(current)?.let { return it }
        detectSpinningTop(current)?.let { return it }
        return noPatternResult()
    }

    private fun detectEngulfing(previous: HistoricCandle, current: HistoricCandle): PatternResult? = when {
        isBearish(previous) && isBullish(current) &&
            isLess(candleOpen(current), candleClose(previous)) &&
            isGreater(candleClose(current), candleOpen(previous)) -> {
            PatternResult(PatternType.BULLISH_ENGULFING, OrderDirection.BUY, 0.85, "Бычье поглощение")
        }

        isBullish(previous) && isBearish(current) &&
            isGreater(candleOpen(current), candleClose(previous)) &&
            isLess(candleClose(current), candleOpen(previous)) -> {
            PatternResult(PatternType.BEARISH_ENGULFING, OrderDirection.SELL, 0.85, "Медвежье поглощение")
        }

        else -> null
    }

    private fun detectHammerAndRelated(
        candle: HistoricCandle,
        candles: List<HistoricCandle>
    ): PatternResult? {
        val body = candleBody(candle)
        val range = candleRange(candle)
        if (!isPositive(range) || isLess(body, range.multiply(MIN_BODY_TO_RANGE_RATIO))) return null

        val upperShadow = candleHigh(candle).subtract(maxPrice(candleOpen(candle), candleClose(candle)))
        val lowerShadow = minPrice(candleOpen(candle), candleClose(candle)).subtract(candleLow(candle))
        val hammer = isGreaterOrEqual(lowerShadow, body.multiply(TWO)) &&
            isLessOrEqual(upperShadow, body.multiply(SHADOW_RATIO))
        val shootingStar = isGreaterOrEqual(upperShadow, body.multiply(TWO)) &&
            isLessOrEqual(lowerShadow, body.multiply(SHADOW_RATIO))
        val trend = detectTrend(candles.dropLast(1), MIN_TREND_CANDLES)

        return when {
            hammer && trend == OrderDirection.SELL ->
                PatternResult(PatternType.HAMMER, OrderDirection.BUY, 0.75, "Молот")
            hammer && trend == OrderDirection.BUY ->
                PatternResult(PatternType.HANGING_MAN, OrderDirection.SELL, 0.70, "Повешенный")
            shootingStar && trend == OrderDirection.BUY ->
                PatternResult(PatternType.SHOOTING_STAR, OrderDirection.SELL, 0.75, "Падающая звезда")
            else -> null
        }
    }

    private fun detectDoji(candle: HistoricCandle, candles: List<HistoricCandle>): PatternResult? {
        if (candles.size < MIN_TREND_CANDLES || !isDoji(candle)) return null

        return when (detectTrend(candles.dropLast(1), MIN_TREND_CANDLES)) {
            OrderDirection.SELL -> PatternResult(PatternType.DOJI, OrderDirection.BUY, 0.60, "Доджи после снижения")
            OrderDirection.BUY -> PatternResult(PatternType.DOJI, OrderDirection.SELL, 0.60, "Доджи после роста")
            OrderDirection.HOLD -> PatternResult(PatternType.DOJI, OrderDirection.HOLD, 0.50, "Доджи без тренда")
        }
    }

    private fun isDoji(candle: HistoricCandle): Boolean {
        val range = candleRange(candle)
        return isPositive(range) && isLessOrEqual(divide(candleBody(candle), range) ?: return false, DOJI_BODY_RATIO)
    }

    private fun detectStar(candles: List<HistoricCandle>): PatternResult? {
        val window = candles.windowed(size = 3, step = 1, partialWindows = false).lastOrNull() ?: return null
        val first = window[0]
        val second = window[1]
        val third = window[2]
        val firstBody = candleBody(first)
        val secondBody = candleBody(second)
        val midpoint = candleOpen(first).add(candleClose(first)).divide(TWO, PRICE_SCALE, ROUNDING)

        return when {
            isBearish(first) && isLess(secondBody, firstBody.multiply(HALF)) && isBullish(third) &&
                isGreater(candleClose(third), midpoint) ->
                PatternResult(PatternType.MORNING_STAR, OrderDirection.BUY, 0.80, "Утренняя звезда")

            isBullish(first) && isLess(secondBody, firstBody.multiply(HALF)) && isBearish(third) &&
                isLess(candleClose(third), midpoint) ->
                PatternResult(PatternType.EVENING_STAR, OrderDirection.SELL, 0.80, "Вечерняя звезда")

            else -> null
        }
    }

    private fun detectPiercingAndDarkCloud(previous: HistoricCandle, current: HistoricCandle): PatternResult? {
        if (hasExcessiveGap(candleOpen(current), candleClose(previous))) return null

        val midpoint = candleOpen(previous).add(candleClose(previous)).divide(TWO, PRICE_SCALE, ROUNDING)
        return when {
            isBearish(previous) && isBullish(current) &&
                isLess(candleOpen(current), candleClose(previous)) &&
                isGreater(candleClose(current), midpoint) ->
                PatternResult(PatternType.PIERCING_LINE, OrderDirection.BUY, 0.70, "Пробивающая линия")

            isBullish(previous) && isBearish(current) &&
                isGreater(candleOpen(current), candleClose(previous)) &&
                isLess(candleClose(current), midpoint) ->
                PatternResult(PatternType.DARK_CLOUD_COVER, OrderDirection.SELL, 0.70, "Тёмное облако")

            else -> null
        }
    }

    private fun detectThreeSoldiersOrCrows(candles: List<HistoricCandle>): PatternResult? {
        val window = candles.windowed(size = 3, step = 1, partialWindows = false).lastOrNull() ?: return null
        val first = window[0]
        val second = window[1]
        val third = window[2]

        val soldiers = listOf(first, second, third).all(::isBullish) &&
            isLess(candleClose(first), candleClose(second)) &&
            isLess(candleClose(second), candleClose(third)) &&
            isLess(candleBody(first), candleBody(second)) &&
            isLess(candleBody(second), candleBody(third))
        if (soldiers) {
            return PatternResult(PatternType.THREE_WHITE_SOLDIERS, OrderDirection.BUY, 0.85, "Три белых солдата")
        }

        val crows = listOf(first, second, third).all(::isBearish) &&
            isGreater(candleClose(first), candleClose(second)) &&
            isGreater(candleClose(second), candleClose(third))
        return if (crows) {
            PatternResult(PatternType.THREE_BLACK_CROWS, OrderDirection.SELL, 0.85, "Три чёрных ворона")
        } else {
            null
        }
    }

    private fun detectMarubozu(candle: HistoricCandle): PatternResult? {
        val body = candleBody(candle)
        if (!isPositive(body)) return null

        val upperShadow = candleHigh(candle).subtract(maxPrice(candleOpen(candle), candleClose(candle)))
        val lowerShadow = minPrice(candleOpen(candle), candleClose(candle)).subtract(candleLow(candle))
        val isMarubozu = isLessOrEqual(upperShadow, body.multiply(MARUBOZU_SHADOW_RATIO)) &&
            isLessOrEqual(lowerShadow, body.multiply(MARUBOZU_SHADOW_RATIO))
        if (!isMarubozu) return null

        return if (isBullish(candle)) {
            PatternResult(PatternType.MARUBOZU, OrderDirection.BUY, 0.65, "Белое марубозу")
        } else {
            PatternResult(PatternType.MARUBOZU, OrderDirection.SELL, 0.65, "Чёрное марубозу")
        }
    }

    private fun detectSpinningTop(candle: HistoricCandle): PatternResult? {
        val range = candleRange(candle)
        if (!isPositive(range)) return null

        val bodyRatio = divide(candleBody(candle), range) ?: return null
        val upperShadow = candleHigh(candle).subtract(maxPrice(candleOpen(candle), candleClose(candle)))
        val lowerShadow = minPrice(candleOpen(candle), candleClose(candle)).subtract(candleLow(candle))
        val upperRatio = divide(upperShadow, range) ?: return null
        val lowerRatio = divide(lowerShadow, range) ?: return null

        return if (
            isLessOrEqual(bodyRatio, SPINNING_TOP_BODY_RATIO) &&
            isBetween(upperRatio, SPINNING_TOP_MIN_SHADOW_RATIO, SPINNING_TOP_MAX_SHADOW_RATIO) &&
            isBetween(lowerRatio, SPINNING_TOP_MIN_SHADOW_RATIO, SPINNING_TOP_MAX_SHADOW_RATIO)
        ) {
            PatternResult(PatternType.SPINNING_TOP, OrderDirection.HOLD, 0.30, "Волчок")
        } else {
            null
        }
    }

    private fun scorePattern(pattern: PatternResult, candles: List<HistoricCandle>): PatternResult {
        if (pattern.pattern == null || pattern.direction == OrderDirection.HOLD) return pattern

        val filters = calculateFilters(candles)
        val expectedTrend = expectedTrend(pattern.pattern, pattern.direction)
        val trendMatch = filters.globalTrend == expectedTrend
        val rsiExtreme = isRsiExtreme(filters.rsi, pattern.direction)
        val confidence = pattern.confidence +
            (if (trendMatch) TREND_MATCH_BONUS else TREND_MISMATCH_PENALTY) +
            (if (rsiExtreme) RSI_EXTREME_BONUS else NO_RSI_ADJUSTMENT) +
            (if (filters.volumeSpike) VOLUME_SPIKE_BONUS else LOW_VOLUME_PENALTY)

        return pattern.copy(confidence = confidence.coerceIn(0.0, 1.0))
    }

    private fun calculateFilters(candles: List<HistoricCandle>): MarketFilters {
        val closes = candles.map(::candleClose)
        val ema200 = calculateEma(closes, EMA_PERIOD)
        val currentClose = closes.lastOrNull()
        val globalTrend = when {
            ema200 == null || currentClose == null -> OrderDirection.HOLD
            isGreater(currentClose, ema200) -> OrderDirection.BUY
            isLess(currentClose, ema200) -> OrderDirection.SELL
            else -> OrderDirection.HOLD
        }

        return MarketFilters(
            globalTrend = globalTrend,
            rsi = calculateRsi(closes, RSI_PERIOD),
            volumeSpike = hasVolumeSpike(candles)
        )
    }

    private fun calculateEma(prices: List<BigDecimal>, period: Int): BigDecimal? {
        if (period <= 0 || prices.size < period) return null

        val multiplier = TWO.divide(BigDecimal.valueOf((period + 1).toLong()), PRICE_SCALE, ROUNDING)
        var ema = prices.take(period).reduce(BigDecimal::add)
            .divide(BigDecimal.valueOf(period.toLong()), PRICE_SCALE, ROUNDING)
        prices.drop(period).forEach { price ->
            ema = price.multiply(multiplier).add(ema.multiply(ONE.subtract(multiplier)))
        }
        return ema
    }

    private fun calculateRsi(prices: List<BigDecimal>, period: Int): BigDecimal? {
        if (period <= 0 || prices.size < period + 1) return null

        var gains = ZERO
        var losses = ZERO
        prices.takeLast(period + 1).windowed(2, 1, false).forEach { (previous, current) ->
            val change = current.subtract(previous)
            when {
                isPositive(change) -> gains = gains.add(change)
                isNegative(change) -> losses = losses.add(change.abs())
            }
        }
        if (losses.compareTo(ZERO) == 0) return RSI_MAX

        val averageGain = gains.divide(BigDecimal.valueOf(period.toLong()), PRICE_SCALE, ROUNDING)
        val averageLoss = losses.divide(BigDecimal.valueOf(period.toLong()), PRICE_SCALE, ROUNDING)
        val relativeStrength = averageGain.divide(averageLoss, PRICE_SCALE, ROUNDING)
        return RSI_MAX.subtract(RSI_MAX.divide(ONE.add(relativeStrength), PRICE_SCALE, ROUNDING))
    }

    private fun hasVolumeSpike(candles: List<HistoricCandle>): Boolean {
        if (candles.size < VOLUME_SMA_PERIOD + 1) return false

        val currentVolume = candles.last().volume
        if (currentVolume <= 0L) return false
        val averageVolume = candles.dropLast(1).takeLast(VOLUME_SMA_PERIOD)
            .map(HistoricCandle::getVolume)
            .filter { it > 0L }
            .takeIf { it.size == VOLUME_SMA_PERIOD }
            ?.map(BigDecimal::valueOf)
            ?.reduce(BigDecimal::add)
            ?.divide(BigDecimal.valueOf(VOLUME_SMA_PERIOD.toLong()), PRICE_SCALE, ROUNDING)
            ?: return false

        return isGreaterOrEqual(BigDecimal.valueOf(currentVolume), averageVolume.multiply(VOLUME_MULTIPLIER))
    }

    private fun detectTrend(candles: List<HistoricCandle>, period: Int): OrderDirection {
        if (period <= 0 || candles.size < period) return OrderDirection.HOLD

        val closes = candles.takeLast(period).map(::candleClose)
        val average = closes.reduce(BigDecimal::add)
            .divide(BigDecimal.valueOf(period.toLong()), PRICE_SCALE, ROUNDING)
        return when {
            isGreater(closes.last(), average) -> OrderDirection.BUY
            isLess(closes.last(), average) -> OrderDirection.SELL
            else -> OrderDirection.HOLD
        }
    }

    private fun isConfirmedByPrice(
        pattern: PatternResult,
        candle: HistoricCandle,
        currentPrice: BigDecimal?
    ): Boolean {
        if (currentPrice == null) return true
        return when (pattern.direction) {
            OrderDirection.BUY -> isGreater(currentPrice, candleHigh(candle))
            OrderDirection.SELL -> isLess(currentPrice, candleLow(candle))
            OrderDirection.HOLD -> true
        }
    }

    private fun registerSignalExpiry(instrumentUid: String, key: String, candle: HistoricCandle) {
        activeSignalKeys[instrumentUid] = key
        signalExpiryJobs.remove(instrumentUid)?.cancel()
        val candleCloseTime = candleStart(candle).plusSeconds(currentTimeframe.minutes.toLong() * SECONDS_IN_MINUTE)
        val expiresAt = candleCloseTime.plusSeconds(currentTimeframe.minutes.toLong() * SIGNAL_VALIDITY_CANDLES * SECONDS_IN_MINUTE)
        val delayMs = Duration.between(Instant.now(), expiresAt).toMillis().coerceAtLeast(0L)
        signalExpiryJobs[instrumentUid] = strategyScope.launch {
            delay(delayMs)
            activeSignalKeys.remove(instrumentUid, key)
            signalExpiryJobs.remove(instrumentUid)
        }
    }

    private fun isSignalActive(instrumentUid: String, candleKey: String?): Boolean =
        candleKey != null && activeSignalKeys[instrumentUid] == candleKey

    private fun logPatternOnce(instrumentUid: String, result: PatternResult) {
        val key = result.candleKey ?: return
        if (loggedPatternKeys.put(instrumentUid, key) != key) {
            logger.info { "Обнаружен паттерн ${result.description}, уверенность=${result.confidence}" }
        }
    }

    private fun hasExcessiveGap(open: BigDecimal, previousClose: BigDecimal): Boolean {
        if (!isPositive(previousClose)) return true
        val gapRatio = divide(open.subtract(previousClose).abs(), previousClose) ?: return true
        return isGreater(gapRatio, MAX_ALLOWED_GAP_RATIO)
    }

    private fun isRsiExtreme(rsi: BigDecimal?, direction: OrderDirection): Boolean = when (direction) {
        OrderDirection.BUY -> rsi != null && isLessOrEqual(rsi, RSI_OVERSOLD)
        OrderDirection.SELL -> rsi != null && isGreaterOrEqual(rsi, RSI_OVERBOUGHT)
        OrderDirection.HOLD -> false
    }

    private fun expectedTrend(pattern: PatternType, direction: OrderDirection): OrderDirection =
        if (pattern in CONTINUATION_PATTERNS) direction else opposite(direction)

    private fun pendingConfirmationResult(pattern: PatternResult): PatternResult = pattern.copy(
        pattern = null,
        direction = OrderDirection.HOLD,
        description = "Паттерн ожидает подтверждения ценой"
    )

    private fun belowConfidenceResult(pattern: PatternResult): PatternResult = pattern.copy(
        pattern = null,
        direction = OrderDirection.HOLD,
        description = "Уверенность паттерна ниже порога входа"
    )

    private fun insufficientCandlesResult(): PatternResult =
        PatternResult(null, OrderDirection.HOLD, 0.0, "Недостаточно закрытых свечей")

    private fun noPatternResult(): PatternResult = PatternResult(null, OrderDirection.HOLD, 0.0, "Нет паттерна")

    override fun getExplanation(data: MarketData): String {
        val pattern = data.candlestickPattern ?: return "Свечной паттерн не обнаружен"
        return "Паттерн: ${pattern.description}; сигнал: ${pattern.direction}; уверенность: ${pattern.confidence}"
    }

    private fun isClosed(candle: HistoricCandle, now: Instant, intervalSeconds: Long): Boolean =
        candle.isComplete && !candleStart(candle).plusSeconds(intervalSeconds).isAfter(now)

    private fun candleStart(candle: HistoricCandle): Instant =
        Instant.ofEpochSecond(candle.time.seconds, candle.time.nanos.toLong())

    private fun candleKey(candle: HistoricCandle): String =
        "${currentTimeframe.name}:${candle.time.seconds}:${candle.time.nanos}"

    private fun candleOpen(candle: HistoricCandle): BigDecimal = quotation(candle.open.units, candle.open.nano)

    private fun candleClose(candle: HistoricCandle): BigDecimal = quotation(candle.close.units, candle.close.nano)

    private fun candleHigh(candle: HistoricCandle): BigDecimal = quotation(candle.high.units, candle.high.nano)

    private fun candleLow(candle: HistoricCandle): BigDecimal = quotation(candle.low.units, candle.low.nano)

    private fun quotation(units: Long, nano: Int): BigDecimal = BigDecimal.valueOf(units)
        .add(BigDecimal.valueOf(nano.toLong(), NANO_SCALE))

    private fun candleBody(candle: HistoricCandle): BigDecimal = candleOpen(candle).subtract(candleClose(candle)).abs()

    private fun candleRange(candle: HistoricCandle): BigDecimal = candleHigh(candle).subtract(candleLow(candle))

    private fun isBullish(candle: HistoricCandle): Boolean = isGreater(candleClose(candle), candleOpen(candle))

    private fun isBearish(candle: HistoricCandle): Boolean = isLess(candleClose(candle), candleOpen(candle))

    private fun isPositive(value: BigDecimal): Boolean = value.compareTo(ZERO) > 0

    private fun isNegative(value: BigDecimal): Boolean = value.compareTo(ZERO) < 0

    private fun isGreater(left: BigDecimal, right: BigDecimal): Boolean = left.compareTo(right) > 0

    private fun isGreaterOrEqual(left: BigDecimal, right: BigDecimal): Boolean = left.compareTo(right) >= 0

    private fun isLess(left: BigDecimal, right: BigDecimal): Boolean = left.compareTo(right) < 0

    private fun isLessOrEqual(left: BigDecimal, right: BigDecimal): Boolean = left.compareTo(right) <= 0

    private fun isBetween(value: BigDecimal, from: BigDecimal, to: BigDecimal): Boolean =
        isGreaterOrEqual(value, from) && isLessOrEqual(value, to)

    private fun divide(dividend: BigDecimal, divisor: BigDecimal): BigDecimal? =
        divisor.takeIf(::isPositive)?.let { dividend.divide(it, PRICE_SCALE, ROUNDING) }

    private fun maxPrice(first: BigDecimal, second: BigDecimal): BigDecimal =
        if (isGreater(first, second)) first else second

    private fun minPrice(first: BigDecimal, second: BigDecimal): BigDecimal =
        if (isLess(first, second)) first else second

    private fun opposite(direction: OrderDirection): OrderDirection = when (direction) {
        OrderDirection.BUY -> OrderDirection.SELL
        OrderDirection.SELL -> OrderDirection.BUY
        OrderDirection.HOLD -> OrderDirection.HOLD
    }

    private companion object {
        const val DEFAULT_LOOKBACK_CANDLES = 30
        const val INDICATOR_BUFFER_CANDLES = 20
        const val EMA_PERIOD = 200
        const val RSI_PERIOD = 14
        const val VOLUME_SMA_PERIOD = 20
        const val MIN_TREND_CANDLES = 5
        const val MIN_REQUIRED_CANDLES = 3
        const val SIGNAL_VALIDITY_CANDLES = 2
        const val CANDLE_CACHE_TTL_SECONDS = 5L
        const val SECONDS_IN_MINUTE = 60L
        const val PRICE_SCALE = 12
        const val NANO_SCALE = 9
        const val MIN_ENTRY_CONFIDENCE = 0.85

        val ZERO = BigDecimal.ZERO
        val ONE = BigDecimal.ONE
        val TWO = BigDecimal("2")
        val HALF = BigDecimal("0.5")
        val MIN_BODY_TO_RANGE_RATIO = BigDecimal("0.05")
        val DOJI_BODY_RATIO = BigDecimal("0.1")
        val SHADOW_RATIO = BigDecimal("0.3")
        val MARUBOZU_SHADOW_RATIO = BigDecimal("0.05")
        val SPINNING_TOP_BODY_RATIO = BigDecimal("0.2")
        val SPINNING_TOP_MIN_SHADOW_RATIO = BigDecimal("0.3")
        val SPINNING_TOP_MAX_SHADOW_RATIO = BigDecimal("0.7")
        val MAX_ALLOWED_GAP_RATIO = BigDecimal("0.003")
        val VOLUME_MULTIPLIER = BigDecimal("1.5")
        val RSI_MAX = BigDecimal("100")
        val RSI_OVERSOLD = BigDecimal("30")
        val RSI_OVERBOUGHT = BigDecimal("70")
        val ROUNDING = RoundingMode.HALF_UP
        val CONTINUATION_PATTERNS = setOf(
            PatternType.THREE_WHITE_SOLDIERS,
            PatternType.THREE_BLACK_CROWS,
            PatternType.MARUBOZU
        )

        const val TREND_MATCH_BONUS = 0.10
        const val TREND_MISMATCH_PENALTY = -0.30
        const val RSI_EXTREME_BONUS = 0.10
        const val NO_RSI_ADJUSTMENT = 0.0
        const val VOLUME_SPIKE_BONUS = 0.10
        const val LOW_VOLUME_PENALTY = -0.10
    }
}
