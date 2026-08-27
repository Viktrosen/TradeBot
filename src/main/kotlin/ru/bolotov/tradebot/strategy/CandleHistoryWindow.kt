package ru.bolotov.tradebot.strategy

import ru.tinkoff.piapi.contract.v1.CandleInterval
import java.time.Duration
import java.time.Instant

/**
 * Ограничивает период одного запроса GetCandles безопасным значением для
 * выбранного интервала. Значения намеренно меньше максимума T-Bank API, чтобы
 * избежать ошибки 30014 на пограничных значениях времени.
 */
object CandleHistoryWindow {
    /**
     * Возвращает самое раннее допустимое время для запроса исторических свечей.
     * Если вызывающему коду требуется более короткий период, он сохраняет его.
     */
    fun earliestAllowedFrom(now: Instant, interval: CandleInterval): Instant =
        now.minus(maxRequestDuration(interval))

    /** Ограничивает запрошенную дату начала доступным периодом API. */
    fun clampFrom(
        requestedFrom: Instant,
        now: Instant,
        interval: CandleInterval
    ): Instant = maxOf(requestedFrom, earliestAllowedFrom(now, interval))

    private fun maxRequestDuration(interval: CandleInterval): Duration = when (interval) {
        CandleInterval.CANDLE_INTERVAL_1_MIN,
        CandleInterval.CANDLE_INTERVAL_2_MIN,
        CandleInterval.CANDLE_INTERVAL_3_MIN -> Duration.ofHours(23)

        CandleInterval.CANDLE_INTERVAL_5_MIN,
        CandleInterval.CANDLE_INTERVAL_10_MIN -> Duration.ofDays(6)

        CandleInterval.CANDLE_INTERVAL_15_MIN,
        CandleInterval.CANDLE_INTERVAL_30_MIN -> Duration.ofDays(20)

        CandleInterval.CANDLE_INTERVAL_HOUR,
        CandleInterval.CANDLE_INTERVAL_2_HOUR,
        CandleInterval.CANDLE_INTERVAL_4_HOUR -> Duration.ofDays(80)

        CandleInterval.CANDLE_INTERVAL_DAY -> Duration.ofDays(365 * 5L)
        CandleInterval.CANDLE_INTERVAL_WEEK -> Duration.ofDays(365 * 4L)
        CandleInterval.CANDLE_INTERVAL_MONTH -> Duration.ofDays(365 * 9L)
        else -> Duration.ofDays(6)
    }
}
