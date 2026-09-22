package ru.bolotov.tradebot.domain.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "trade_events")
data class TradeEvent(
    @Id
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "instrument_id")
    var instrumentId: String,

    @Column(name = "instrument_name")
    var instrumentName: String,

    @Enumerated(EnumType.STRING)
    var direction: OrderDirection,

    @Enumerated(EnumType.STRING)
    @Column(name = "position_side", nullable = false)
    var positionSide: PositionSide = PositionSide.LONG,

    @Column(precision = 20, scale = 4)
    var price: BigDecimal,

    var quantity: Long,

    @Column(name = "lot_size")
    var lotSize: Int,

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
    var positionId: String? = null,

    @Column(name = "broker_order_id")
    var brokerOrderId: String? = null,

    @Column(name = "execution_status")
    var executionStatus: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "broker_order_state", columnDefinition = "TEXT")
    var brokerOrderState: String? = null,

    @Column(name = "entry_strategy_id")
    var entryStrategyId: String? = null,

    @Column(name = "entry_market_regime")
    var entryMarketRegime: String? = null,

    @Column(name = "signal_candle_key")
    var signalCandleKey: String? = null,

    @Column(name = "signal_confidence", precision = 5, scale = 4)
    var signalConfidence: BigDecimal? = null,

    @Column(name = "entry_context_json", columnDefinition = "TEXT")
    var entryContextJson: String? = null,

    @Column(name = "mfe_percent", precision = 12, scale = 6)
    var mfePercent: BigDecimal? = null,

    @Column(name = "mae_percent", precision = 12, scale = 6)
    var maePercent: BigDecimal? = null,

    @Column(name = "excursion_complete")
    var excursionComplete: Boolean? = null
)

enum class OrderDirection {
    BUY, SELL
}

enum class PositionSide {
    LONG,
    SHORT
}

enum class EventStatus {
    PENDING, PROCESSED, FAILED
}

enum class EventType {
    OPEN, CLOSE
}
