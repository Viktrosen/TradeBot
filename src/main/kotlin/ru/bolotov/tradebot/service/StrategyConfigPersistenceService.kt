package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant

private val logger = KotlinLogging.logger {}

// Sealed class для типов загруженной конфигурации
sealed class LoadedConfig {
    data class Simple(val name: String) : LoadedConfig()
    data class Voting(val weights: Map<String, Int>) : LoadedConfig()
    data class Confirmation(val indicators: List<String>) : LoadedConfig()
    data class Candlestick(val timeframe: String, val minConfidence: Double) : LoadedConfig()
}

// Data classes для сериализации в JSON
data class StrategyConfigWrapper(
    var simpleConfig: StrategyConfigData? = null,
    var votingConfig: StrategyConfigData? = null,
    var confirmationConfig: StrategyConfigData? = null,
    var candlestickConfig: StrategyConfigData? = null
)

data class StrategyConfigData(
    val type: String,
    val name: String? = null,
    val weights: Map<String, Int>? = null,
    val indicators: List<String>? = null,
    val timeframe: String? = null,
    val minConfidence: Double? = null,
    val updatedAt: String
)

@Service
class StrategyConfigPersistenceService(
    private val objectMapper: ObjectMapper
) {

    private val configFile = File("strategy_config.json")

    init {
        objectMapper.registerKotlinModule()
    }

    /**
     * Приватная функция для сохранения конфигурации
     * @param updateBlock функция, которая получает текущий wrapper и обновляет его
     */
    private fun saveConfig(updateBlock: StrategyConfigWrapper.() -> Unit) {
        try {
            val existingConfig = if (configFile.exists()) {
                objectMapper.readValue<StrategyConfigWrapper>(configFile)
            } else {
                StrategyConfigWrapper()
            }

            // Применяем обновления
            existingConfig.updateBlock()

            // Сохраняем в файл
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, existingConfig)
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка сохранения конфигурации" }
            throw e
        }
    }

    /**
     * Загрузка последней сохранённой конфигурации стратегии
     */
    fun loadLastConfiguration(): LoadedConfig? {
        return try {
            if (!configFile.exists()) {
                logger.info { "Файл конфигурации не найден, используем стратегию по умолчанию" }
                return null
            }

            val wrapper = objectMapper.readValue<StrategyConfigWrapper>(configFile)

            // Сохраняем значения в локальные переменные
            val candlestickConfig = wrapper.candlestickConfig
            val confirmationConfig = wrapper.confirmationConfig
            val votingConfig = wrapper.votingConfig
            val simpleConfig = wrapper.simpleConfig

            when {
                candlestickConfig != null -> {
                    logger.info { "📂 Загружена сохранённая свечная стратегия: ${candlestickConfig.timeframe}" }
                    LoadedConfig.Candlestick(
                        timeframe = candlestickConfig.timeframe ?: "M5",
                        minConfidence = candlestickConfig.minConfidence ?: 0.6
                    )
                }
                confirmationConfig != null -> {
                    logger.info { "📂 Загружена сохранённая стратегия подтверждения: ${confirmationConfig.indicators}" }
                    LoadedConfig.Confirmation(
                        indicators = confirmationConfig.indicators ?: listOf("EMA", "RSI", "MACD", "BB")
                    )
                }
                votingConfig != null -> {
                    logger.info { "📂 Загружена сохранённая стратегия голосования: ${votingConfig.weights}" }
                    LoadedConfig.Voting(
                        weights = votingConfig.weights ?: emptyMap()
                    )
                }
                simpleConfig != null -> {
                    logger.info { "📂 Загружена сохранённая простая стратегия: ${simpleConfig.name}" }
                    LoadedConfig.Simple(
                        name = simpleConfig.name ?: "ema"
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка загрузки конфигурации стратегии" }
            null
        }
    }

    /**
     * Сохранение простой стратегии (EMA, RSI, MACD)
     */
    fun saveSimpleStrategy(name: String) {
        val config = StrategyConfigData(
            type = "SIMPLE",
            name = name,
            updatedAt = Instant.now().toString()
        )

        saveConfig {
            simpleConfig = config
            votingConfig = null
            confirmationConfig = null
            candlestickConfig = null
        }

        logger.info { "💾 Сохранена простая стратегия: $name" }
    }

    /**
     * Сохранение стратегии голосования
     */
    fun saveVotingStrategy(weights: Map<String, Int>) {
        val config = StrategyConfigData(
            type = "VOTING",
            weights = weights,
            updatedAt = Instant.now().toString()
        )

        saveConfig {
            votingConfig = config
            simpleConfig = null
            confirmationConfig = null
            candlestickConfig = null
        }

        logger.info { "💾 Сохранена стратегия голосования: $weights" }
    }

    /**
     * Сохранение стратегии подтверждения (EMA + RSI + MACD + BB)
     */
    fun saveConfirmationStrategy(indicators: List<String>) {
        val config = StrategyConfigData(
            type = "CONFIRMATION",
            indicators = indicators,
            updatedAt = Instant.now().toString()
        )

        saveConfig {
            confirmationConfig = config
            simpleConfig = null
            votingConfig = null
            candlestickConfig = null
        }

        logger.info { "💾 Сохранена стратегия подтверждения: $indicators" }
    }

    /**
     * Сохранение свечной стратегии (Candlestick Patterns)
     */
    fun saveCandlestickStrategy(timeframe: String, minConfidence: Double) {
        val config = StrategyConfigData(
            type = "CANDLESTICK",
            timeframe = timeframe,
            minConfidence = minConfidence,
            updatedAt = Instant.now().toString()
        )

        saveConfig {
            candlestickConfig = config
            simpleConfig = null
            votingConfig = null
            confirmationConfig = null
        }

        logger.info { "💾 Сохранена свечная стратегия: таймфрейм=$timeframe, уверенность=$minConfidence" }
    }

    /**
     * Получение текущей конфигурации в виде Map (для API)
     */
    fun getCurrentConfig(): Map<String, Any?> {
        return try {
            if (!configFile.exists()) {
                return mapOf("exists" to false)
            }

            val wrapper = objectMapper.readValue<StrategyConfigWrapper>(configFile)

            // Сохраняем значения в локальные переменные
            val candlestickConfig = wrapper.candlestickConfig
            val confirmationConfig = wrapper.confirmationConfig
            val votingConfig = wrapper.votingConfig
            val simpleConfig = wrapper.simpleConfig

            when {
                candlestickConfig != null -> mapOf(
                    "type" to "CANDLESTICK",
                    "timeframe" to candlestickConfig.timeframe,
                    "minConfidence" to candlestickConfig.minConfidence,
                    "updatedAt" to candlestickConfig.updatedAt
                )
                confirmationConfig != null -> mapOf(
                    "type" to "CONFIRMATION",
                    "indicators" to confirmationConfig.indicators,
                    "updatedAt" to confirmationConfig.updatedAt
                )
                votingConfig != null -> mapOf(
                    "type" to "VOTING",
                    "weights" to votingConfig.weights,
                    "updatedAt" to votingConfig.updatedAt
                )
                simpleConfig != null -> mapOf(
                    "type" to "SIMPLE",
                    "name" to simpleConfig.name,
                    "updatedAt" to simpleConfig.updatedAt
                )
                else -> mapOf("type" to "NONE")
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения текущей конфигурации" }
            mapOf("error" to e.message)
        }
    }

    /**
     * Сброс конфигурации (удаление файла)
     */
    fun resetConfig(): Boolean {
        return try {
            if (configFile.exists()) {
                configFile.delete()
                logger.info { "🗑️ Конфигурация стратегии сброшена" }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка сброса конфигурации" }
            false
        }
    }
}