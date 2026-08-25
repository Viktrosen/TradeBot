package ru.bolotov.tradebot.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "risk_config")
class RiskConfigEntity(
    @Id
    var id: String = "current",

    @Column(name = "position_size_percent")
    var positionSizePercent: Double = 0.05,

    @Column(name = "stop_loss_percent")
    var stopLossPercent: Double = 0.02,

    @Column(name = "take_profit_percent")
    var takeProfitPercent: Double = 0.03,

    @Column(name = "max_capital_usage")
    var maxCapitalUsage: Double = 0.80,

    @Column(name = "max_positions")
    var maxPositions: Int = 10,

    @Column(name = "broker_limit_usage")
    var brokerLimitUsage: Double = 0.95,

    @Column(name = "min_order_cash_buffer")
    var minOrderCashBuffer: Long = 100L,

    @Column(name = "short_trading_enabled")
    var shortTradingEnabled: Boolean = false,

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
)
