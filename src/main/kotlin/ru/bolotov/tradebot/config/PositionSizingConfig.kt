package ru.bolotov.tradebot.config

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import ru.bolotov.tradebot.service.RiskConfigPersistenceService
import java.util.concurrent.atomic.AtomicReference

@Component
class PositionSizingConfig(
    private val persistenceService: RiskConfigPersistenceService
) {

    private val _riskPerTrade = AtomicReference(0.02)
    private val _maxCapitalUsage = AtomicReference(0.80)
    private val _maxPositionSize = AtomicReference(100_000L)
    private val _minPositionSize = AtomicReference(5_000L)
    private val _maxPositions = AtomicReference(10)
    private val _brokerLimitUsage = AtomicReference(0.95)
    private val _minOrderCashBuffer = AtomicReference(100L)
    private val _allowMinPositionSizeUpscale = AtomicReference(false)

    var riskPerTrade: Double
        get() = _riskPerTrade.get()
        set(value) {
            require(value in 0.001..0.10) { "Риск на сделку должен быть от 0.1% до 10%" }
            _riskPerTrade.set(value)
        }

    var maxCapitalUsage: Double
        get() = _maxCapitalUsage.get()
        set(value) {
            require(value in 0.10..0.95) { "Загрузка капитала должна быть от 10% до 95%" }
            _maxCapitalUsage.set(value)
        }

    var maxPositionSize: Long
        get() = _maxPositionSize.get()
        set(value) {
            require(value in 1_000..1_000_000) { "Макс. размер позиции от 1 000 до 1 000 000 ₽" }
            _maxPositionSize.set(value)
        }

    var minPositionSize: Long
        get() = _minPositionSize.get()
        set(value) {
            require(value in 100..100_000) { "Мин. размер позиции от 100 до 100 000 ₽" }
            require(value <= maxPositionSize) { "Мин. размер не может быть больше макс." }
            _minPositionSize.set(value)
        }

    var maxPositions: Int
        get() = _maxPositions.get()
        set(value) {
            require(value in 1..50) { "Количество позиций от 1 до 50" }
            _maxPositions.set(value)
        }

    var brokerLimitUsage: Double
        get() = _brokerLimitUsage.get()
        set(value) {
            require(value in 0.10..1.00) { "Broker limit usage must be between 10% and 100%" }
            _brokerLimitUsage.set(value)
        }

    var minOrderCashBuffer: Long
        get() = _minOrderCashBuffer.get()
        set(value) {
            require(value in 0..100_000) { "Cash buffer must be between 0 and 100 000" }
            _minOrderCashBuffer.set(value)
        }

    var allowMinPositionSizeUpscale: Boolean
        get() = _allowMinPositionSizeUpscale.get()
        set(value) {
            _allowMinPositionSizeUpscale.set(value)
        }

    @PostConstruct
    fun init() {
        val saved = persistenceService.loadConfig()
        if (saved != null) {
            _riskPerTrade.set(saved.riskPerTrade)
            _maxCapitalUsage.set(saved.maxCapitalUsage)
            _maxPositionSize.set(saved.maxPositionSize)
            _minPositionSize.set(saved.minPositionSize)
            _maxPositions.set(saved.maxPositions)
            _brokerLimitUsage.set(saved.brokerLimitUsage)
            _minOrderCashBuffer.set(saved.minOrderCashBuffer)
            _allowMinPositionSizeUpscale.set(saved.allowMinPositionSizeUpscale)
        }
    }

    fun persist() {
        persistenceService.saveConfig(
            riskPerTrade = riskPerTrade,
            maxCapitalUsage = maxCapitalUsage,
            maxPositionSize = maxPositionSize,
            minPositionSize = minPositionSize,
            maxPositions = maxPositions,
            brokerLimitUsage = brokerLimitUsage,
            minOrderCashBuffer = minOrderCashBuffer,
            allowMinPositionSizeUpscale = allowMinPositionSizeUpscale
        )
    }

    fun toMap(): Map<String, Any> = mapOf(
        "riskPerTrade" to riskPerTrade,
        "riskPerTradePercent" to "${"%.1f".format(riskPerTrade * 100)}%",
        "maxCapitalUsage" to maxCapitalUsage,
        "maxCapitalUsagePercent" to "${"%.0f".format(maxCapitalUsage * 100)}%",
        "maxPositionSize" to maxPositionSize,
        "minPositionSize" to minPositionSize,
        "maxPositions" to maxPositions,
        "brokerLimitUsage" to brokerLimitUsage,
        "brokerLimitUsagePercent" to "${"%.0f".format(brokerLimitUsage * 100)}%",
        "minOrderCashBuffer" to minOrderCashBuffer,
        "allowMinPositionSizeUpscale" to allowMinPositionSizeUpscale
    )
}
