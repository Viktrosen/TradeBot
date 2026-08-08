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
    data class Simple(val name: String) : LoadedConfig()  // "ema", "rsi"
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
    fun loadLastConfiguration(): LoadedConfig? {
        return try {
            // Пробуем загрузить по порядку приоритета
            val candlestickConfig = configRepository.findByType(StrategyType.CANDLESTICK)
            if (candlestickConfig != null) {
                return parseCandlestickConfig(candlestickConfig.config)
            }

            val confirmationConfig = configRepository.findByType(StrategyType.CONFIRMATION)
            if (confirmationConfig != null) {
                return parseConfirmationConfig(confirmationConfig.config)
            }

            val votingConfig = configRepository.findByType(StrategyType.VOTING)
            if (votingConfig != null) {
                return parseVotingConfig(votingConfig.config)
            }

            val simpleEmaConfig = configRepository.findByType(StrategyType.SIMPLE_EMA)
            if (simpleEmaConfig != null) {
                return LoadedConfig.Simple("ema")
            }

            val simpleRsiConfig = configRepository.findByType(StrategyType.SIMPLE_RSI)
            if (simpleRsiConfig != null) {
                return LoadedConfig.Simple("rsi")
            }

            val compositeConfig = configRepository.findByType(StrategyType.COMPOSITE)
            if (compositeConfig != null) {
                return parseCompositeConfig(compositeConfig.config)
            }

            null
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка загрузки конфигурации стратегии" }
            null
        }
    }

    private fun parseCandlestickConfig(configJson: String): LoadedConfig.Candlestick {
        val map = objectMapper.readValue<Map<String, Any>>(configJson)
        return LoadedConfig.Candlestick(
            timeframe = map["timeframe"] as? String ?: "M5",
            minConfidence = (map["minConfidence"] as? Number)?.toDouble() ?: 0.70
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

    private fun parseCompositeConfig(configJson: String): LoadedConfig.Simple {
        // COMPOSITE сохраняем как Simple для обратной совместимости
        return LoadedConfig.Simple("ema")
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
        val existingConfig = configRepository.findById("current").orElse(null)

        if (existingConfig != null) {
            existingConfig.type = strategyType
            existingConfig.config = configJson
            existingConfig.updatedAt = Instant.now()
            configRepository.save(existingConfig)
        } else {
            val newConfig = StrategyConfig(
                id = "current",
                type = strategyType,
                config = configJson,
                updatedAt = Instant.now()
            )
            configRepository.save(newConfig)
        }
    }

    /**
     * Получение текущей конфигурации в виде Map (для API)
     */
    @Transactional(readOnly = true)
    fun getCurrentConfig(): Map<String, Any?> {
        return try {
            val config = configRepository.findById("current").orElse(null) ?: return mapOf("exists" to false)

            val configData = objectMapper.readValue<Map<String, Any>>(config.config)

            when (config.type) {
                StrategyType.CANDLESTICK -> mapOf(
                    "type" to "CANDLESTICK",
                    "timeframe" to configData["timeframe"],
                    "minConfidence" to configData["minConfidence"],
                    "updatedAt" to config.updatedAt.toString()
                )
                StrategyType.CONFIRMATION -> mapOf(
                    "type" to "CONFIRMATION",
                    "indicators" to configData["indicators"],
                    "updatedAt" to config.updatedAt.toString()
                )
                StrategyType.VOTING -> mapOf(
                    "type" to "VOTING",
                    "weights" to configData["weights"],
                    "updatedAt" to config.updatedAt.toString()
                )
                StrategyType.SIMPLE_EMA -> mapOf(
                    "type" to "SIMPLE",
                    "name" to "ema",
                    "updatedAt" to config.updatedAt.toString()
                )
                StrategyType.SIMPLE_RSI -> mapOf(
                    "type" to "SIMPLE",
                    "name" to "rsi",
                    "updatedAt" to config.updatedAt.toString()
                )
                StrategyType.COMPOSITE -> mapOf(
                    "type" to "COMPOSITE",
                    "updatedAt" to config.updatedAt.toString()
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения текущей конфигурации" }
            mapOf("error" to e.message)
        }
    }
}
