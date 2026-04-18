package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.StrategyConfig
import ru.bolotov.tradebot.domain.model.StrategyType
import ru.bolotov.tradebot.domain.repository.StrategyConfigRepository
import java.time.Instant

@Service
class StrategyConfigPersistenceService(
    private val strategyConfigRepository: StrategyConfigRepository,
    private val objectMapper: ObjectMapper
) {

    fun saveSimpleStrategy(strategyName: String) {
        val type = when (strategyName.lowercase()) {
            "ema", "cross_ema" -> StrategyType.SIMPLE_EMA
            "rsi" -> StrategyType.SIMPLE_RSI
            else -> return
        }

        val config = objectMapper.writeValueAsString(mapOf("name" to strategyName))
        val entity = StrategyConfig(
            id = "current_simple",
            type = type,
            config = config,
            updatedAt = Instant.now()
        )
        strategyConfigRepository.save(entity)
        logger.info { "💾 Сохранена простая стратегия: $strategyName" }
    }

    fun saveCompositeStrategy(weights: Map<String, Int>) {
        val config = objectMapper.writeValueAsString(weights)
        val entity = StrategyConfig(
            id = "current_composite",
            type = StrategyType.COMPOSITE,
            config = config,
            updatedAt = Instant.now()
        )
        strategyConfigRepository.save(entity)
        logger.info { "💾 Сохранена комбинированная стратегия с весами: $weights" }
    }

    fun loadLastConfiguration(): LoadedConfig? {
        val composite = strategyConfigRepository.findById("current_composite").orElse(null)
        val simple = strategyConfigRepository.findById("current_simple").orElse(null)

        return when {
            composite != null && composite.updatedAt.isAfter(simple?.updatedAt ?: Instant.MIN) -> {
                val weights = objectMapper.readValue(composite.config, Map::class.java) as Map<String, Int>
                LoadedConfig.Composite(weights)
            }
            simple != null -> {
                val configMap = objectMapper.readValue(simple.config, Map::class.java) as Map<String, String>
                LoadedConfig.Simple(configMap["name"] ?: "ema")
            }
            else -> null
        }
    }
}

sealed class LoadedConfig {
    data class Simple(val name: String) : LoadedConfig()
    data class Composite(val weights: Map<String, Int>) : LoadedConfig()
}