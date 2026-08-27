package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.bolotov.tradebot.domain.model.StrategyConfig
import ru.bolotov.tradebot.domain.model.StrategyType
import ru.bolotov.tradebot.domain.repository.StrategyConfigRepository
import java.time.Instant

private val logger = KotlinLogging.logger {}

// Sealed class для типов загруженной конфигурации
sealed class LoadedConfig {
    data class Voting(val weights: Map<String, Int>) : LoadedConfig()
    data class Confirmation(val indicators: List<String>) : LoadedConfig()
    data class Candlestick(val timeframe: String, val minConfidence: Double) : LoadedConfig()
}

@Service
class StrategyConfigPersistenceService(
    private val objectMapper: ObjectMapper,
    private val configRepository: StrategyConfigRepository
) {

    init {
        objectMapper.registerKotlinModule()
    }

    /**
     * Загрузка последней сохранённой конфигурации стратегии
     */
    @Transactional(readOnly = true)
    fun loadConfigurations(): StrategyConfigurations {
        return try {
            StrategyConfigurations(
                candlestick = configRepository.findByType(StrategyType.CANDLESTICK)
                    ?.let { config -> parseCandlestickConfig(config.config) },
                voting = configRepository.findByType(StrategyType.VOTING)
                    ?.let { config -> parseVotingConfig(config.config) },
                confirmation = configRepository.findByType(StrategyType.CONFIRMATION)
                    ?.let { config -> parseConfirmationConfig(config.config) }
            )
        } catch (e: Exception) {
            logger.error(e) { "Ошибка загрузки конфигураций стратегий" }
            StrategyConfigurations()
        }
    }

    private fun parseCandlestickConfig(configJson: String): LoadedConfig.Candlestick {
        val map = objectMapper.readValue<Map<String, Any>>(configJson)
        return LoadedConfig.Candlestick(
            timeframe = map["timeframe"] as? String ?: "M5",
            minConfidence = (map["minConfidence"] as? Number)?.toDouble() ?: 0.85
        )
    }

    private fun parseConfirmationConfig(configJson: String): LoadedConfig.Confirmation {
        val map = objectMapper.readValue<Map<String, Any>>(configJson)
        @Suppress("UNCHECKED_CAST")
        val indicators = map["indicators"] as? List<String> ?: listOf("EMA", "RSI", "MACD", "BB")
        return LoadedConfig.Confirmation(indicators)
    }

    private fun parseVotingConfig(configJson: String): LoadedConfig.Voting {
        val map = objectMapper.readValue<Map<String, Any>>(configJson)
        @Suppress("UNCHECKED_CAST")
        val weights = map["weights"] as? Map<String, Int> ?: mapOf("EMA" to 1, "RSI" to 1, "MACD" to 1)
        return LoadedConfig.Voting(weights)
    }

    /**
     * Сохранение простой стратегии (EMA)
     */
    @Transactional
    fun saveSimpleStrategy(name: String) {
        val strategyType = when (name.lowercase()) {
            "ema" -> StrategyType.SIMPLE_EMA
            "rsi" -> StrategyType.SIMPLE_RSI
            else -> StrategyType.SIMPLE_EMA
        }

        saveConfig(strategyType, emptyMap<String, Any>())
        logger.info { "💾 Сохранена простая стратегия: $name" }
    }

    /**
     * Сохранение стратегии голосования
     */
    @Transactional
    fun saveVotingStrategy(weights: Map<String, Int>) {
        val config = mapOf("weights" to weights)
        saveConfig(StrategyType.VOTING, config)
        logger.info { "💾 Сохранена стратегия голосования: $weights" }
    }

    /**
     * Сохранение стратегии подтверждения
     */
    @Transactional
    fun saveConfirmationStrategy(indicators: List<String>) {
        val config = mapOf("indicators" to indicators)
        saveConfig(StrategyType.CONFIRMATION, config)
        logger.info { "💾 Сохранена стратегия подтверждения: $indicators" }
    }

    /**
     * Сохранение свечной стратегии
     */
    @Transactional
    fun saveCandlestickStrategy(timeframe: String, minConfidence: Double) {
        val config = mapOf(
            "timeframe" to timeframe,
            "minConfidence" to minConfidence
        )
        saveConfig(StrategyType.CANDLESTICK, config)
        logger.info { "💾 Сохранена свечная стратегия: таймфрейм=$timeframe, уверенность=$minConfidence" }
    }

    /**
     * Приватный метод сохранения конфигурации
     */
    private fun saveConfig(strategyType: StrategyType, configData: Map<String, Any>) {
        val configJson = objectMapper.writeValueAsString(configData)
        val configId = strategyType.name.lowercase()
        val existingConfig = configRepository.findById(configId).orElse(null)

        if (existingConfig != null) {
            existingConfig.type = strategyType
            existingConfig.config = configJson
            existingConfig.updatedAt = Instant.now()
            configRepository.save(existingConfig)
        } else {
            val newConfig = StrategyConfig(
                id = configId,
                type = strategyType,
                config = configJson,
                updatedAt = Instant.now()
            )
            configRepository.save(newConfig)
        }
    }

}

data class StrategyConfigurations(
    val candlestick: LoadedConfig.Candlestick? = null,
    val voting: LoadedConfig.Voting? = null,
    val confirmation: LoadedConfig.Confirmation? = null
)
