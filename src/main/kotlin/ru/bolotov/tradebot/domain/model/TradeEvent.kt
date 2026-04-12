package ru.bolotov.tradebot.domain.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "trade_events")
class TradeEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: String? = null,

    var instrumentId: String,
    var instrumentName: String,

    @Enumerated(EnumType.STRING)
    var direction: OrderDirection,

    var price: BigDecimal,
    var quantity: Long,
    var totalValue: BigDecimal,

    @Column(length = 2000)
    var reason: String,

    @Column(length = 4000)
    var explanation: String,

    var timestamp: Instant = Instant.now(),

    @Enumerated(EnumType.STRING)
    var status: EventStatus = EventStatus.PENDING,

    var processedAt: Instant? = null
)

enum class OrderDirection { BUY, SELL }
enum class EventStatus { PENDING, PROCESSED, FAILED }