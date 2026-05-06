package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.contract.v1.ShareType
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
        // Акции (обыкновенные и привилегированные)
        STOCK(listOf("stock", "common_share", "preferred_share"), "Акции"),

        // Облигации
        BOND(listOf("bond"), "Облигации"),

        // ETF (фонды)
        ETF(listOf("etf", "etp"), "ETF"),

        // Фьючерсы
        FUTURES(listOf("future"), "Фьючерсы"),

        // Валюта
        CURRENCY(listOf("currency"), "Валюта"),

        // Все инструменты
        ALL(emptyList(), "Все инструменты");

        companion object {
            fun fromString(value: String): InstrumentCategory? {
                return values().find { it.name.equals(value, ignoreCase = true) }
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
        logger.info { "Параметры фильтрации: мин.объём=$minDailyVolume, волатильность=$minVolatility%..$maxVolatility%, макс.кол-во=$maxCount" }

        val allShares = instrumentsService.getTradableSharesSync()
        logger.info { "Получено ${allShares.size} доступных акций" }

        // Пакетная проверка статусов торговли
        val shareUids = allShares.map { it.uid }
        val tradingStatuses = getTradingStatusesBatch(shareUids)

        val tradableShares = allShares.filter { share ->
            tradingStatuses[share.uid] ?: false
        }

        logger.info { "✅ Прошли проверку торговли: ${tradableShares.size} из ${allShares.size}" }

        // Получаем цены пакетно
        val prices = getCurrentPricesBatch(tradableShares.map { it.uid })

        var failedVolume = 0
        var failedVolatility = 0

        val selected = tradableShares
            .mapNotNull { share ->
                try {
                    val dailyVolume = getDailyVolume(share.uid)
                    if (dailyVolume < minDailyVolume) {
                        failedVolume++
                        return@mapNotNull null
                    }

                    val volatility = calculateVolatility(share.uid, 30)
                    if (volatility < minVolatility || volatility > maxVolatility) {
                        failedVolatility++
                        return@mapNotNull null
                    }

                    val price = prices[share.uid] ?: BigDecimal.ZERO

                    SelectedInstrument(
                        uid = share.uid,
                        ticker = share.ticker,
                        name = share.name,
                        instrumentType = "stock",  // ← просто "stock"
                        dailyVolume = dailyVolume,
                        volatility = volatility,
                        price = price
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Ошибка анализа ${share.ticker}" }
                    null
                }
            }
            .sortedByDescending { it.dailyVolume }
            .take(maxCount)

        logger.info { "🎯 Итоговый список (${selected.size} инструментов):" }
        selected.forEachIndexed { index, instrument ->
            logger.info { "  ${index + 1}. ${instrument.ticker} (${instrument.name}): цена=${instrument.price}, объём=${instrument.dailyVolume}, волатильность=${String.format("%.2f", instrument.volatility)}%" }
        }

        return selected
    }

    /**
     * Пакетное получение статусов торговли для списка инструментов
     * ОДИН запрос вместо N запросов!
     */
    private fun getTradingStatusesBatch(instrumentUids: List<String>): Map<String, Boolean> {
        if (instrumentUids.isEmpty()) return emptyMap()

        return try {
            val response = marketDataService.getTradingStatusesSync(instrumentUids)

            response.tradingStatusesList.associate { status ->
                status.instrumentUid to (status.apiTradeAvailableFlag && status.marketOrderAvailableFlag)
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка пакетной проверки статусов торговли" }

            // Fallback: возвращаем false для всех
            instrumentUids.associateWith { false }
        }
    }

    /**
     * Пакетное получение текущих цен для списка инструментов
     */
    private suspend fun getCurrentPricesBatch(instrumentUids: List<String>): Map<String, BigDecimal> {
        if (instrumentUids.isEmpty()) return emptyMap()

        return try {
            // Разбиваем на чанки по 300 (ограничение API)
            instrumentUids.chunked(300).flatMap { chunk ->
                val lastPrices = marketDataService.getLastPricesSync(chunk)
                delay(100.milliseconds) // Небольшая задержка между чанками

                lastPrices.map { price ->
                    price.instrumentUid to quotationToBigDecimal(price.price)
                }
            }.toMap()
        } catch (e: Exception) {
            logger.error(e) { "Ошибка пакетного получения цен" }
            emptyMap()
        }
    }

    private suspend fun getDailyVolume(instrumentUid: String): Long {
        return try {
            val now = Instant.now()
            val dayAgo = now.minus(1, ChronoUnit.DAYS)

            val candles = marketDataService.getCandlesSync(
                instrumentUid,
                dayAgo,
                now,
                CandleInterval.CANDLE_INTERVAL_DAY
            )

            candles.sumOf { it.volume }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения объёма для $instrumentUid" }
            0L
        }
    }

    private suspend fun calculateVolatility(instrumentUid: String, days: Int): Double {
        return try {
            val now = Instant.now()
            val startDate = now.minus(days.toLong(), ChronoUnit.DAYS)

            val candles = marketDataService.getCandlesSync(
                instrumentUid,
                startDate,
                now,
                CandleInterval.CANDLE_INTERVAL_DAY
            )

            if (candles.size < 2) {
                logger.debug { "Недостаточно данных для расчёта волатильности $instrumentUid (${candles.size} свечей)" }
                return 0.0
            }

            val returns = mutableListOf<Double>()
            for (i in 1 until candles.size) {
                val prevClose = quotationToBigDecimal(candles[i - 1].close)
                val currClose = quotationToBigDecimal(candles[i].close)
                val dailyReturn = (currClose - prevClose) / prevClose
                returns.add(dailyReturn.toDouble())
            }

            val mean = returns.average()
            val variance = returns.map { (it - mean) * (it - mean) }.average()
            val stdDev = Math.sqrt(variance)
            val annualizedVol = stdDev * Math.sqrt(252.0)

            annualizedVol * 100
        } catch (e: Exception) {
            logger.error(e) { "Ошибка расчёта волатильности для $instrumentUid" }
            0.0
        }
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
    val instrumentType: String,  // 🆕 добавлено поле
    val dailyVolume: Long,
    val volatility: Double,
    val price: BigDecimal
)