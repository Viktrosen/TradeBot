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
    val volume: Long,
    val averageVolume: Long,
    val volatility: Double,
    val spread: BigDecimal
) {
    companion object {
        fun from(data: MarketData) = AiMarketContext(
            instrument = data.instrumentName,
            currentPrice = data.currentPrice,
            ema5 = data.ema5,
            ema21 = data.ema21,
            rsi = data.rsi,
            macdHistogram = data.macd?.histogram,
            bollingerPercentB = data.bollingerBands?.percentB,
            atr = data.atr,
            volume = data.volume,
            averageVolume = data.avgVolume,
            volatility = data.volatility,
            spread = data.spread
        )
    }
}
