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

    suspend fun loadLastConfiguration() {
        try {
            when (val config = strategyConfigPersistenceService.loadLastConfiguration()) {
                is LoadedConfig.Simple -> {
                    strategyManager.switchToSimpleStrategy(config.name)
                    strategyConfigurationLogger.info { "Загружена сохранённая простая стратегия: ${config.name}" }
                }

                is LoadedConfig.Voting -> {
                    strategyManager.switchToVotingStrategy(config.weights)
                    strategyConfigurationLogger.info { "Загружена сохранённая стратегия голосования: ${config.weights}" }
                }

                is LoadedConfig.Confirmation -> {
                    strategyManager.switchToConfirmationStrategy(config.indicators)
                    strategyConfigurationLogger.info { "Загружена сохранённая стратегия подтверждения: ${config.indicators}" }
                }

                is LoadedConfig.Candlestick -> {
                    val timeframe = runCatching {
                        CandlestickPatternStrategy.CandleTimeframe.valueOf(config.timeframe)
                    }.getOrDefault(CandlestickPatternStrategy.CandleTimeframe.M5)
                    val minConfidence = config.minConfidence.coerceIn(0.75, 1.0)

                    candlestickPatternStrategy.setTimeframe(timeframe)
                    candlestickPatternStrategy.configureMinConfidence(minConfidence)
                    strategyManager.switchToCandlestickStrategy()

                    strategyConfigurationLogger.info {
                        "Загружена сохранённая свечная стратегия: ${config.timeframe}, уверенность=$minConfidence"
                    }
                }

                null -> {
                    strategyManager.switchToSimpleStrategy("ema")
                    strategyConfigurationLogger.info {
                        "Нет сохранённой конфигурации, используется стратегия по умолчанию: ema"
                    }
                }
            }
        } catch (e: Exception) {
            strategyConfigurationLogger.error(e) {
                "Ошибка загрузки конфигурации стратегии, используется стратегия по умолчанию"
            }
            strategyManager.switchToSimpleStrategy("ema")
        }
    }

    fun switchToSimpleStrategy(strategyName: String) {
        strategyManager.switchToSimpleStrategy(strategyName)
        strategyConfigPersistenceService.saveSimpleStrategy(strategyName)
        strategyConfigurationLogger.info { "Стратегия переключена на: $strategyName" }
    }

    fun switchToVotingStrategy(weights: Map<String, Int>) {
        strategyManager.switchToVotingStrategy(weights)
        strategyConfigPersistenceService.saveVotingStrategy(weights)
        strategyConfigurationLogger.info { "Стратегия переключена на голосование: $weights" }
    }

    fun switchToConfirmationStrategy(requiredIndicators: List<String>) {
        strategyManager.switchToConfirmationStrategy(requiredIndicators)
        strategyConfigPersistenceService.saveConfirmationStrategy(requiredIndicators)
        strategyConfigurationLogger.info { "Стратегия переключена на подтверждение: $requiredIndicators" }
    }

    fun switchToCandlestickStrategy(
        timeframe: CandlestickPatternStrategy.CandleTimeframe,
        minConfidence: Double
    ) {
        candlestickPatternStrategy.setTimeframe(timeframe)
        candlestickPatternStrategy.configureMinConfidence(minConfidence)
        strategyManager.switchToCandlestickStrategy()
        strategyConfigPersistenceService.saveCandlestickStrategy(
            timeframe = timeframe.name,
            minConfidence = minConfidence
        )
        strategyConfigurationLogger.info {
            "Стратегия переключена на свечные паттерны: таймфрейм=${timeframe.name}, уверенность=$minConfidence"
        }
    }
}

