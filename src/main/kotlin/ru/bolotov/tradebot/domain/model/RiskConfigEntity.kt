package ru.bolotov.tradebot.domain.model

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "risk_config")
class RiskConfigEntity(
    @Id
    var id: String = "current",

    @Column(name = "risk_per_trade")
    var riskPerTrade: Double = 0.02,

    @Column(name = "max_capital_usage")
    var maxCapitalUsage: Double = 0.80,

    @Column(name = "max_position_size")
    var maxPositionSize: Long = 100_000L,

    @Column(name = "min_position_size")
    var minPositionSize: Long = 5_000L,

    @Column(name = "max_positions")
    var maxPositions: Int = 10,

    @Column(name = "broker_limit_usage")
    var brokerLimitUsage: Double = 0.95,

    @Column(name = "min_order_cash_buffer")
    var minOrderCashBuffer: Long = 100L,

    @Column(name = "allow_min_position_size_upscale")
    var allowMinPositionSizeUpscale: Boolean = false,

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
)
