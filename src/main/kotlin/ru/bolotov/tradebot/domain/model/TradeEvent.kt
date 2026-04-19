package ru.bolotov.tradebot.domain.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "trade_event")
data class TradeEvent(
    @Id
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "instrument_id")
    var instrumentId: String,

    @Column(name = "instrument_name")
    var instrumentName: String,

    @Enumerated(EnumType.STRING)
    var direction: OrderDirection,

    @Column(precision = 20, scale = 4)
    var price: BigDecimal,

    var quantity: Long,

    @Column(name = "total_value", precision = 20, scale = 4)
    var totalValue: BigDecimal,

    var reason: String,

    @Column(columnDefinition = "TEXT")
    var explanation: String,

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now(),

    @Enumerated(EnumType.STRING)
    var status: EventStatus = EventStatus.PENDING,

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    // 🆕 НОВЫЕ ПОЛЯ
    @Column(name = "pnl", precision = 20, scale = 2)
    var pnl: BigDecimal? = null,

    @Column(name = "event_type")
    @Enumerated(EnumType.STRING)
    var eventType: EventType? = null,

    @Column(name = "position_id")
    var positionId: String? = null
)

enum class OrderDirection {
    BUY, SELL
}

enum class EventStatus {
    PENDING, PROCESSED, FAILED
}

enum class EventType {
    OPEN, CLOSE
}