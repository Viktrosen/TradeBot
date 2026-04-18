package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.tinkoff.piapi.core.MarketDataService
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.core.InstrumentsService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Component
class MarketDataProvider(
    private val marketDataService: MarketDataService,
    private val instrumentsService: InstrumentsService
) {
    // Кэш названий инструментов
    private val instrumentCache = mutableMapOf<String, InstrumentInfo>()

    data class InstrumentInfo(
        val ticker: String,
        val name: String
    )

    suspend fun getUidByTicker(ticker: String): String? {
        return try {
            val instrument = instrumentsService.getShareByTickerSync(ticker, "TQBR")
            instrument.uid.also { uid ->
                instrumentCache[uid] = InstrumentInfo(instrument.ticker, instrument.name)
            }
        } catch (e: Exception) {
            logger.error(e) { "Не удалось найти UID для $ticker" }
            null
        }
    }

    suspend fun fetchMarketData(instrumentUid: String): MarketData? {
        logger.info { "Начинаем fetchMarketData для $instrumentUid" }
        return try {
            // Получаем информацию об инструменте
            val instrumentInfo = getInstrumentInfo(instrumentUid)
            val displayName = instrumentInfo?.ticker ?: instrumentUid.take(8)

            // 1. Последняя цена
            val lastPricesFuture = marketDataService.getLastPrices(listOf(instrumentUid))
            val lastPrices = lastPricesFuture.get(10, TimeUnit.SECONDS)
            val lastPrice = lastPrices.firstOrNull()
                ?: throw IllegalStateException("Нет данных о последней цене для $displayName")
            val currentPrice = quotationToBigDecimal(lastPrice.price)

            // 2. Свечи за последние 3 дня (для индикаторов)
            val now = Instant.now()
            val threeDaysAgo = now.minusSeconds(259200) // 3 дня
            val candlesFuture = marketDataService.getCandles(
                instrumentUid,
                threeDaysAgo,
                now,
                CandleInterval.CANDLE_INTERVAL_5_MIN
            )
            val candles: List<HistoricCandle> = candlesFuture.get(10, TimeUnit.SECONDS)
            val closes = candles.map { quotationToBigDecimal(it.close) }
            val volumes = candles.map { it.volume }

            val currentVolume = candles.lastOrNull()?.volume ?: 0L
            val avgVolume = if (volumes.isNotEmpty()) volumes.average().toLong() else 0L

            // 3. Индикаторы
            val ema5 = calculateEMA(closes, 5)
            val ema21 = calculateEMA(closes, 21)
            val rsi = calculateRSI(closes, 14)
            val macd = calculateMACD(closes)
            val bollingerBands = calculateBollingerBands(closes, currentPrice)

            logger.info { "$displayName: цена=$currentPrice, EMA5=$ema5, EMA21=$ema21, RSI=$rsi" }

            MarketData(
                instrumentId = instrumentUid,
                instrumentName = displayName,
                currentPrice = currentPrice,
                ema5 = ema5,
                ema21 = ema21,
                rsi = rsi,
                macd = macd,
                bollingerBands = bollingerBands,
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

    /**
     * Получить информацию об инструменте по UID
     */
    private suspend fun getInstrumentInfo(instrumentUid: String): InstrumentInfo? {
        instrumentCache[instrumentUid]?.let { return it }

        return try {
            val response = instrumentsService.getInstrumentByUIDSync(instrumentUid)
            val instrument = response.instrument
            val info = InstrumentInfo(
                ticker = instrument.ticker,
                name = instrument.name
            )
            instrumentCache[instrumentUid] = info
            info
        } catch (e: Exception) {
            try {
                val share = instrumentsService.getShareByUidSync(instrumentUid)
                val info = InstrumentInfo(
                    ticker = share.ticker,
                    name = share.name
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
        if (prices.size < period) return null
        val multiplier = 2.0 / (period + 1)
        val initialSma = prices.take(period).map { it.toDouble() }.average()
        var ema = BigDecimal(initialSma)
        for (i in period until prices.size) {
            val price = prices[i]
            ema = price * multiplier.toBigDecimal() + ema * (1 - multiplier).toBigDecimal()
        }
        return ema
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
        if (prices.size < 26) return null

        val ema12 = calculateEMA(prices, 12) ?: return null
        val ema26 = calculateEMA(prices, 26) ?: return null
        val macdLine = ema12 - ema26

        // Для Signal Line нужна история MACD. Упрощённо используем последние 9 значений MACD
        // В реальности нужно рассчитывать EMA от MACD Line, но для простоты используем заглушку
        val signalLine = macdLine * BigDecimal("0.9") // Заглушка

        return MacdData(
            macdLine = macdLine,
            signalLine = signalLine,
            histogram = macdLine - signalLine,
            isPositive = macdLine > BigDecimal.ZERO
        )
    }

    private fun calculateBollingerBands(prices: List<BigDecimal>, currentPrice: BigDecimal): BollingerBandsData? {
        if (prices.size < 20) return null

        // SMA 20
        val period = 20
        val last20Prices = prices.takeLast(period)
        val sma20 = last20Prices.reduce { acc, price -> acc + price } / BigDecimal(period)

        // Стандартное отклонение
        val variance = last20Prices.map { price ->
            val diff = price - sma20
            diff * diff
        }.reduce { acc, diff -> acc + diff }.toDouble() / period

        val stdDev = BigDecimal(Math.sqrt(variance))

        // Полосы (±2 стандартных отклонения)
        val multiplier = BigDecimal.valueOf(2)
        val upperBand = sma20 + stdDev * multiplier
        val lowerBand = sma20 - stdDev * multiplier

        // Ширина канала в процентах
        val bandwidth = if (sma20 > BigDecimal.ZERO) {
            (upperBand - lowerBand) / sma20 * BigDecimal(100)
        } else {
            BigDecimal.ZERO
        }

        // %B — позиция цены внутри канала (0 = нижняя, 1 = верхняя)
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
}