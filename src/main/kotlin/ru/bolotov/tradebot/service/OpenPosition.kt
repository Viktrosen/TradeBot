package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.domain.model.OrderDirection
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class OpenPosition(
    val positionId: String = UUID.randomUUID().toString(),
    val instrumentId: String,
    val instrumentName: String,
    val direction: OrderDirection,
    val entryPrice: BigDecimal,
    val quantity: Long,
    val lotSize: Int = 1,
    val entryCommission: BigDecimal = BigDecimal.ZERO,
    val entryTime: Instant,
    val stopLossPrice: BigDecimal? = null,
    val atr: BigDecimal? = null
)

