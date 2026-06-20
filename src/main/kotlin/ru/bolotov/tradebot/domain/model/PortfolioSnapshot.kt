package ru.bolotov.tradebot.domain.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "portfolio_snapshots")
class PortfolioSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: String? = null,

    @Column(name = "total_value", precision = 20, scale = 4)
    var totalValue: BigDecimal,

    @Column(name = "cash_balance", precision = 20, scale = 4)
    var cashBalance: BigDecimal,

    @Column(name = "blocked_cash", precision = 20, scale = 4)
    var blockedCash: BigDecimal = BigDecimal.ZERO,

    @Column(name = "available_cash", precision = 20, scale = 4)
    var availableCash: BigDecimal = BigDecimal.ZERO,

    @Column(name = "currency")
    var currency: String = "rub",

    @Column(columnDefinition = "jsonb")
    var positions: String,

    @Column(name = "timestamp")
    var timestamp: Instant = Instant.now()
)
