package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.tinkoff.piapi.core.MarketDataService
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import ru.tinkoff.piapi.contract.v1.InstrumentType
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.core.InstrumentsService
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Component
class MarketDataProvider(
    private val marketDataService: MarketDataService,
    private val instrumentsService: InstrumentsService
) {

    suspend fun getUidByTicker(ticker: String): String? {
        return try {
            val instrument = instrumentsService.getInstrumentByTickerSync(ticker, "TQBR")
            instrument.uid
        } catch (e: Exception) {
            logger.error(e) { "Не удалось найти UID для $ticker" }
            null
        }
    }

    suspend fun fetchMarketData(instrumentUid: String): MarketData? {
        logger.info { "Начинаем fetchMarketData для $instrumentUid" }
        return try {
            // 1. Последняя цена
            val lastPricesFuture = marketDataService.getLastPrices(listOf(instrumentUid))
            val lastPrices = lastPricesFuture.get(10, TimeUnit.SECONDS)
            val lastPrice = lastPrices.firstOrNull()
                ?: throw IllegalStateException("Нет данных о последней цене для $instrumentUid")
            val currentPrice = quotationToBigDecimal(lastPrice.price)

            // 2. Свечи за последние 3 часа (5-минутные) → 36 свечей, достаточно для EMA21 и RSI
            val now = Instant.now()
            val threeHoursAgo = now.minusSeconds(10800)  // 3 часа
            val candlesFuture = marketDataService.getCandles(
                instrumentUid,
                threeHoursAgo,
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

            logger.info { "$instrumentUid: цена=$currentPrice, EMA5=$ema5, EMA21=$ema21, RSI=$rsi" }

            MarketData(
                instrumentId = instrumentUid,
                instrumentName = instrumentUid,
                currentPrice = currentPrice,
                ema5 = ema5,
                ema21 = ema21,
                rsi = rsi,
                volume = currentVolume,
                avgVolume = avgVolume,
                spread = BigDecimal.valueOf(0.1),
                volatility = 2.5
            )
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения данных для $instrumentUid" }
            null
        }
    }

    private fun quotationToBigDecimal(quotation: Quotation): BigDecimal {
        return BigDecimal.valueOf(quotation.units)
            .add(BigDecimal.valueOf(quotation.nano.toLong(), 9))
    }

    private fun calculateEMA(prices: List<BigDecimal>, period: Int): BigDecimal? {
        if (prices.size < period) return null
        val multiplier = 2.0 / (period + 1)
        // Вычисляем начальное SMA как среднее первых period цен
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
}