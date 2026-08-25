package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class OpenPosition(
    val positionId: String = UUID.randomUUID().toString(),
    val instrumentId: String,
    val instrumentName: String,
    val direction: OrderDirection,
    val side: PositionSide = PositionSide.LONG,
    val entryPrice: BigDecimal,
    val quantity: Long,
    val lotSize: Int = 1,
    val entryCommission: BigDecimal = BigDecimal.ZERO,
    val entryTime: Instant,
    val stopLossPrice: BigDecimal? = null,
    val atr: BigDecimal? = null
) {
    fun calculateUnrealizedPnl(currentPrice: BigDecimal): BigDecimal {
        val priceDifference = when (side) {
            PositionSide.LONG -> currentPrice - entryPrice
            PositionSide.SHORT -> entryPrice - currentPrice
        }

        return priceDifference * quantity.toBigDecimal() * lotSize.toBigDecimal()
    }
}
