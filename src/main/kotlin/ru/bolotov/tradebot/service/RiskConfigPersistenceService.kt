package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.RiskConfigEntity
import ru.bolotov.tradebot.domain.repository.RiskConfigRepository
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class RiskConfigPersistenceService(
    private val repository: RiskConfigRepository
    // Убираем PositionSizingConfig из конструктора!
) {

    // Загружаем конфиг и возвращаем его (не сохраняем в config)
    fun loadConfig(): RiskConfigEntity? {
        return try {
            val entity = repository.findById("current").orElse(null)

            if (entity != null) {
                logger.info { "📂 Загружена сохранённая конфигурация рисков" }
            } else {
                logger.info { "📂 Конфигурация рисков не найдена, создаём по умолчанию" }
                saveDefaultConfig()
            }
            entity
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка загрузки конфигурации рисков" }
            null
        }
    }

    fun saveConfig(
        riskPerTrade: Double,
        maxCapitalUsage: Double,
        maxPositionSize: Long,
        minPositionSize: Long,
        maxPositions: Int,
        brokerLimitUsage: Double,
        minOrderCashBuffer: Long,
        allowMinPositionSizeUpscale: Boolean
    ) {
        val entity = RiskConfigEntity(
            id = "current",
            riskPerTrade = riskPerTrade,
            maxCapitalUsage = maxCapitalUsage,
            maxPositionSize = maxPositionSize,
            minPositionSize = minPositionSize,
            maxPositions = maxPositions,
            brokerLimitUsage = brokerLimitUsage,
            minOrderCashBuffer = minOrderCashBuffer,
            allowMinPositionSizeUpscale = allowMinPositionSizeUpscale,
            updatedAt = Instant.now()
        )
        repository.save(entity)
        logger.info { "💾 Конфигурация рисков сохранена в БД" }
    }

    private fun saveDefaultConfig() {
        saveConfig(
            riskPerTrade = 0.02,
            maxCapitalUsage = 0.80,
            maxPositionSize = 100_000L,
            minPositionSize = 5_000L,
            maxPositions = 10,
            brokerLimitUsage = 0.95,
            minOrderCashBuffer = 100L,
            allowMinPositionSizeUpscale = false
        )
    }
}
