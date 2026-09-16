package ru.bolotov.tradebot.broker

import org.springframework.stereotype.Component
import ru.ttech.piapi.core.MarketDataServiceSync
import ru.tinkoff.piapi.contract.v1.CandleInterval
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Read-only broker boundary for diagnostic M5 exports; does not touch the trading cache. */
interface CandleHistoryReader {
    fun read(instrumentUid: UUID, from: Instant, to: Instant): List<HistoricalCandle>
}

data class HistoricalCandle(
    val time: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
    val complete: Boolean
)

@Component
class TInvestCandleHistoryReader(private val marketData: MarketDataServiceSync) : CandleHistoryReader {
    override fun read(instrumentUid: UUID, from: Instant, to: Instant): List<HistoricalCandle> =
        marketData.getCandlesSync(instrumentUid.toString(), from, to, CandleInterval.CANDLE_INTERVAL_5_MIN)
            .map { candle ->
                HistoricalCandle(
                    Instant.ofEpochSecond(candle.time.seconds, candle.time.nanos.toLong()),
                    candle.open.toBigDecimal(), candle.high.toBigDecimal(),
                    candle.low.toBigDecimal(), candle.close.toBigDecimal(),
                    candle.volume, candle.isComplete
                )
            }
}
