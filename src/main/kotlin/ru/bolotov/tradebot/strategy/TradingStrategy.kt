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
    val confidence: Double,
    val reason: String? = null
) {
    companion object {
        val HOLD = Signal(OrderDirection.HOLD, 0.0, "Нет сигнала")
    }
}

enum class OrderDirection { BUY, SELL, HOLD }