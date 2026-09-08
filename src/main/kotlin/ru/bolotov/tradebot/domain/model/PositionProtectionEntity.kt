package ru.bolotov.tradebot.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "position_protections")
class PositionProtectionEntity(
    @Id
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "position_id", nullable = false, unique = true)
    var positionId: String,

    @Column(name = "instrument_id", nullable = false)
    var instrumentId: String,

    @Column(name = "stop_loss_order_id")
    var stopLossOrderId: String? = null,

    @Column(name = "broker_stop_loss_price", precision = 20, scale = 9)
    var brokerStopLossPrice: BigDecimal? = null,

    @Column(name = "managed_exit_price", precision = 20, scale = 9)
    var managedExitPrice: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "profit_protection_stage", nullable = false)
    var profitProtectionStage: ProfitProtectionStage = ProfitProtectionStage.INACTIVE,

    @Column(name = "take_profit_order_id")
    var takeProfitOrderId: String? = null,

    @Column(name = "replacement_stop_loss_order_id")
    var replacementStopLossOrderId: String? = null,

    @Column(name = "replacement_take_profit_order_id")
    var replacementTakeProfitOrderId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "update_status", nullable = false)
    var updateStatus: ProtectionUpdateStatus = ProtectionUpdateStatus.ACTIVE,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

enum class ProtectionUpdateStatus {
    ACTIVE,
    CREATING_REPLACEMENT,
    REPLACEMENT_CREATED,
    CANCELLING_PREVIOUS
}

/** Состояние управляемого ботом выхода, защищающего уже полученную прибыль. */
enum class ProfitProtectionStage {
    INACTIVE,
    BREAKEVEN,
    TRAILING
}
