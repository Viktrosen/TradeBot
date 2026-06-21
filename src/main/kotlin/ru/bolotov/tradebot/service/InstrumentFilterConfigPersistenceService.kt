package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.InstrumentFilterConfigEntity
import ru.bolotov.tradebot.domain.repository.InstrumentFilterConfigRepository
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class InstrumentFilterConfigPersistenceService(
    private val repository: InstrumentFilterConfigRepository
) {
    fun loadConfig(): InstrumentFilterConfigEntity {
        return try {
            val entity = repository.findById("current").orElse(null)
            if (entity != null) {
                logger.info {
                    "📂 Загружена сохранённая конфигурация фильтров инструментов: " +
                            "minDailyVolume=${entity.minDailyVolume}, " +
                            "volatility=${entity.minVolatility}%..${entity.maxVolatility}%, " +
                            "maxCount=${entity.maxCount}"
                }
                entity
            } else {
                logger.info { "📂 Конфигурация фильтров инструментов не найдена, создаём по умолчанию" }
                saveConfig(100_000L, 3.0, 15.0, 10)
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка загрузки конфигурации фильтров инструментов, используем значения по умолчанию" }
            InstrumentFilterConfigEntity()
        }
    }

    fun saveConfig(
        minDailyVolume: Long,
        minVolatility: Double,
        maxVolatility: Double,
        maxCount: Int
    ): InstrumentFilterConfigEntity {
        val entity = InstrumentFilterConfigEntity(
            id = "current",
            minDailyVolume = minDailyVolume,
            minVolatility = minVolatility,
            maxVolatility = maxVolatility,
            maxCount = maxCount,
            updatedAt = Instant.now()
        )
        repository.save(entity)
        logger.info {
            "💾 Конфигурация фильтров инструментов сохранена в БД: " +
                    "minDailyVolume=$minDailyVolume, volatility=$minVolatility%..$maxVolatility%, maxCount=$maxCount"
        }
        return entity
    }
}
