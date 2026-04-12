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

    var totalValue: BigDecimal,
    var cashBalance: BigDecimal,

    @Column(columnDefinition = "jsonb")
    var positions: String,

    var timestamp: Instant = Instant.now()
)