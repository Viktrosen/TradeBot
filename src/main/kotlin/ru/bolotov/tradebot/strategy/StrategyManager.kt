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
    private val candlestickPatternStrategy: CandlestickPatternStrategy
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

    private val strategiesById = mapOf(
        "ema" to crossEmaStrategy,
        "rsi" to rsiStrategy,
        "macd" to macdStrategy,
        "bb" to bbStrategy,
        "voting" to votingStrategy,
        "confirmation" to confirmationStrategy,
        "candlestick" to candlestickPatternStrategy
    )

    fun getCurrentStrategy(): TradingStrategy = currentStrategy

    /**
     * Возвращает зарегистрированную стратегию по стабильному идентификатору.
     * Идентификаторы используются при сохранении стратегии входа в событии сделки.
     */
    fun getStrategyById(strategyId: String): TradingStrategy? = strategiesById[strategyId]

    fun getCurrentStrategyIdFor(strategy: TradingStrategy): String? =
        strategiesById.entries.firstOrNull { (_, registered) -> registered === strategy }?.key

    fun isCandlestickStrategy(strategy: TradingStrategy): Boolean =
        strategy === candlestickPatternStrategy

    fun getCurrentStrategyId(): String = when (currentStrategy) {
        crossEmaStrategy -> "ema"
        rsiStrategy -> "rsi"
        macdStrategy -> "macd"
        bbStrategy -> "bb"
        votingStrategy -> "voting"
        confirmationStrategy -> "confirmation"
        candlestickPatternStrategy -> "candlestick"
        else -> "ema"
    }

    fun getCurrentStrategyType(): String = when (currentStrategy) {
        votingStrategy -> "voting"
        confirmationStrategy -> "confirmation"
        candlestickPatternStrategy -> "candlestick"
        else -> "simple"
    }

    fun getCurrentStrategySettings(): Map<String, Any> = when (currentStrategy) {
        votingStrategy -> mapOf("weights" to votingStrategy.getWeights())
        confirmationStrategy -> mapOf("indicators" to confirmationStrategy.getRequiredIndicators())
        candlestickPatternStrategy -> mapOf(
            "timeframe" to candlestickPatternStrategy.currentTimeframe.name,
            "minConfidence" to candlestickPatternStrategy.minConfidence
        )
        else -> emptyMap()
    }

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
        configureVotingStrategy(weights)
        currentStrategy = votingStrategy
        logger.info { "🔄 Переключено на стратегию голосования: $weights" }
    }

    fun switchToConfirmationStrategy(requiredIndicators: List<String>) {
        configureConfirmationStrategy(requiredIndicators)
        currentStrategy = confirmationStrategy
        logger.info { "🔄 Переключено на стратегию подтверждения: $requiredIndicators" }
    }

    fun analyze(data: MarketData): Signal = currentStrategy.analyze(data)

    fun configureVotingStrategy(weights: Map<String, Int>) {
        votingStrategy.configure(StrategyConfiguration.Voting(weights))
    }

    fun configureConfirmationStrategy(requiredIndicators: List<String>) {
        confirmationStrategy.configure(StrategyConfiguration.Confirmation(requiredIndicators))
    }

    fun getVotingWeights(): Map<String, Int> = votingStrategy.getWeights()

    fun getConfirmationIndicators(): List<String> = confirmationStrategy.getRequiredIndicators()

    fun getExplanation(data: MarketData): String = currentStrategy.getExplanation(data)
}
