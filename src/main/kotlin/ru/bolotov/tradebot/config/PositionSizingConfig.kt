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

    var riskPerTrade: Double
        get() = settingsValue.get().riskPerTrade
        set(value) = update(riskPerTrade = value)

    var maxCapitalUsage: Double
        get() = settingsValue.get().maxCapitalUsage
        set(value) = update(maxCapitalUsage = value)

    var maxPositionSize: Long
        get() = settingsValue.get().maxPositionSize
        set(value) = update(maxPositionSize = value)

    var minPositionSize: Long
        get() = settingsValue.get().minPositionSize
        set(value) = update(minPositionSize = value)

    var maxPositions: Int
        get() = settingsValue.get().maxPositions
        set(value) = update(maxPositions = value)

    var brokerLimitUsage: Double
        get() = settingsValue.get().brokerLimitUsage
        set(value) = update(brokerLimitUsage = value)

    var minOrderCashBuffer: Long
        get() = settingsValue.get().minOrderCashBuffer
        set(value) = update(minOrderCashBuffer = value)

    @PostConstruct
    fun loadPersistedConfig() {
        persistenceService.loadConfig()?.let { saved ->
            val maxPositionSize = maxOf(saved.maxPositionSize, saved.minPositionSize)
            update(
                riskPerTrade = saved.riskPerTrade,
                maxCapitalUsage = saved.maxCapitalUsage,
                maxPositionSize = maxPositionSize,
                minPositionSize = saved.minPositionSize,
                maxPositions = saved.maxPositions,
                brokerLimitUsage = saved.brokerLimitUsage,
                minOrderCashBuffer = saved.minOrderCashBuffer
            )
            if (maxPositionSize != saved.maxPositionSize) {
                persist()
            }
        }
    }

    fun update(
        riskPerTrade: Double? = null,
        maxCapitalUsage: Double? = null,
        maxPositionSize: Long? = null,
        minPositionSize: Long? = null,
        maxPositions: Int? = null,
        brokerLimitUsage: Double? = null,
        minOrderCashBuffer: Long? = null
    ) {
        val current = settingsValue.get()
        val updated = current.copy(
            riskPerTrade = riskPerTrade ?: current.riskPerTrade,
            maxCapitalUsage = maxCapitalUsage ?: current.maxCapitalUsage,
            maxPositionSize = maxPositionSize ?: current.maxPositionSize,
            minPositionSize = minPositionSize ?: current.minPositionSize,
            maxPositions = maxPositions ?: current.maxPositions,
            brokerLimitUsage = brokerLimitUsage ?: current.brokerLimitUsage,
            minOrderCashBuffer = minOrderCashBuffer ?: current.minOrderCashBuffer
        )
        validate(updated)
        settingsValue.set(updated)
    }

    fun persist() {
        val settings = settingsValue.get()
        persistenceService.saveConfig(
            riskPerTrade = settings.riskPerTrade,
            maxCapitalUsage = settings.maxCapitalUsage,
            maxPositionSize = settings.maxPositionSize,
            minPositionSize = settings.minPositionSize,
            maxPositions = settings.maxPositions,
            brokerLimitUsage = settings.brokerLimitUsage,
            minOrderCashBuffer = settings.minOrderCashBuffer
        )
    }

    fun toMap(): Map<String, Any> {
        val settings = settingsValue.get()
        return mapOf(
            "riskPerTrade" to settings.riskPerTrade,
            "riskPerTradePercent" to "${"%.1f".format(settings.riskPerTrade * 100)}%",
            "maxCapitalUsage" to settings.maxCapitalUsage,
            "maxCapitalUsagePercent" to "${"%.0f".format(settings.maxCapitalUsage * 100)}%",
            "maxPositionSize" to settings.maxPositionSize,
            "minPositionSize" to settings.minPositionSize,
            "maxPositions" to settings.maxPositions,
            "brokerLimitUsage" to settings.brokerLimitUsage,
            "brokerLimitUsagePercent" to "${"%.0f".format(settings.brokerLimitUsage * 100)}%",
            "minOrderCashBuffer" to settings.minOrderCashBuffer
        )
    }

    private fun validate(settings: RiskSettings) {
        require(settings.riskPerTrade in 0.001..0.10) {
            "Риск на сделку должен быть от 0.1% до 10%"
        }
        require(settings.maxCapitalUsage in 0.10..0.95) {
            "Загрузка капитала должна быть от 10% до 95%"
        }
        require(settings.maxPositionSize in 1_000..1_000_000) {
            "Максимальный размер позиции должен быть от 1 000 до 1 000 000 ₽"
        }
        require(settings.minPositionSize in 100..100_000) {
            "Минимальный размер позиции должен быть от 100 до 100 000 ₽"
        }
        require(settings.minPositionSize <= settings.maxPositionSize) {
            "Минимальный размер позиции не может быть больше максимального"
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

    private data class RiskSettings(
        val riskPerTrade: Double,
        val maxCapitalUsage: Double,
        val maxPositionSize: Long,
        val minPositionSize: Long,
        val maxPositions: Int,
        val brokerLimitUsage: Double,
        val minOrderCashBuffer: Long
    ) {
        companion object {
            fun defaults() = RiskSettings(
                riskPerTrade = 0.02,
                maxCapitalUsage = 0.80,
                maxPositionSize = 100_000L,
                minPositionSize = 5_000L,
                maxPositions = 10,
                brokerLimitUsage = 0.95,
                minOrderCashBuffer = 100L
            )
        }
    }
}
