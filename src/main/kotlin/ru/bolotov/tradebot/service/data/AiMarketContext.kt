package ru.bolotov.tradebot.service.data

import ru.bolotov.tradebot.strategy.MarketData
import java.math.BigDecimal

/** Снимок индикаторов и цены инструмента, доступный AI при принятии решения. */
data class AiMarketContext(
    val instrument: String,
    val currentPrice: BigDecimal,
    val ema5: BigDecimal?,
    val ema21: BigDecimal?,
    val rsi: Double?,
    val macdHistogram: BigDecimal?,
    val bollingerPercentB: Double?,
    val atr: BigDecimal?,
    val vwap: BigDecimal?,
    val vwapDeviationPercent: Double?,
    val volume: Long,
    val averageVolume: Long,
    val volatility: Double,
    val spread: BigDecimal
) {
    companion object {
        fun from(data: MarketData): AiMarketContext {
            val vwap = data.vwap?.takeIf { it > BigDecimal.ZERO }
            val vwapDeviationPercent = vwap?.let {
                (data.currentPrice.toDouble() - it.toDouble()) / it.toDouble() * 100
            }

            return AiMarketContext(
            instrument = data.instrumentName,
            currentPrice = data.currentPrice,
            ema5 = data.ema5,
            ema21 = data.ema21,
            rsi = data.rsi,
            macdHistogram = data.macd?.histogram,
            bollingerPercentB = data.bollingerBands?.percentB,
            atr = data.atr,
            vwap = vwap,
            vwapDeviationPercent = vwapDeviationPercent,
            volume = data.volume,
            averageVolume = data.avgVolume,
            volatility = data.volatility,
            spread = data.spread
        )
        }
    }
}
