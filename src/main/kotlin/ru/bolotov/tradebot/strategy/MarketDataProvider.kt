package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.tinkoff.piapi.core.MarketDataService
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.core.InstrumentsService
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

@Component
class MarketDataProvider(
    private val marketDataService: MarketDataService,
    private val instrumentsService: InstrumentsService
) {
    private val instrumentCache = ConcurrentHashMap<String, InstrumentInfo>()
    private val calculationContext = MathContext.DECIMAL64

    data class InstrumentInfo(
        val ticker: String,
        val name: String,
        val lotSize: Int
    )

    suspend fun fetchMarketData(instrumentUid: String): MarketData? {
        logger.info { "Начинаем получение рыночных данных для $instrumentUid" }
        return try {
            val instrumentInfo = getInstrumentInfo(instrumentUid)
            val displayName = instrumentInfo?.ticker ?: instrumentUid.take(8)

            // 1. Последняя цена
            val lastPricesFuture = marketDataService.getLastPrices(listOf(instrumentUid))
            val lastPrices = lastPricesFuture.get(10, TimeUnit.SECONDS)
            val lastPrice = lastPrices.firstOrNull()
                ?: throw IllegalStateException("Нет данных о последней цене для $displayName")
            val currentPrice = quotationToBigDecimal(lastPrice.price)

            // 2. Свечи за последние 3 дня
            val now = Instant.now()
            val threeDaysAgo = now.minusSeconds(259200)
            val candlesFuture = marketDataService.getCandles(
                instrumentUid,
                threeDaysAgo,
                now,
                CandleInterval.CANDLE_INTERVAL_5_MIN
            )
            val candles = candlesFuture.get(10, TimeUnit.SECONDS)
                .filter { candle -> isClosed(candle, now, 5 * 60L) }
            val closes = candles.map { quotationToBigDecimal(it.close) }
            val volumes = candles.map { it.volume }

            val currentVolume = candles.lastOrNull()?.volume ?: 0L
            val avgVolume = if (volumes.isNotEmpty()) volumes.average().toLong() else 0L

            // 3. Индикаторы
            val ema5Series = calculateEMASeries(closes, 5)
            val ema21Series = calculateEMASeries(closes, 21)
            val ema5 = ema5Series?.lastOrNull()
            val ema21 = ema21Series?.lastOrNull()
            val rsi = calculateRSI(closes, 14)
            val macd = calculateMACD(closes)
            val bollingerBands = calculateBollingerBands(closes, currentPrice)
            val atr = calculateATR(candles)  // 🆕

            logger.info {
                "$displayName: цена=${formatIndicator(currentPrice)}, EMA5=${formatIndicator(ema5)}, " +
                    "EMA21=${formatIndicator(ema21)}, RSI=${rsi?.let { "%.2f".format(it) } ?: "нет данных"}, ATR=${formatIndicator(atr)}"
            }

            MarketData(
                instrumentId = instrumentUid,
                instrumentName = displayName,
                currentPrice = currentPrice,
                lotSize = instrumentInfo?.lotSize ?: 1,
                ema5 = ema5,
                ema21 = ema21,
                previousEma5 = ema5Series?.dropLast(1)?.lastOrNull(),
                previousEma21 = ema21Series?.dropLast(1)?.lastOrNull(),
                rsi = rsi,
                macd = macd,
                bollingerBands = bollingerBands,
                atr = atr,  // 🆕
                volume = currentVolume,
                avgVolume = avgVolume,
                spread = BigDecimal.valueOf(0.1),
                volatility = calculateVolatility(closes)
            )
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения данных для $instrumentUid" }
            null
        }
    }

    // 🆕 Расчёт ATR (Average True Range)
    private fun calculateATR(candles: List<HistoricCandle>, period: Int = 14): BigDecimal? {
        if (candles.size < period + 1) return null

        val trueRanges = mutableListOf<BigDecimal>()

        for (i in 1 until candles.size) {
            val high = quotationToBigDecimal(candles[i].high)
            val low = quotationToBigDecimal(candles[i].low)
            val prevClose = quotationToBigDecimal(candles[i - 1].close)

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
                val share = instrumentsService.getShareByUidSync(instrumentUid)
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

    private fun quotationToBigDecimal(quotation: Quotation): BigDecimal {
        return BigDecimal.valueOf(quotation.units)
            .add(BigDecimal.valueOf(quotation.nano.toLong(), 9))
    }

    private fun calculateEMA(prices: List<BigDecimal>, period: Int): BigDecimal? {
        return calculateEMASeries(prices, period)?.lastOrNull()
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

    private fun isClosed(candle: HistoricCandle, now: Instant, intervalSeconds: Long): Boolean {
        val candleStart = Instant.ofEpochSecond(candle.time.seconds, candle.time.nanos.toLong())
        return !candleStart.plusSeconds(intervalSeconds).isAfter(now)
    }

    private fun formatIndicator(value: BigDecimal?): String = value
        ?.setScale(4, RoundingMode.HALF_UP)
        ?.stripTrailingZeros()
        ?.toPlainString()
        ?: "нет данных"
}
