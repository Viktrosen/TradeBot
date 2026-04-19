package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.config.PositionSizingConfig
import ru.bolotov.tradebot.domain.model.RiskConfigEntity
import ru.bolotov.tradebot.domain.repository.RiskConfigRepository
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class RiskConfigPersistenceService(
    private val repository: RiskConfigRepository,
    private val config: PositionSizingConfig
) {

    @PostConstruct
    fun loadConfig() {
        try {
            val entity = repository.findById("current").orElse(null)

            if (entity != null) {
                config.riskPerTrade = entity.riskPerTrade
                config.maxCapitalUsage = entity.maxCapitalUsage
                config.maxPositionSize = entity.maxPositionSize
                config.minPositionSize = entity.minPositionSize
                config.maxPositions = entity.maxPositions

                logger.info { "📂 Загружена сохранённая конфигурация рисков: risk=${"%.1f".format(config.riskPerTrade * 100)}%, maxUsage=${"%.0f".format(config.maxCapitalUsage * 100)}%, maxPos=${config.maxPositions}" }
            } else {
                // Сохраняем значения по умолчанию
                saveConfig()
                logger.info { "📂 Создана новая конфигурация рисков со значениями по умолчанию" }
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка загрузки конфигурации рисков, используются значения по умолчанию" }
        }
    }

    fun saveConfig() {
        val entity = RiskConfigEntity(
            id = "current",
            riskPerTrade = config.riskPerTrade,
            maxCapitalUsage = config.maxCapitalUsage,
            maxPositionSize = config.maxPositionSize,
            minPositionSize = config.minPositionSize,
            maxPositions = config.maxPositions,
            updatedAt = Instant.now()
        )
        repository.save(entity)
        logger.info { "💾 Конфигурация рисков сохранена в БД" }
    }
}