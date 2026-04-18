package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class StrategyManager(
    private val crossEmaStrategy: CrossEmaStrategy,
    private val rsiStrategy: RsiStrategy,
    private val votingStrategy: VotingStrategy,
    private val confirmationStrategy: ConfirmationStrategy
) {
    private var currentStrategy: TradingStrategy = crossEmaStrategy

    fun getCurrentStrategy(): TradingStrategy = currentStrategy

    fun getAvailableStrategies(): List<Map<String, String>> {
        return listOf(
            mapOf(
                "name" to crossEmaStrategy.name,
                "description" to crossEmaStrategy.description,
                "type" to "simple"
            ),
            mapOf(
                "name" to rsiStrategy.name,
                "description" to rsiStrategy.description,
                "type" to "simple"
            ),
            mapOf(
                "name" to confirmationStrategy.name,
                "description" to confirmationStrategy.description,
                "type" to "confirmation"
            ),
            mapOf(
                "name" to votingStrategy.name,
                "description" to votingStrategy.description,
                "type" to "voting"
            )
        )
    }

    fun switchToSimpleStrategy(strategyName: String) {
        currentStrategy = when (strategyName.lowercase()) {
            "ema", "cross_ema" -> crossEmaStrategy
            "rsi" -> rsiStrategy
            "confirmation" -> confirmationStrategy
            else -> {
                logger.warn { "Неизвестная стратегия '$strategyName', используется EMA" }
                crossEmaStrategy
            }
        }
        logger.info { "🔄 Переключено на стратегию: ${currentStrategy.name}" }
    }

    fun switchToVotingStrategy(weights: Map<String, Int>) {
        votingStrategy.configure(StrategyConfiguration.Voting(weights))
        currentStrategy = votingStrategy
        logger.info { "🔄 Переключено на стратегию голосования: $weights" }
    }

    fun switchToConfirmationStrategy(requiredIndicators: List<String>) {
        confirmationStrategy.configure(StrategyConfiguration.Confirmation(requiredIndicators))
        currentStrategy = confirmationStrategy
        logger.info { "🔄 Переключено на стратегию подтверждения: $requiredIndicators" }
    }

    fun analyze(data: MarketData): Signal = currentStrategy.analyze(data)

    fun getExplanation(data: MarketData): String = currentStrategy.getExplanation(data)
}