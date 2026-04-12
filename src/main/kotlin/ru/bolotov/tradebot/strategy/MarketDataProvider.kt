package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.tinkoff.piapi.core.MarketDataService
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
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
            // Получаем информацию об инструменте (тикер и название)
            val instrumentInfo = getInstrumentInfo(instrumentUid)
            val displayName = instrumentInfo?.ticker ?: instrumentUid.take(8)

            // 1. Последняя цена
            val lastPricesFuture = marketDataService.getLastPrices(listOf(instrumentUid))
            val lastPrices = lastPricesFuture.get(10, TimeUnit.SECONDS)
            val lastPrice = lastPrices.firstOrNull()
                ?: throw IllegalStateException("Нет данных о последней цене для $displayName")
            val currentPrice = quotationToBigDecimal(lastPrice.price)

            // 2. Свечи за последние 3 часа (5-минутные)
            val now = Instant.now()
            val threeHoursAgo = now.minusSeconds(10800)
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

            logger.info { "$displayName: цена=$currentPrice, EMA5=$ema5, EMA21=$ema21, RSI=$rsi" }

            MarketData(
                instrumentId = instrumentUid,
                instrumentName = displayName,  // ← Теперь тикер!
                currentPrice = currentPrice,
                ema5 = ema5,
                ema21 = ema21,
                rsi = rsi,
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
        // Проверяем кэш
        instrumentCache[instrumentUid]?.let { return it }

        return try {
            // Правильный метод - getInstrumentByUIDSync (UID большими буквами)
            val response = instrumentsService.getInstrumentByUIDSync(instrumentUid)
            val instrument = response.instrument

            val info = InstrumentInfo(
                ticker = instrument.ticker,
                name = instrument.name
            )
            instrumentCache[instrumentUid] = info
            info
        } catch (e: Exception) {
            // Fallback: пробуем получить как акцию
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

    /**
     * Получить тикер по UID (для внешнего использования)
     */
    fun getTickerByUid(uid: String): String {
        return instrumentCache[uid]?.ticker ?: uid.take(8)
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