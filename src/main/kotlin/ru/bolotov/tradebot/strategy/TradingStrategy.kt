package ru.bolotov.tradebot.strategy

import java.math.BigDecimal

interface TradingStrategy {
    val name: String
    val description: String
    fun analyze(data: MarketData): Signal
    fun getExplanation(data: MarketData): String
}

/**
 * Конфигурируемая стратегия (может менять параметры)
 */
interface ConfigurableStrategy : TradingStrategy {
    fun configure(config: StrategyConfiguration)
}

/**
 * Конфигурация стратегии
 */
sealed class StrategyConfiguration {
    data class Voting(val weights: Map<String, Int>) : StrategyConfiguration()
    data class Confirmation(val requiredIndicators: List<String>) : StrategyConfiguration()
    data class Candlestick(val requiredPatterns: List<String>) : StrategyConfiguration()
}

data class MarketData(
    val instrumentId: String,
    val instrumentName: String,
    val currentPrice: BigDecimal,
    val lotSize: Int = 1,
    val ema5: BigDecimal?,
    val ema21: BigDecimal?,
    val previousEma5: BigDecimal? = null,
    val previousEma21: BigDecimal? = null,
    val rsi: Double?,
    val macd: MacdData?,
    val bollingerBands: BollingerBandsData?,
    val atr: BigDecimal?,  // 🆕 Average True Range
    val volume: Long,
    val avgVolume: Long,
    val spread: BigDecimal,
    val volatility: Double,
    val strategyCandleKey: String? = null,
    val candlestickPattern: CandlestickPatternStrategy.PatternResult? = null
)

data class MacdData(
    val macdLine: BigDecimal,
    val signalLine: BigDecimal,
    val histogram: BigDecimal,
    val isPositive: Boolean  // MACD > 0
)

data class BollingerBandsData(
    val upperBand: BigDecimal,
    val middleBand: BigDecimal,
    val lowerBand: BigDecimal,
    val bandwidth: BigDecimal,  // (upper - lower) / middle * 100
    val percentB: Double        // (price - lower) / (upper - lower) — позиция цены внутри канала (0-1)
)

data class Signal(
    val direction: OrderDirection,
    val confidence: Double,
    val reason: String? = null
) {
    companion object {
        val HOLD = Signal(OrderDirection.HOLD, 0.0, "Нет сигнала")
    }
}

enum class OrderDirection { BUY, SELL, HOLD }
