package ru.bolotov.tradebot.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import ru.bolotov.tradebot.service.RiskConfigPersistenceService
import java.util.concurrent.atomic.AtomicReference

@Component
class PositionSizingConfig(
    private val persistenceService: RiskConfigPersistenceService
) {
    private val settingsValue = AtomicReference(RiskSettings.defaults())

    val positionSizePercent: Double
        get() = settingsValue.get().positionSizePercent

    val stopLossPercent: Double
        get() = settingsValue.get().stopLossPercent

    val takeProfitPercent: Double
        get() = settingsValue.get().takeProfitPercent

    val maxCapitalUsage: Double
        get() = settingsValue.get().maxCapitalUsage

    val maxPositions: Int
        get() = settingsValue.get().maxPositions

    val brokerLimitUsage: Double
        get() = settingsValue.get().brokerLimitUsage

    val minOrderCashBuffer: Long
        get() = settingsValue.get().minOrderCashBuffer

    val shortTradingEnabled: Boolean
        get() = settingsValue.get().shortTradingEnabled

    @PostConstruct
    fun loadPersistedConfig() {
        persistenceService.loadConfig()?.let { saved ->
            update(
                positionSizePercent = saved.positionSizePercent,
                stopLossPercent = saved.stopLossPercent,
                takeProfitPercent = saved.takeProfitPercent,
                maxCapitalUsage = saved.maxCapitalUsage,
                maxPositions = saved.maxPositions,
                brokerLimitUsage = saved.brokerLimitUsage,
                minOrderCashBuffer = saved.minOrderCashBuffer,
                shortTradingEnabled = saved.shortTradingEnabled
            )
        }
    }

    fun update(
        positionSizePercent: Double? = null,
        stopLossPercent: Double? = null,
        takeProfitPercent: Double? = null,
        maxCapitalUsage: Double? = null,
        maxPositions: Int? = null,
        brokerLimitUsage: Double? = null,
        minOrderCashBuffer: Long? = null,
        shortTradingEnabled: Boolean? = null
    ) {
        settingsValue.set(preview(
            positionSizePercent = positionSizePercent,
            stopLossPercent = stopLossPercent,
            takeProfitPercent = takeProfitPercent,
            maxCapitalUsage = maxCapitalUsage,
            maxPositions = maxPositions,
            brokerLimitUsage = brokerLimitUsage,
            minOrderCashBuffer = minOrderCashBuffer,
            shortTradingEnabled = shortTradingEnabled
        ))
    }

    fun preview(
        positionSizePercent: Double? = null,
        stopLossPercent: Double? = null,
        takeProfitPercent: Double? = null,
        maxCapitalUsage: Double? = null,
        maxPositions: Int? = null,
        brokerLimitUsage: Double? = null,
        minOrderCashBuffer: Long? = null,
        shortTradingEnabled: Boolean? = null
    ): RiskSettings {
        val current = settingsValue.get()
        val updated = current.copy(
            positionSizePercent = positionSizePercent ?: current.positionSizePercent,
            stopLossPercent = stopLossPercent ?: current.stopLossPercent,
            takeProfitPercent = takeProfitPercent ?: current.takeProfitPercent,
            maxCapitalUsage = maxCapitalUsage ?: current.maxCapitalUsage,
            maxPositions = maxPositions ?: current.maxPositions,
            brokerLimitUsage = brokerLimitUsage ?: current.brokerLimitUsage,
            minOrderCashBuffer = minOrderCashBuffer ?: current.minOrderCashBuffer,
            shortTradingEnabled = shortTradingEnabled ?: current.shortTradingEnabled
        )
        validate(updated)
        return updated
    }

    fun apply(settings: RiskSettings) {
        validate(settings)
        settingsValue.set(settings)
    }

    fun persist() {
        val settings = settingsValue.get()
        persistenceService.saveConfig(
            positionSizePercent = settings.positionSizePercent,
            stopLossPercent = settings.stopLossPercent,
            takeProfitPercent = settings.takeProfitPercent,
            maxCapitalUsage = settings.maxCapitalUsage,
            maxPositions = settings.maxPositions,
            brokerLimitUsage = settings.brokerLimitUsage,
            minOrderCashBuffer = settings.minOrderCashBuffer,
            shortTradingEnabled = settings.shortTradingEnabled
        )
    }

    fun toMap(): Map<String, Any> {
        val settings = settingsValue.get()
        return mapOf(
            "positionSizePercent" to settings.positionSizePercent,
            "positionSizePercentDisplay" to settings.positionSizePercent.toPercentString(),
            "stopLossPercent" to settings.stopLossPercent,
            "stopLossPercentDisplay" to settings.stopLossPercent.toPercentString(),
            "takeProfitPercent" to settings.takeProfitPercent,
            "takeProfitPercentDisplay" to settings.takeProfitPercent.toPercentString(),
            "maxCapitalUsage" to settings.maxCapitalUsage,
            "maxCapitalUsagePercent" to settings.maxCapitalUsage.toPercentString(),
            "maxPositions" to settings.maxPositions,
            "brokerLimitUsage" to settings.brokerLimitUsage,
            "minOrderCashBuffer" to settings.minOrderCashBuffer,
            "shortTradingEnabled" to settings.shortTradingEnabled
        )
    }

    private fun validate(settings: RiskSettings) {
        require(settings.positionSizePercent in 0.01..0.80) {
            "Размер позиции должен быть от 1% до 80% капитала"
        }
        require(settings.stopLossPercent in 0.001..0.50) {
            "Стоп-лосс должен быть от 0.1% до 50%"
        }
        require(settings.takeProfitPercent in 0.001..5.00) {
            "Тейк-профит должен быть от 0.1% до 500%"
        }
        require(settings.maxCapitalUsage in 0.10..0.95) {
            "Загрузка капитала должна быть от 10% до 95%"
        }
        require(settings.maxPositions in 1..50) {
            "Количество позиций должно быть от 1 до 50"
        }
        require(settings.brokerLimitUsage in 0.10..1.00) {
            "Использование лимита брокера должно быть от 10% до 100%"
        }
        require(settings.minOrderCashBuffer in 0..100_000) {
            "Резерв денежных средств должен быть от 0 до 100 000 ₽"
        }
    }

    private fun Double.toPercentString(): String = "${"%.0f".format(this * 100)}%"

    data class RiskSettings(
        val positionSizePercent: Double,
        val stopLossPercent: Double,
        val takeProfitPercent: Double,
        val maxCapitalUsage: Double,
        val maxPositions: Int,
        val brokerLimitUsage: Double,
        val minOrderCashBuffer: Long,
        val shortTradingEnabled: Boolean
    ) {
        companion object {
            fun defaults() = RiskSettings(
                positionSizePercent = 0.05,
                stopLossPercent = 0.02,
                takeProfitPercent = 0.03,
                maxCapitalUsage = 0.80,
                maxPositions = 10,
                brokerLimitUsage = 0.95,
                minOrderCashBuffer = 100L,
                shortTradingEnabled = false
            )
        }
    }
}
