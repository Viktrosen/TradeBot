package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class StrategyManager(
    private val crossEmaStrategy: CrossEmaStrategy,
    private val rsiStrategy: RsiStrategy,
    private val macdStrategy: MacdStrategy,
    private val bbStrategy: BollingerBandsStrategy,
    private val votingStrategy: VotingStrategy,
    private val confirmationStrategy: ConfirmationStrategy,
    private val candlestickPatternStrategy: CandlestickPatternStrategy  // ← исправлено
) {
    private var currentStrategy: TradingStrategy = crossEmaStrategy

    private val simpleStrategies = mapOf(
        "ema" to crossEmaStrategy,
        "cross_ema" to crossEmaStrategy,
        "rsi" to rsiStrategy,
        "macd" to macdStrategy,
        "bb" to bbStrategy,
        "bollinger" to bbStrategy
    )

    fun getCurrentStrategy(): TradingStrategy = currentStrategy

    fun isCandlestickStrategyActive(): Boolean =
        currentStrategy === candlestickPatternStrategy

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
                "name" to macdStrategy.name,
                "description" to macdStrategy.description,
                "type" to "simple"
            ),
            mapOf(
                "name" to bbStrategy.name,
                "description" to bbStrategy.description,
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
            ),
            mapOf(
                "name" to candlestickPatternStrategy.name,
                "description" to candlestickPatternStrategy.description,
                "type" to "candlestick"
            )
        )
    }

    fun switchToCandlestickStrategy() {
        currentStrategy = candlestickPatternStrategy
        logger.info { "🕯️ Переключено на свечную стратегию: ${candlestickPatternStrategy.name}" }
    }

    fun switchToSimpleStrategy(strategyName: String) {
        currentStrategy = simpleStrategies[strategyName.lowercase()] ?: crossEmaStrategy
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
