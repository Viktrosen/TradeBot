package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.bolotov.tradebot.broker.getCandlesSync
import ru.bolotov.tradebot.broker.getInstrumentByUIDSync
import ru.bolotov.tradebot.broker.getLastPricesSync
import ru.bolotov.tradebot.broker.getShareByUidSync
import ru.bolotov.tradebot.broker.toBigDecimal
import ru.bolotov.tradebot.strategy.data.InstrumentInfo
import ru.ttech.piapi.core.MarketDataServiceSync
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import ru.ttech.piapi.core.InstrumentsServiceSync
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class MarketDataProvider(
    private val marketDataService: MarketDataServiceSync,
    private val instrumentsService: InstrumentsServiceSync
) {
    private val instrumentCache = ConcurrentHashMap<String, InstrumentInfo>()
    private val candleHistoryCache = ConcurrentHashMap<String, CandleHistoryCacheEntry>()
    private val candleHistoryLocks = ConcurrentHashMap<String, Any>()
    private val lastFailureLogAt = ConcurrentHashMap<String, Instant>()
    private val calculationContext = MathContext.DECIMAL64

    /**
     * Собирает единый снимок рынка по инструменту только из закрытых M5-свечей.
     *
     * Снимок используют и стратегии, и определитель режима рынка, поэтому
     * EMA(50)/EMA(200) рассчитываются из расширенного исторического окна.
     *
     * Используется вне ценового стрима, когда свежая цена ещё не передана
     * вызывающим кодом. Обработчик стрима должен использовать перегрузку с
     * [currentPrice], чтобы не делать повторный запрос к T-Invest API.
     */
    suspend fun fetchMarketData(instrumentUid: String): MarketData? {
        return try {
            val lastPrice = marketDataService.getLastPricesSync(listOf(instrumentUid)).firstOrNull()
                ?: throw IllegalStateException("Нет данных о последней цене для $instrumentUid")
            fetchMarketData(instrumentUid, lastPrice.price.toBigDecimal())
        } catch (e: Exception) {
            logMarketDataFailure(instrumentUid, e)
            null
        }
    }

    /**
     * Собирает рыночный снимок по цене из `LastPrice` стрима.
     *
     * Тиковая цена сохраняет оперативность защитных проверок, а история и
     * индикаторы берутся из кеша закрытых M5-свечей.
     */
    suspend fun fetchMarketData(instrumentUid: String, currentPrice: BigDecimal): MarketData? {
        return try {
            val instrumentInfo = getInstrumentInfo(instrumentUid)
            val displayName = instrumentInfo?.ticker ?: instrumentUid.take(8)
            logger.debug { "Обогащаем потоковую цену для $instrumentUid ($displayName)" }

            val now = Instant.now()
            val history = getCandleHistory(instrumentUid, displayName, now)
            val indicators = history.indicators
            val bollingerBands = calculateBollingerBands(indicators.closes, currentPrice)

            lastFailureLogAt.remove(instrumentUid)

            MarketData(
                instrumentId = instrumentUid,
                instrumentName = displayName,
                currentPrice = currentPrice,
                lotSize = instrumentInfo?.lotSize ?: 1,
                ema5 = indicators.ema5,
                ema21 = indicators.ema21,
                ema50 = indicators.ema50,
                ema200 = indicators.ema200,
                previousEma5 = indicators.previousEma5,
                previousEma21 = indicators.previousEma21,
                rsi = indicators.rsi,
                macd = indicators.macd,
                bollingerBands = bollingerBands,
                atr = indicators.atr,
                volume = indicators.currentVolume,
                avgVolume = indicators.avgVolume,
                spread = BigDecimal.valueOf(0.1),
                volatility = indicators.volatility,
                strategyCandleKey = indicators.strategyCandleKey,
                // НОВОЕ: для Choppiness Index и ADX
                high14 = indicators.high14,
                low14 = indicators.low14,
                adx = indicators.adx,
                vwap = indicators.vwap
            )
        } catch (e: Exception) {
            logMarketDataFailure(instrumentUid, e)
            null
        }
    }

    /**
     * Возвращает историю закрытых M5-свечей из кеша. Новая история запрашивается
     * только при первом обращении, после закрытия следующей M5-свечи или после TTL.
     *
     * Кеш привязан к instrumentUid, поэтому смена набора отслеживаемых инструментов
     * не сбрасывает уже прогретые данные. Блокировка на один инструмент исключает
     * параллельные одинаковые запросы при серии ценовых событий.
     */
    private fun getCandleHistory(
        instrumentUid: String,
        displayName: String,
        now: Instant
    ): CandleHistoryCacheEntry {
        val lock = candleHistoryLocks.computeIfAbsent(instrumentUid) { Any() }
        return synchronized(lock) {
            val cached = candleHistoryCache[instrumentUid]
            when {
                cached == null || isExpired(cached, now) -> loadFullCandleHistory(instrumentUid, displayName, now)
                now >= cached.refreshAfter -> refreshCandleHistory(instrumentUid, displayName, cached, now)
                else -> cached
            }.also { refreshed -> candleHistoryCache[instrumentUid] = refreshed }
        }
    }

    private fun loadFullCandleHistory(
        instrumentUid: String,
        displayName: String,
        now: Instant
    ): CandleHistoryCacheEntry {
        val historicalFrom = CandleHistoryWindow.earliestAllowedFrom(now, M5_INTERVAL)
        val candles = loadClosedCandles(instrumentUid, historicalFrom, now)
        logger.info { "$displayName: история M5 прогрета, закрытых свечей=${candles.size}" }
        return createCandleHistoryEntry(candles, now)
    }

    private fun refreshCandleHistory(
        instrumentUid: String,
        displayName: String,
        cached: CandleHistoryCacheEntry,
        now: Instant
    ): CandleHistoryCacheEntry {
        val lastCandleStart = cached.candles.lastOrNull()?.let(::candleStart)
            ?: return loadFullCandleHistory(instrumentUid, displayName, now)
        val newCandles = loadClosedCandles(instrumentUid, lastCandleStart.minusSeconds(M5_INTERVAL_SECONDS), now)
        val mergedCandles = trimHistoryToAllowedWindow(cached.candles + newCandles, now)
        if (mergedCandles == cached.candles) {
            return cached.copy(refreshAfter = nextCandleRefreshAt(now))
        }

        val knownCandleKeys = cached.candles.mapTo(mutableSetOf(), ::strategyCandleKey)
        val addedCandles = mergedCandles.count { strategyCandleKey(it) !in knownCandleKeys }
        logger.info {
            "$displayName: история M5 обновлена, добавлено закрытых свечей=$addedCandles"
        }
        return createCandleHistoryEntry(mergedCandles, now)
    }

    private fun loadClosedCandles(instrumentUid: String, from: Instant, now: Instant): List<HistoricCandle> =
        marketDataService.getCandlesSync(instrumentUid, from, now, M5_INTERVAL)
            .filter { candle -> isClosed(candle, now, M5_INTERVAL_SECONDS) }
            .sortedBy(::candleStart)

    private fun trimHistoryToAllowedWindow(candles: List<HistoricCandle>, now: Instant): List<HistoricCandle> {
        val earliestAllowed = CandleHistoryWindow.earliestAllowedFrom(now, M5_INTERVAL)
        return candles
            .asSequence()
            .filter { candleStart(it) >= earliestAllowed }
            .associateBy(::strategyCandleKey)
            .values
            .sortedBy(::candleStart)
    }

    private fun createCandleHistoryEntry(candles: List<HistoricCandle>, now: Instant): CandleHistoryCacheEntry =
        CandleHistoryCacheEntry(
            candles = candles,
            indicators = calculateCandleIndicators(candles),
            loadedAt = now,
            refreshAfter = nextCandleRefreshAt(now)
        )

    /** Рассчитывается только при изменении списка закрытых свечей. */
    private fun calculateCandleIndicators(candles: List<HistoricCandle>): CandleIndicators {
        val closes = candles.map { it.close.toBigDecimal() }
        val volumes = candles.map { it.volume }
        val ema5Series = calculateEMASeries(closes, 5)
        val ema21Series = calculateEMASeries(closes, 21)
        val ema50Series = calculateEMASeries(closes, 50)
        val ema200Series = calculateEMASeries(closes, 200)

        // НОВОЕ: high14, low14 для Choppiness Index
        val high14 = candles.takeLast(14).maxOfOrNull { it.high.toBigDecimal() }
        val low14 = candles.takeLast(14).minOfOrNull { it.low.toBigDecimal() }

        // НОВОЕ: ADX для определения силы тренда
        val adx = calculateADX(candles, 14)

        return CandleIndicators(
            closes = closes,
            ema5 = ema5Series?.lastOrNull(),
            ema21 = ema21Series?.lastOrNull(),
            ema50 = ema50Series?.lastOrNull(),
            ema200 = ema200Series?.lastOrNull(),
            previousEma5 = ema5Series?.dropLast(1)?.lastOrNull(),
            previousEma21 = ema21Series?.dropLast(1)?.lastOrNull(),
            rsi = calculateRSI(closes, 14),
            macd = calculateMACD(closes),
            atr = calculateATR(candles),
            currentVolume = candles.lastOrNull()?.volume ?: 0L,
            avgVolume = if (volumes.isNotEmpty()) volumes.average().toLong() else 0L,
            volatility = calculateVolatility(closes),
            strategyCandleKey = candles.lastOrNull()?.let(::strategyCandleKey),
            // НОВОЕ
            high14 = high14,
            low14 = low14,
            adx = adx,
            vwap = calculateVwap(candles)
        )
    }

    /** VWAP по закрытым M5-свечам: типичная цена (H+L+C)/3, взвешенная объёмом. */
    private fun calculateVwap(candles: List<HistoricCandle>, period: Int = 20): BigDecimal? {
        val window = candles.takeLast(period).filter { it.volume > 0 }
        val totalVolume = window.sumOf(HistoricCandle::getVolume)
        if (totalVolume == 0L) return null

        val weightedPrice = window.fold(BigDecimal.ZERO) { total, candle ->
            val typicalPrice = (candle.high.toBigDecimal() + candle.low.toBigDecimal() + candle.close.toBigDecimal())
                .divide(BigDecimal(3), calculationContext)
            total + typicalPrice * candle.volume.toBigDecimal()
        }
        return weightedPrice.divide(totalVolume.toBigDecimal(), calculationContext)
    }

    private fun isExpired(entry: CandleHistoryCacheEntry, now: Instant): Boolean =
        now >= entry.loadedAt.plusSeconds(CANDLE_CACHE_TTL_SECONDS)

    private fun nextCandleRefreshAt(now: Instant): Instant {
        val currentEpochSecond = now.epochSecond
        val nextBoundary = (currentEpochSecond / M5_INTERVAL_SECONDS + 1) * M5_INTERVAL_SECONDS
        return Instant.ofEpochSecond(nextBoundary + CANDLE_CLOSE_GRACE_SECONDS)
    }

    fun getCurrentPrices(instrumentUids: List<String>): Map<String, BigDecimal> {
        if (instrumentUids.isEmpty()) return emptyMap()

        return try {
            marketDataService
                .getLastPricesSync(instrumentUids.distinct())
                .associate { lastPrice ->
                    lastPrice.instrumentUid to lastPrice.price.toBigDecimal()
                }
        } catch (error: Exception) {
            logger.error(error) { "Не удалось получить текущие цены открытых позиций" }
            emptyMap()
        }
    }

    private fun calculateATR(candles: List<HistoricCandle>, period: Int = 14): BigDecimal? {
        if (period <= 0) return null
        if (candles.size < period + 1) return null

        val trueRanges = mutableListOf<BigDecimal>()

        for (i in 1 until candles.size) {
            val high = candles[i].high.toBigDecimal()
            val low = candles[i].low.toBigDecimal()
            val prevClose = candles[i - 1].close.toBigDecimal()

            val tr1 = high - low
            val tr2 = (high - prevClose).let { if (it < BigDecimal.ZERO) -it else it }
            val tr3 = (low - prevClose).let { if (it < BigDecimal.ZERO) -it else it }

            val trueRange = listOf(tr1, tr2, tr3).maxOrNull() ?: BigDecimal.ZERO
            trueRanges.add(trueRange)
        }

        if (trueRanges.size < period) return null

        var atr = trueRanges.take(period).reduce { acc, tr -> acc + tr } / BigDecimal(period)

        val multiplier = BigDecimal.valueOf(2)
            .divide(BigDecimal.valueOf((period + 1).toLong()), calculationContext)
        for (i in period until trueRanges.size) {
            atr = trueRanges[i].multiply(multiplier, calculationContext)
                .add(atr.multiply(BigDecimal.ONE.subtract(multiplier), calculationContext), calculationContext)
        }

        return atr
    }

    suspend fun getInstrumentInfo(instrumentUid: String): InstrumentInfo? {
        instrumentCache[instrumentUid]?.let { return it }

        return try {
            val response = instrumentsService.getInstrumentByUIDSync(instrumentUid)
            val instrument = response.instrument
            val info = InstrumentInfo(
                ticker = instrument.ticker,
                name = instrument.name,
                lotSize = instrument.lot
            )
            instrumentCache[instrumentUid] = info
            info
        } catch (e: Exception) {
            try {
                val share = instrumentsService.getShareByUidSync(instrumentUid).instrument
                val info = InstrumentInfo(
                    ticker = share.ticker,
                    name = share.name,
                    lotSize = share.lot
                )
                instrumentCache[instrumentUid] = info
                info
            } catch (e2: Exception) {
                logger.warn { "Не удалось получить информацию об инструменте $instrumentUid" }
                null
            }
        }
    }

    private fun calculateEMASeries(prices: List<BigDecimal>, period: Int): List<BigDecimal>? {
        if (prices.size < period) return null

        val multiplier = BigDecimal.valueOf(2)
            .divide(BigDecimal.valueOf((period + 1).toLong()), calculationContext)
        var ema = prices.take(period)
            .reduce(BigDecimal::add)
            .divide(BigDecimal.valueOf(period.toLong()), calculationContext)
        val values = mutableListOf(ema)

        for (i in period until prices.size) {
            ema = prices[i].multiply(multiplier, calculationContext)
                .add(ema.multiply(BigDecimal.ONE.subtract(multiplier), calculationContext), calculationContext)
            values += ema
        }
        return values
    }

    private fun calculateRSI(prices: List<BigDecimal>, period: Int): Double? {
        if (prices.size < period + 1) return null
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val change = prices[i] - prices[i - 1]
            if (change > BigDecimal.ZERO) avgGain += change.toDouble()
            else avgLoss -= change.toDouble()
        }
        avgGain /= period
        avgLoss /= period
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }

    private fun calculateMACD(prices: List<BigDecimal>): MacdData? {
        if (prices.size < 34) return null
        val ema12 = calculateEMASeries(prices, 12) ?: return null
        val ema26 = calculateEMASeries(prices, 26) ?: return null
        val macdSeries = ema12.drop(26 - 12).zip(ema26) { short, long ->
            short.subtract(long, calculationContext)
        }
        val signalLine = calculateEMASeries(macdSeries, 9)?.lastOrNull() ?: return null
        val macdLine = macdSeries.last()
        return MacdData(
            macdLine = macdLine,
            signalLine = signalLine,
            histogram = macdLine - signalLine,
            isPositive = macdLine > BigDecimal.ZERO
        )
    }

    private fun calculateBollingerBands(prices: List<BigDecimal>, currentPrice: BigDecimal): BollingerBandsData? {
        if (prices.size < 20) return null
        val period = 20
        val last20Prices = prices.takeLast(period)
        val sma20 = last20Prices.reduce { acc, price -> acc + price } / BigDecimal(period)
        val variance = last20Prices.map { price ->
            val diff = price - sma20
            diff * diff
        }.reduce { acc, diff -> acc + diff }.toDouble() / period
        val stdDev = BigDecimal(Math.sqrt(variance))
        val multiplier = BigDecimal.valueOf(2)
        val upperBand = sma20 + stdDev * multiplier
        val lowerBand = sma20 - stdDev * multiplier
        val bandwidth = if (sma20 > BigDecimal.ZERO) {
            (upperBand - lowerBand) / sma20 * BigDecimal(100)
        } else {
            BigDecimal.ZERO
        }
        val bandRange = upperBand - lowerBand
        val percentB = if (bandRange > BigDecimal.ZERO) {
            ((currentPrice - lowerBand) / bandRange).toDouble().coerceIn(0.0, 1.0)
        } else {
            0.5
        }
        return BollingerBandsData(
            upperBand = upperBand,
            middleBand = sma20,
            lowerBand = lowerBand,
            bandwidth = bandwidth,
            percentB = percentB
        )
    }

    private fun calculateVolatility(prices: List<BigDecimal>): Double {
        if (prices.size < 2) return 2.5
        val returns = mutableListOf<Double>()
        for (i in 1 until prices.size) {
            val dailyReturn = (prices[i] - prices[i - 1]) / prices[i - 1]
            returns.add(dailyReturn.toDouble())
        }
        val mean = returns.average()
        val variance = returns.map { (it - mean) * (it - mean) }.average()
        val stdDev = Math.sqrt(variance)
        return stdDev * Math.sqrt(252.0) * 100
    }

    // НОВОЕ: расчёт ADX (Average Directional Index)
    private fun calculateADX(candles: List<HistoricCandle>, period: Int = 14): Double? {
        if (candles.size < period * 2 + 1) return null

        // Рассчитываем +DM, -DM, TR
        val directionalMovements = mutableListOf<DirectionalMovement>()
        val trueRanges = mutableListOf<BigDecimal>()

        for (i in 1 until candles.size) {
            val high = candles[i].high.toBigDecimal()
            val low = candles[i].low.toBigDecimal()
            val prevHigh = candles[i - 1].high.toBigDecimal()
            val prevLow = candles[i - 1].low.toBigDecimal()
            val prevClose = candles[i - 1].close.toBigDecimal()

            val plusDM = if (high - prevHigh > prevLow - low) {
                high - prevHigh
            } else {
                BigDecimal.ZERO
            }

            val minusDM = if (prevLow - low > high - prevHigh) {
                prevLow - low
            } else {
                BigDecimal.ZERO
            }

            val tr1 = high - low
            val tr2 = (high - prevClose).let { if (it < BigDecimal.ZERO) -it else it }
            val tr3 = (low - prevClose).let { if (it < BigDecimal.ZERO) -it else it }
            val trueRange = listOf(tr1, tr2, tr3).maxOrNull() ?: BigDecimal.ZERO

            directionalMovements.add(DirectionalMovement(plusDM, minusDM))
            trueRanges.add(trueRange)
        }

        if (directionalMovements.size < period || trueRanges.size < period) return null

        // Wilder's smoothing для +DM, -DM, TR
        var sumPlusDM = directionalMovements.take(period).sumOf { it.plusDM }
        var sumMinusDM = directionalMovements.take(period).sumOf { it.minusDM }
        var sumTR = trueRanges.take(period).reduce { acc, tr -> acc + tr }
        val periodBD = BigDecimal.valueOf(period.toLong())

        for (i in period until directionalMovements.size) {
            sumPlusDM = sumPlusDM.subtract(sumPlusDM.divide(periodBD, 8, RoundingMode.HALF_UP)).add(directionalMovements[i].plusDM)
            sumMinusDM = sumMinusDM.subtract(sumMinusDM.divide(periodBD, 8, RoundingMode.HALF_UP)).add(directionalMovements[i].minusDM)
            sumTR = sumTR.subtract(sumTR.divide(periodBD, 8, RoundingMode.HALF_UP)).add(trueRanges[i])
        }

        if (sumTR.compareTo(BigDecimal.ZERO) == 0) return 0.0

        val hundredBD = BigDecimal.valueOf(100)
        val plusDI = sumPlusDM.divide(periodBD, 8, RoundingMode.HALF_UP).divide(sumTR.divide(periodBD, 8, RoundingMode.HALF_UP), 8, RoundingMode.HALF_UP).multiply(hundredBD)
        val minusDI = sumMinusDM.divide(periodBD, 8, RoundingMode.HALF_UP).divide(sumTR.divide(periodBD, 8, RoundingMode.HALF_UP), 8, RoundingMode.HALF_UP).multiply(hundredBD)

        val diSum = plusDI.add(minusDI)
        return if (diSum > BigDecimal.ZERO) {
            Math.abs(plusDI.toDouble() - minusDI.toDouble()) / diSum.toDouble() * 100
        } else 0.0
    }

    private data class DirectionalMovement(
        val plusDM: BigDecimal,
        val minusDM: BigDecimal
    )

    private fun isClosed(candle: HistoricCandle, now: Instant, intervalSeconds: Long): Boolean {
        return candle.isComplete && !candleStart(candle).plusSeconds(intervalSeconds).isAfter(now)
    }

    private fun candleStart(candle: HistoricCandle): Instant =
        Instant.ofEpochSecond(candle.time.seconds, candle.time.nanos.toLong())

    private fun strategyCandleKey(candle: HistoricCandle): String =
        "M5:${candle.time.seconds}:${candle.time.nanos}"

    private fun formatIndicator(value: BigDecimal?): String = value
        ?.setScale(4, RoundingMode.HALF_UP)
        ?.stripTrailingZeros()
        ?.toPlainString()
        ?: "нет данных"

    /** Не даёт одной недоступной котировке засорять журнал одинаковыми stack trace. */
    private fun logMarketDataFailure(instrumentUid: String, error: Exception) {
        val now = Instant.now()
        val previous = lastFailureLogAt[instrumentUid]
        if (previous != null && now.isBefore(previous.plusSeconds(FAILURE_LOG_INTERVAL_SECONDS))) return

        lastFailureLogAt[instrumentUid] = now
        logger.warn {
            "Не удалось получить рыночные данные для $instrumentUid: " +
                "${error.message ?: error.javaClass.simpleName}"
        }
    }

    private companion object {
        const val FAILURE_LOG_INTERVAL_SECONDS = 60L
        const val M5_INTERVAL_SECONDS = 5 * 60L
        const val CANDLE_CLOSE_GRACE_SECONDS = 5L
        const val CANDLE_CACHE_TTL_SECONDS = 30 * 60L
        val M5_INTERVAL: CandleInterval = CandleInterval.CANDLE_INTERVAL_5_MIN
    }

    /** Неизменяемое состояние истории одного инструмента, безопасное для выдачи из ConcurrentHashMap. */
    private data class CandleHistoryCacheEntry(
        val candles: List<HistoricCandle>,
        val indicators: CandleIndicators,
        val loadedAt: Instant,
        val refreshAfter: Instant
    )

    /** Показатели, зависящие только от закрытых свечей; текущая цена в них не входит. */
    private data class CandleIndicators(
        val closes: List<BigDecimal>,
        val ema5: BigDecimal?,
        val ema21: BigDecimal?,
        val ema50: BigDecimal?,
        val ema200: BigDecimal?,
        val previousEma5: BigDecimal?,
        val previousEma21: BigDecimal?,
        val rsi: Double?,
        val macd: MacdData?,
        val atr: BigDecimal?,
        val currentVolume: Long,
        val avgVolume: Long,
        val volatility: Double,
        val strategyCandleKey: String?,
        // НОВОЕ: для Choppiness Index и ADX
        val high14: BigDecimal? = null,
        val low14: BigDecimal? = null,
        val adx: Double? = null,
        val vwap: BigDecimal? = null
    )
}
