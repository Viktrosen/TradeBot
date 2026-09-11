package ru.bolotov.tradebot.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Compact, queryable audit trail of operational decisions.
 *
 * It intentionally excludes high-frequency ticks and indicator calculations;
 * those remain ordinary DEBUG logs.
 */
@Entity
@Table(name = "bot_operation_logs")
class BotOperationLogEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false)
    var level: BotOperationLogLevel,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: BotOperationEventType,

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    var message: String,

    @Column(name = "instrument_id")
    var instrumentId: String? = null,

    @Column(name = "instrument_name")
    var instrumentName: String? = null,

    @Column(name = "position_id")
    var positionId: String? = null,

    @Column(name = "broker_order_id")
    var brokerOrderId: String? = null,

    @Column(name = "correlation_id")
    var correlationId: String? = null,

    @Column(name = "context_json", columnDefinition = "TEXT")
    var contextJson: String? = null,

    @Column(name = "error_class")
    var errorClass: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null
)

enum class BotOperationLogLevel {
    INFO,
    WARN,
    ERROR
}

enum class BotOperationEventType {
    AI_REQUEST,
    AI_DECISION,
    AI_FAILURE,
    TRADE_DECISION,
    RISK_REJECTION,
    ORDER_EXECUTION,
    PROTECTION_CREATED,
    PROTECTION_RESTORED,
    PROFIT_PROTECTION_UPDATED,
    RECONCILIATION,
    SCHEDULER_FAILURE
}
