package ru.bolotov.tradebot.strategy

import org.springframework.stereotype.Component

@Component
class CompositeStrategy(
    private val crossEmaStrategy: CrossEmaStrategy,
    private val rsiStrategy: RsiStrategy
) : TradingStrategy {

    override val name = "Composite"
    override val description = "Комбинированная стратегия с голосованием"

    private var weights: Map<String, Int> = mapOf(
        crossEmaStrategy.name to 1,
        rsiStrategy.name to 1
    )

    fun updateWeights(newWeights: Map<String, Int>) {
        weights = newWeights
    }

    override fun analyze(data: MarketData): Signal {
        val signals = listOf(
            crossEmaStrategy.analyze(data) to (weights[crossEmaStrategy.name] ?: 1),
            rsiStrategy.analyze(data) to (weights[rsiStrategy.name] ?: 1)
        )
        val totalWeight = signals.sumOf { it.second }
        val buyWeight = signals.filter { it.first.direction == OrderDirection.BUY }.sumOf { it.second }
        val sellWeight = signals.filter { it.first.direction == OrderDirection.SELL }.sumOf { it.second }

        return when {
            buyWeight > sellWeight -> Signal(OrderDirection.BUY, buyWeight.toDouble() / totalWeight)
            sellWeight > buyWeight -> Signal(OrderDirection.SELL, sellWeight.toDouble() / totalWeight)
            else -> Signal(OrderDirection.HOLD, 0.0)
        }
    }

    override fun getExplanation(data: MarketData): String = """
        🧠 Комбинированная стратегия (веса: $weights)
        
        ${crossEmaStrategy.getExplanation(data)}
        
        ${rsiStrategy.getExplanation(data)}
        
        Итоговое решение: ${analyze(data).direction}
    """.trimIndent()
}