package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.core.InstrumentsService
import ru.tinkoff.piapi.core.MarketDataService
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

@Component
class InstrumentSelector(
    private val instrumentsService: InstrumentsService,
    private val marketDataService: MarketDataService
) {

    enum class InstrumentCategory(val types: List<String>, val description: String) {
        STOCK(listOf("stock", "common_share", "preferred_share"), "Акции"),
        BOND(listOf("bond"), "Облигации"),
        ETF(listOf("etf", "etp"), "ETF"),
        FUTURES(listOf("future"), "Фьючерсы"),
        CURRENCY(listOf("currency"), "Валюта"),
        ALL(emptyList(), "Все инструменты");

        companion object {
            fun fromString(value: String): InstrumentCategory? {
                return entries.find { it.name.equals(value, ignoreCase = true) }
            }
        }
    }

    suspend fun selectTradableInstruments(
        minDailyVolume: Long = 10_000_000,
        minVolatility: Double = 3.0,
        maxVolatility: Double = 15.0,
        maxCount: Int = 10
    ): List<SelectedInstrument> {
        logger.info { "========== НАЧАЛО ОТБОРА ИНСТРУМЕНТОВ ==========" }
        logger.info {
            "Параметры фильтрации: мин.объём=$minDailyVolume, " +
                    "волатильность=$minVolatility%..$maxVolatility%, макс.кол-во=$maxCount"
        }

        val allShares = instrumentsService.tradableSharesSync
        logger.info { "Получено ${allShares.size} доступных акций" }

        val shareUids = allShares.map { it.uid }
        val tradingStatuses = getTradingStatusesBatch(shareUids)
        val tradableShares = allShares.filter { share ->
            tradingStatuses[share.uid] ?: false
        }

        logger.info { "✅ Прошли проверку торговли: ${tradableShares.size} из ${allShares.size}" }

        val prices = getCurrentPricesBatch(tradableShares.map { it.uid })

        var failedVolume = 0
        var failedVolatilityLow = 0
        var failedVolatilityHigh = 0
        var failedNoCandles = 0
        var failedAnalysis = 0
        val analyzedCandidates = mutableListOf<SelectedInstrument>()

        val selected = tradableShares
            .mapNotNull { share ->
                try {
                    val candles = getDailyCandles(share.uid, 30)
                    if (candles.isEmpty()) {
                        failedNoCandles++
                        return@mapNotNull null
                    }

                    val dailyVolume = calculateAverageDailyVolume(candles)
                    val volatility = calculateVolatility(candles)
                    val price = prices[share.uid] ?: BigDecimal.ZERO
                    val candidate = SelectedInstrument(
                        uid = share.uid,
                        ticker = share.ticker,
                        name = share.name,
                        instrumentType = "stock",
                        dailyVolume = dailyVolume,
                        volatility = volatility,
                        price = price
                    )
                    analyzedCandidates += candidate

                    if (dailyVolume < minDailyVolume) {
                        failedVolume++
                        return@mapNotNull null
                    }

                    if (volatility < minVolatility) {
                        failedVolatilityLow++
                        return@mapNotNull null
                    }

                    if (volatility > maxVolatility) {
                        failedVolatilityHigh++
                        return@mapNotNull null
                    }

                    candidate
                } catch (e: Exception) {
                    failedAnalysis++
                    logger.error(e) { "Ошибка анализа ${share.ticker}" }
                    null
                }
            }
            .sortedByDescending { it.dailyVolume }
            .take(maxCount)

        logger.info {
            "📊 Диагностика отбора: всего=${allShares.size}, прошли trading-status=${tradableShares.size}, " +
                    "нет свечей=$failedNoCandles, объём=$failedVolume, " +
                    "волатильность ниже=$failedVolatilityLow, волатильность выше=$failedVolatilityHigh, " +
                    "ошибки=$failedAnalysis, выбрано=${selected.size}"
        }

        logger.info { "Топ кандидатов по объёму после расчёта метрик:" }
        analyzedCandidates
            .sortedByDescending { it.dailyVolume }
            .take(maxCount.coerceAtMost(20))
            .forEachIndexed { index, instrument ->
                logger.info {
                    "Кандидат ${index + 1}: ${instrument.ticker}, price=${instrument.price}, " +
                            "volume=${instrument.dailyVolume}, volatility=${String.format("%.2f", instrument.volatility)}%"
                }
            }

        logger.info { "🎯 Итоговый список (${selected.size} инструментов):" }
        selected.forEachIndexed { index, instrument ->
            logger.info {
                "  ${index + 1}. ${instrument.ticker} (${instrument.name}): " +
                        "цена=${instrument.price}, объём=${instrument.dailyVolume}, " +
                        "волатильность=${String.format("%.2f", instrument.volatility)}%"
            }
        }

        return selected
    }

    private fun getTradingStatusesBatch(instrumentUids: List<String>): Map<String, Boolean> {
        if (instrumentUids.isEmpty()) return emptyMap()

        return try {
            val response = marketDataService.getTradingStatusesSync(instrumentUids)
            response.tradingStatusesList.associate { status ->
                status.instrumentUid to (status.apiTradeAvailableFlag && status.marketOrderAvailableFlag)
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка пакетной проверки статусов торговли" }
            instrumentUids.associateWith { false }
        }
    }

    private suspend fun getCurrentPricesBatch(instrumentUids: List<String>): Map<String, BigDecimal> {
        if (instrumentUids.isEmpty()) return emptyMap()

        return try {
            instrumentUids.chunked(300).flatMap { chunk ->
                val lastPrices = marketDataService.getLastPricesSync(chunk)
                delay(100.milliseconds)

                lastPrices.map { price ->
                    price.instrumentUid to quotationToBigDecimal(price.price)
                }
            }.toMap()
        } catch (e: Exception) {
            logger.error(e) { "Ошибка пакетного получения цен" }
            emptyMap()
        }
    }

    private suspend fun getDailyCandles(instrumentUid: String, days: Int): List<HistoricCandle> {
        return try {
            val now = Instant.now()
            val startDate = now.minus(days.toLong(), ChronoUnit.DAYS)

            marketDataService.getCandlesSync(
                instrumentUid,
                startDate,
                now,
                CandleInterval.CANDLE_INTERVAL_DAY
            )
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения дневных свечей для $instrumentUid" }
            emptyList()
        }
    }

    private fun calculateAverageDailyVolume(candles: List<HistoricCandle>): Long {
        return candles
            .map { it.volume }
            .filter { it > 0 }
            .takeLast(5)
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toLong()
            ?: 0L
    }

    private fun calculateVolatility(candles: List<HistoricCandle>): Double {
        if (candles.size < 2) return 0.0

        val returns = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val prevClose = quotationToBigDecimal(candles[i - 1].close)
            val currClose = quotationToBigDecimal(candles[i].close)
            if (prevClose <= BigDecimal.ZERO) continue
            val dailyReturn = (currClose - prevClose) / prevClose
            returns.add(dailyReturn.toDouble())
        }

        if (returns.isEmpty()) return 0.0

        val mean = returns.average()
        val variance = returns.map { (it - mean) * (it - mean) }.average()
        val stdDev = Math.sqrt(variance)
        val annualizedVol = stdDev * Math.sqrt(252.0)
        return annualizedVol * 100
    }

    private fun quotationToBigDecimal(quotation: Quotation): BigDecimal {
        return BigDecimal.valueOf(quotation.units)
            .add(BigDecimal.valueOf(quotation.nano.toLong(), 9))
    }
}

data class SelectedInstrument(
    val uid: String,
    val ticker: String,
    val name: String,
    val instrumentType: String,
    val dailyVolume: Long,
    val volatility: Double,
    val price: BigDecimal
)
