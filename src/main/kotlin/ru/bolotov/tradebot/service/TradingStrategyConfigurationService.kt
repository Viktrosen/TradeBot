package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.strategy.CandlestickPatternStrategy
import ru.bolotov.tradebot.strategy.StrategyManager

private val strategyConfigurationLogger = KotlinLogging.logger {}

@Service
class TradingStrategyConfigurationService(
    private val strategyManager: StrategyManager,
    private val candlestickPatternStrategy: CandlestickPatternStrategy,
    private val strategyConfigPersistenceService: StrategyConfigPersistenceService
) {

    suspend fun loadConfigurations() {
        try {
            val configurations = strategyConfigPersistenceService.loadConfigurations()
            configurations.candlestick?.let(::applyCandlestickConfiguration)
            configurations.voting?.let { config -> strategyManager.configureVotingStrategy(config.weights) }
            configurations.confirmation?.let { config -> strategyManager.configureConfirmationStrategy(config.indicators) }
            strategyConfigurationLogger.info { "Загружены настройки стратегий для автоматического выбора" }
        } catch (e: Exception) {
            strategyConfigurationLogger.error(e) { "Ошибка загрузки конфигураций стратегий" }
        }
    }

    fun switchToSimpleStrategy(strategyName: String) {
        strategyManager.switchToSimpleStrategy(strategyName)
        strategyConfigPersistenceService.saveSimpleStrategy(strategyName)
        strategyConfigurationLogger.info { "Стратегия переключена на: $strategyName" }
    }

    fun switchToVotingStrategy(weights: Map<String, Int>) {
        strategyManager.configureVotingStrategy(weights)
        strategyConfigPersistenceService.saveVotingStrategy(weights)
        strategyConfigurationLogger.info { "Стратегия переключена на голосование: $weights" }
    }

    fun switchToConfirmationStrategy(requiredIndicators: List<String>) {
        strategyManager.configureConfirmationStrategy(requiredIndicators)
        strategyConfigPersistenceService.saveConfirmationStrategy(requiredIndicators)
        strategyConfigurationLogger.info { "Стратегия переключена на подтверждение: $requiredIndicators" }
    }

    fun switchToCandlestickStrategy(
        timeframe: CandlestickPatternStrategy.CandleTimeframe,
        minConfidence: Double
    ) {
        applyCandlestickConfiguration(LoadedConfig.Candlestick(timeframe.name, minConfidence))
        strategyConfigPersistenceService.saveCandlestickStrategy(
            timeframe = timeframe.name,
            minConfidence = minConfidence
        )
        strategyConfigurationLogger.info {
            "Стратегия переключена на свечные паттерны: таймфрейм=${timeframe.name}, уверенность=$minConfidence"
        }
    }

    fun getConfigurations(): Map<String, Any> = mapOf(
        "candlestick" to mapOf(
            "timeframe" to candlestickPatternStrategy.currentTimeframe.name,
            "minConfidence" to candlestickPatternStrategy.minConfidence
        ),
        "voting" to mapOf("weights" to strategyManager.getVotingWeights()),
        "confirmation" to mapOf("indicators" to strategyManager.getConfirmationIndicators())
    )

    private fun applyCandlestickConfiguration(config: LoadedConfig.Candlestick) {
        val timeframe = runCatching {
            CandlestickPatternStrategy.CandleTimeframe.valueOf(config.timeframe)
        }.getOrDefault(CandlestickPatternStrategy.CandleTimeframe.M5)
        candlestickPatternStrategy.setTimeframe(timeframe)
        candlestickPatternStrategy.configureMinConfidence(config.minConfidence.coerceIn(0.85, 1.0))
    }
}
