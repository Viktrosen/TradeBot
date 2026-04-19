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

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
)