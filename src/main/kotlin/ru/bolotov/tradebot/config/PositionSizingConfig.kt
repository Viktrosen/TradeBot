package ru.bolotov.tradebot.config

import org.springframework.stereotype.Component
import ru.bolotov.tradebot.service.RiskConfigPersistenceService
import java.util.concurrent.atomic.AtomicReference

@Component
class PositionSizingConfig(
    private val persistenceService: RiskConfigPersistenceService? = null  // Опционально
) {

    private val _riskPerTrade = AtomicReference(0.02)
    private val _maxCapitalUsage = AtomicReference(0.80)
    private val _maxPositionSize = AtomicReference(100_000L)
    private val _minPositionSize = AtomicReference(5_000L)
    private val _maxPositions = AtomicReference(10)

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


    fun persist() {
        persistenceService?.saveConfig()
    }

    fun toMap(): Map<String, Any> = mapOf(
        "riskPerTrade" to riskPerTrade,
        "riskPerTradePercent" to "${"%.1f".format(riskPerTrade * 100)}%",
        "maxCapitalUsage" to maxCapitalUsage,
        "maxCapitalUsagePercent" to "${"%.0f".format(maxCapitalUsage * 100)}%",
        "maxPositionSize" to maxPositionSize,
        "minPositionSize" to minPositionSize,
        "maxPositions" to maxPositions
    )
}