package ru.bolotov.tradebot.strategy

import org.springframework.stereotype.Component

@Component
class StrategyManager(
    private val crossEmaStrategy: CrossEmaStrategy,
    private val rsiStrategy: RsiStrategy,
    private val compositeStrategy: CompositeStrategy
) {
    private var currentStrategy: TradingStrategy = crossEmaStrategy

    fun getCurrentStrategy(): TradingStrategy = currentStrategy

    fun switchToSimpleStrategy(strategyName: String) {
        currentStrategy = when (strategyName.lowercase()) {
            "ema", "cross_ema" -> crossEmaStrategy
            "rsi" -> rsiStrategy
            else -> crossEmaStrategy
        }
    }

    fun switchToCompositeStrategy(weights: Map<String, Int>) {
        compositeStrategy.updateWeights(weights)
        currentStrategy = compositeStrategy
    }

    fun analyze(data: MarketData): Signal = currentStrategy.analyze(data)
    fun getExplanation(data: MarketData): String = currentStrategy.getExplanation(data)
}