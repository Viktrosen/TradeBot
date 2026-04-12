package ru.bolotov.tradebot.strategy

import java.math.BigDecimal

interface TradingStrategy {
    val name: String
    val description: String
    fun analyze(data: MarketData): Signal
    fun getExplanation(data: MarketData): String
}

data class MarketData(
    val instrumentId: String,
    val instrumentName: String,
    val currentPrice: BigDecimal,
    val ema5: BigDecimal? = null,
    val ema21: BigDecimal? = null,
    val rsi: Double? = null,
    val volume: Long,
    val avgVolume: Long,
    val spread: BigDecimal,
    val volatility: Double
)

data class Signal(
    val direction: OrderDirection,
    val confidence: Double
)

enum class OrderDirection { BUY, SELL, HOLD }