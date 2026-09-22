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
    val entryStrategyId: String? = null,
    val stopLossPrice: BigDecimal? = null,
    val atr: BigDecimal? = null,
    val highestObservedPrice: BigDecimal = entryPrice,
    val lowestObservedPrice: BigDecimal = entryPrice,
    /** False when the position was restored after a restart and its tick path is unknown. */
    val excursionTrackingComplete: Boolean = true
) {
    fun calculateUnrealizedPnl(currentPrice: BigDecimal): BigDecimal {
        val priceDifference = when (side) {
            PositionSide.LONG -> currentPrice - entryPrice
            PositionSide.SHORT -> entryPrice - currentPrice
        }

        return priceDifference * quantity.toBigDecimal() * lotSize.toBigDecimal()
    }

    /** Returns a copy with the newest local price incorporated into MFE/MAE tracking. */
    fun observePrice(price: BigDecimal): OpenPosition = copy(
        highestObservedPrice = highestObservedPrice.max(price),
        lowestObservedPrice = lowestObservedPrice.min(price)
    )

    /**
     * Returns percentage excursions only when every price since entry was observed
     * by this process. Restored positions intentionally expose no fabricated values.
     */
    fun excursionAt(closePrice: BigDecimal): PositionExcursion? {
        if (!excursionTrackingComplete || entryPrice <= BigDecimal.ZERO) return null
        val observed = observePrice(closePrice)
        val hundred = BigDecimal("100")
        val (favourable, adverse) = when (side) {
            PositionSide.LONG -> observed.highestObservedPrice - entryPrice to entryPrice - observed.lowestObservedPrice
            PositionSide.SHORT -> entryPrice - observed.lowestObservedPrice to observed.highestObservedPrice - entryPrice
        }
        return PositionExcursion(
            mfePercent = favourable.max(BigDecimal.ZERO).multiply(hundred).divide(entryPrice, 6, java.math.RoundingMode.HALF_UP),
            maePercent = adverse.max(BigDecimal.ZERO).multiply(hundred).divide(entryPrice, 6, java.math.RoundingMode.HALF_UP)
        )
    }
}

data class PositionExcursion(
    val mfePercent: BigDecimal,
    val maePercent: BigDecimal
)
