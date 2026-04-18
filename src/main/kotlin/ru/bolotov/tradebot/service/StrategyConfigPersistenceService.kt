package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.StrategyConfig
import ru.bolotov.tradebot.domain.model.StrategyType
import ru.bolotov.tradebot.domain.repository.StrategyConfigRepository
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class StrategyConfigPersistenceService(
    private val strategyConfigRepository: StrategyConfigRepository,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private const val CURRENT_STRATEGY_ID = "current_strategy"
    }

    fun saveSimpleStrategy(strategyName: String) {
        val type = when (strategyName.lowercase()) {
            "ema", "cross_ema" -> StrategyType.SIMPLE_EMA
            "rsi" -> StrategyType.SIMPLE_RSI
            else -> {
                logger.warn { "⚠️ Неизвестная стратегия: $strategyName" }
                return
            }
        }

        val config = objectMapper.writeValueAsString(
            mapOf(
                "type" to "simple",
                "name" to strategyName
            )
        )
        val entity = StrategyConfig(
            id = CURRENT_STRATEGY_ID,
            type = type,
            config = config,
            updatedAt = Instant.now()
        )
        strategyConfigRepository.save(entity)
        logger.info { "💾 Сохранена простая стратегия: $strategyName" }
    }

    fun saveVotingStrategy(weights: Map<String, Int>) {
        val config = objectMapper.writeValueAsString(
            mapOf(
                "type" to "voting",
                "weights" to weights
            )
        )
        val entity = StrategyConfig(
            id = CURRENT_STRATEGY_ID,
            type = StrategyType.COMPOSITE,
            config = config,
            updatedAt = Instant.now()
        )
        strategyConfigRepository.save(entity)
        logger.info { "💾 Сохранена стратегия голосования: $weights" }
    }

    fun saveConfirmationStrategy(requiredIndicators: List<String>) {
        val config = objectMapper.writeValueAsString(
            mapOf(
                "type" to "confirmation",
                "indicators" to requiredIndicators
            )
        )
        val entity = StrategyConfig(
            id = CURRENT_STRATEGY_ID,
            type = StrategyType.COMPOSITE,
            config = config,
            updatedAt = Instant.now()
        )
        strategyConfigRepository.save(entity)
        logger.info { "💾 Сохранена стратегия подтверждения: $requiredIndicators" }
    }

    fun loadLastConfiguration(): LoadedConfig? {
        val entity = strategyConfigRepository.findById(CURRENT_STRATEGY_ID).orElse(null)

        if (entity == null) {
            logger.info { "📂 Нет сохранённой конфигурации в БД" }
            return null
        }

        return try {
            val configMap = objectMapper.readValue<Map<String, Any>>(entity.config)
            val type = configMap["type"] as? String ?: "simple"

            when (type) {
                "simple" -> {
                    val name = configMap["name"] as? String ?: "ema"
                    LoadedConfig.Simple(name)
                }
                "voting" -> {
                    @Suppress("UNCHECKED_CAST")
                    val weights = configMap["weights"] as? Map<String, Int> ?: emptyMap()
                    LoadedConfig.Voting(weights)
                }
                "confirmation" -> {
                    @Suppress("UNCHECKED_CAST")
                    val indicators = configMap["indicators"] as? List<String> ?: listOf("EMA", "RSI")
                    LoadedConfig.Confirmation(indicators)
                }
                else -> {
                    logger.warn { "⚠️ Неизвестный тип конфигурации: $type" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка парсинга конфигурации стратегии" }
            null
        }
    }

    fun getCurrentConfig(): Map<String, Any>? {
        val entity = strategyConfigRepository.findById(CURRENT_STRATEGY_ID).orElse(null) ?: return null

        return try {
            objectMapper.readValue(entity.config)
        } catch (e: Exception) {
            null
        }
    }
}

sealed class LoadedConfig {
    data class Simple(val name: String) : LoadedConfig()
    data class Voting(val weights: Map<String, Int>) : LoadedConfig()
    data class Confirmation(val indicators: List<String>) : LoadedConfig()
}