package ru.bolotov.tradebot.service.data

import ru.bolotov.tradebot.strategy.MarketData
import java.math.BigDecimal

/** Снимок индикаторов и цены инструмента, доступный AI при принятии решения. */
data class AiMarketContext(
    val instrument: String,
    val currentPrice: BigDecimal,
    val ema5: BigDecimal?,
    val ema21: BigDecimal?,
    val previousEma5: BigDecimal?,
    val previousEma21: BigDecimal?,
    val ema50: BigDecimal?,
    val ema200: BigDecimal?,
    val rsi: Double?,
    val macdLine: BigDecimal?,
    val macdSignalLine: BigDecimal?,
    val macdHistogram: BigDecimal?,
    val bollingerUpperBand: BigDecimal?,
    val bollingerMiddleBand: BigDecimal?,
    val bollingerLowerBand: BigDecimal?,
    val bollingerBandwidth: BigDecimal?,
    val bollingerPercentB: Double?,
    val atr: BigDecimal?,
    val adx: Double?,
    val high14: BigDecimal?,
    val low14: BigDecimal?,
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
            previousEma5 = data.previousEma5,
            previousEma21 = data.previousEma21,
            ema50 = data.ema50,
            ema200 = data.ema200,
            rsi = data.rsi,
            macdLine = data.macd?.macdLine,
            macdSignalLine = data.macd?.signalLine,
            macdHistogram = data.macd?.histogram,
            bollingerUpperBand = data.bollingerBands?.upperBand,
            bollingerMiddleBand = data.bollingerBands?.middleBand,
            bollingerLowerBand = data.bollingerBands?.lowerBand,
            bollingerBandwidth = data.bollingerBands?.bandwidth,
            bollingerPercentB = data.bollingerBands?.percentB,
            atr = data.atr,
            adx = data.adx,
            high14 = data.high14,
            low14 = data.low14,
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
