package ru.bolotov.tradebot.service

import org.springframework.stereotype.Component
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.domain.model.ProfitProtectionStage
import ru.bolotov.tradebot.service.data.ProfitProtectionDecision
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Чистая политика сопровождения прибыли. Брокерский SL остаётся аварийной
 * защитой, а этот уровень исполняется работающим ботом без замены stop-заявки.
 */
@Component
class ProfitProtectionPolicy {

    fun evaluate(
        position: OpenPosition,
        currentPrice: BigDecimal,
        atr: BigDecimal?,
        takeProfitPercent: Double,
        currentExitPrice: BigDecimal?
    ): ProfitProtectionDecision {
        if (currentExitPrice != null && isExitTriggered(position.side, currentPrice, currentExitPrice)) {
            return ProfitProtectionDecision.Exit(currentExitPrice)
        }

        if (!hasReachedActivation(position.side, position.entryPrice, currentPrice, takeProfitPercent)) {
            return ProfitProtectionDecision.NoChange
        }

        val breakEvenPrice = breakEvenPrice(position)
        val candidate = trailingCandidate(position.side, currentPrice, atr, breakEvenPrice)
        if (currentExitPrice != null && !isStricter(position.side, candidate, currentExitPrice)) {
            return ProfitProtectionDecision.NoChange
        }
        val minimumUpdateStep = atr?.takeIf { it > BigDecimal.ZERO }?.multiply(UPDATE_STEP_ATR_FRACTION)
        if (currentExitPrice != null && minimumUpdateStep != null &&
            candidate.subtract(currentExitPrice).abs() < minimumUpdateStep
        ) {
            return ProfitProtectionDecision.NoChange
        }

        val stage = if (candidate == breakEvenPrice) {
            ProfitProtectionStage.BREAKEVEN
        } else {
            ProfitProtectionStage.TRAILING
        }
        return ProfitProtectionDecision.Updated(candidate, stage)
    }

    private fun hasReachedActivation(
        side: PositionSide,
        entryPrice: BigDecimal,
        currentPrice: BigDecimal,
        takeProfitPercent: Double
    ): Boolean {
        val activationPercent = BigDecimal.valueOf(takeProfitPercent).divide(BigDecimal(2), PERCENT_SCALE, RoundingMode.HALF_UP)
        return when (side) {
            PositionSide.LONG -> currentPrice >= entryPrice * (BigDecimal.ONE + activationPercent)
            PositionSide.SHORT -> currentPrice <= entryPrice * (BigDecimal.ONE - activationPercent)
        }
    }

    private fun breakEvenPrice(position: OpenPosition): BigDecimal {
        val units = (position.quantity * position.lotSize).toBigDecimal().coerceAtLeast(BigDecimal.ONE)
        val commissionPerUnit = position.entryCommission
            .multiply(BigDecimal(2))
            .divide(units, MONEY_SCALE, RoundingMode.UP)
        return when (position.side) {
            PositionSide.LONG -> position.entryPrice + commissionPerUnit
            PositionSide.SHORT -> position.entryPrice - commissionPerUnit
        }
    }

    private fun trailingCandidate(
        side: PositionSide,
        currentPrice: BigDecimal,
        atr: BigDecimal?,
        breakEvenPrice: BigDecimal
    ): BigDecimal {
        val trailingDistance = atr?.takeIf { it > BigDecimal.ZERO } ?: return breakEvenPrice
        return when (side) {
            PositionSide.LONG -> maxOf(breakEvenPrice, currentPrice - trailingDistance)
            PositionSide.SHORT -> minOf(breakEvenPrice, currentPrice + trailingDistance)
        }
    }

    private fun isExitTriggered(side: PositionSide, currentPrice: BigDecimal, exitPrice: BigDecimal): Boolean =
        when (side) {
            PositionSide.LONG -> currentPrice <= exitPrice
            PositionSide.SHORT -> currentPrice >= exitPrice
        }

    private fun isStricter(side: PositionSide, candidate: BigDecimal, current: BigDecimal): Boolean =
        when (side) {
            PositionSide.LONG -> candidate > current
            PositionSide.SHORT -> candidate < current
        }

    private companion object {
        const val PERCENT_SCALE = 8
        const val MONEY_SCALE = 8
        val UPDATE_STEP_ATR_FRACTION: BigDecimal = BigDecimal("0.25")
    }
}
