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
) {

    fun loadConfig(): RiskConfigEntity? = try {
        repository.findById(CURRENT_CONFIG_ID).orElseGet(::saveDefaultConfig)
    } catch (error: Exception) {
        logger.error(error) { "Не удалось загрузить конфигурацию рисков" }
        null
    }

    fun saveConfig(
        positionSizePercent: Double,
        stopLossPercent: Double,
        takeProfitPercent: Double,
        maxCapitalUsage: Double,
        maxPositions: Int,
        brokerLimitUsage: Double,
        minOrderCashBuffer: Long,
        shortTradingEnabled: Boolean
    ) {
        repository.save(
            RiskConfigEntity(
                id = CURRENT_CONFIG_ID,
                positionSizePercent = positionSizePercent,
                stopLossPercent = stopLossPercent,
                takeProfitPercent = takeProfitPercent,
                maxCapitalUsage = maxCapitalUsage,
                maxPositions = maxPositions,
                brokerLimitUsage = brokerLimitUsage,
                minOrderCashBuffer = minOrderCashBuffer,
                shortTradingEnabled = shortTradingEnabled,
                updatedAt = Instant.now()
            )
        )
    }

    private fun saveDefaultConfig(): RiskConfigEntity = repository.save(
        RiskConfigEntity(id = CURRENT_CONFIG_ID)
    )

    private companion object {
        const val CURRENT_CONFIG_ID = "current"
    }
}
