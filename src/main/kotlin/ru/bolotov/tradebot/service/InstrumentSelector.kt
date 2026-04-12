package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import ru.tinkoff.piapi.core.InstrumentsService
import ru.tinkoff.piapi.core.MarketDataService
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.Quotation
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

    suspend fun selectTradableInstruments(
        minDailyVolume: Long = 10_000_000,
        minVolatility: Double = 3.0,
        maxVolatility: Double = 15.0,
        maxCount: Int = 10
    ): List<SelectedInstrument> {
        logger.info { "========== НАЧАЛО ОТБОРА ИНСТРУМЕНТОВ ==========" }
        logger.info { "Параметры фильтрации: мин.объём=$minDailyVolume, волатильность=$minVolatility%..$maxVolatility%, макс.кол-во=$maxCount" }

        val allShares = instrumentsService.tradableSharesSync
        logger.info { "Получено ${allShares.size} доступных акций" }

        var passedTradingStatus = 0
        var failedVolume = 0
        var failedVolatility = 0
        var otherErrors = 0

        val selected = allShares
            .filter { share ->
                val tradable = isTradableViaApi(share.uid)
                if (tradable) {
                    passedTradingStatus++
                    logger.debug { "✅ ${share.ticker}: доступен для торговли через API" }
                } else {
                    logger.debug { "❌ ${share.ticker}: НЕ доступен для торговли через API" }
                }
                tradable
            }
            .mapNotNull { share ->
                try {
                    val dailyVolume = getDailyVolume(share.uid)
                    if (dailyVolume < minDailyVolume) {
                        failedVolume++
                        logger.debug { "📉 ${share.ticker}: объём $dailyVolume < $minDailyVolume - пропуск" }
                        return@mapNotNull null
                    }
                    logger.debug { "📊 ${share.ticker}: объём = $dailyVolume (OK)" }

                    val volatility = calculateVolatility(share.uid, 30)
                    if (volatility < minVolatility || volatility > maxVolatility) {
                        failedVolatility++
                        logger.debug { "📈 ${share.ticker}: волатильность ${String.format("%.2f", volatility)}% вне диапазона [$minVolatility-$maxVolatility] - пропуск" }
                        return@mapNotNull null
                    }
                    logger.debug { "🎯 ${share.ticker}: волатильность = ${String.format("%.2f", volatility)}% (OK)" }

                    val price = getCurrentPrice(share.uid)
                    logger.debug { "💰 ${share.ticker}: текущая цена = $price" }

                    SelectedInstrument(
                        uid = share.uid,
                        ticker = share.ticker,
                        name = share.name,
                        dailyVolume = dailyVolume,
                        volatility = volatility,
                        price = price
                    )
                } catch (e: Exception) {
                    otherErrors++
                    logger.error(e) { "❌ Ошибка анализа ${share.ticker}: ${e.message}" }
                    null
                }
            }
            .sortedByDescending { it.dailyVolume }
            .take(maxCount)

        // Итоговая статистика
        logger.info { "========== СТАТИСТИКА ОТБОРА ==========" }
        logger.info { "✅ Прошли проверку торговли: $passedTradingStatus" }
        logger.info { "❌ Отсеяно по объёму: $failedVolume" }
        logger.info { "❌ Отсеяно по волатильности: $failedVolatility" }
        logger.info { "❌ Ошибки при анализе: $otherErrors" }
        logger.info { "🎯 Итоговый список (${selected.size} инструментов):" }

        if (selected.isEmpty()) {
            logger.warn { "⚠️ НЕ ОТОБРАНО НИ ОДНОГО ИНСТРУМЕНТА! Проверьте настройки фильтрации." }
            logger.warn { "Возможно, стоит уменьшить minDailyVolume или расширить диапазон волатильности." }
        } else {
            selected.forEachIndexed { index, instrument ->
                logger.info { "  ${index + 1}. ${instrument.ticker} (${instrument.name}): цена=${instrument.price}, объём=${instrument.dailyVolume}, волатильность=${String.format("%.2f", instrument.volatility)}%" }
            }
        }

        logger.info { "========== КОНЕЦ ОТБОРА ==========" }

        return selected
    }

    private suspend fun isTradableViaApi(instrumentUid: String): Boolean {
        return try {
            val status = marketDataService.getTradingStatusSync(instrumentUid)
            delay(100.milliseconds)
            status.apiTradeAvailableFlag && status.marketOrderAvailableFlag
        } catch (e: Exception) {
            logger.error(e) { "Ошибка проверки статуса торговли для $instrumentUid" }
            false
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

            // Рассчитываем дневные доходности
            val returns = mutableListOf<Double>()
            for (i in 1 until candles.size) {
                val prevClose = quotationToBigDecimal(candles[i - 1].close)
                val currClose = quotationToBigDecimal(candles[i].close)
                val dailyReturn = (currClose - prevClose) / prevClose
                returns.add(dailyReturn.toDouble())
            }

            // Стандартное отклонение доходностей
            val mean = returns.average()
            val variance = returns.map { (it - mean) * (it - mean) }.average()
            val stdDev = Math.sqrt(variance)

            // Годовая волатильность (умножаем на sqrt(252))
            val annualizedVol = stdDev * Math.sqrt(252.0)

            annualizedVol * 100
        } catch (e: Exception) {
            logger.error(e) { "Ошибка расчёта волатильности для $instrumentUid" }
            0.0
        }
    }

    private suspend fun getCurrentPrice(instrumentUid: String): BigDecimal {
        return try {
            val lastPrices = marketDataService.getLastPricesSync(listOf(instrumentUid))
            lastPrices.firstOrNull()?.let { quotationToBigDecimal(it.price) } ?: BigDecimal.ZERO
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения цены для $instrumentUid" }
            BigDecimal.ZERO
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
    val dailyVolume: Long,
    val volatility: Double,
    val price: BigDecimal
)