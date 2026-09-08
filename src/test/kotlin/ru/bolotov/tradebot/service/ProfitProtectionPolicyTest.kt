package ru.bolotov.tradebot.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.domain.model.ProfitProtectionStage
import ru.bolotov.tradebot.service.data.ProfitProtectionDecision
import java.math.BigDecimal
import java.time.Instant

class ProfitProtectionPolicyTest {
    private val policy = ProfitProtectionPolicy()

    @Test
    fun `long profit activates trailing exit above break-even`() {
        val decision = policy.evaluate(
            position = position(PositionSide.LONG),
            currentPrice = BigDecimal("102"),
            atr = BigDecimal("0.50"),
            takeProfitPercent = 0.03,
            currentExitPrice = null
        )

        assertEquals(
            ProfitProtectionDecision.Updated(BigDecimal("101.50"), ProfitProtectionStage.TRAILING),
            decision
        )
    }

    @Test
    fun `short profit activates trailing exit below break-even`() {
        val decision = policy.evaluate(
            position = position(PositionSide.SHORT),
            currentPrice = BigDecimal("98"),
            atr = BigDecimal("0.50"),
            takeProfitPercent = 0.03,
            currentExitPrice = null
        )

        assertEquals(
            ProfitProtectionDecision.Updated(BigDecimal("98.50"), ProfitProtectionStage.TRAILING),
            decision
        )
    }

    @Test
    fun `long exits when price falls to saved managed level`() {
        val decision = policy.evaluate(
            position = position(PositionSide.LONG),
            currentPrice = BigDecimal("101.50"),
            atr = BigDecimal("0.50"),
            takeProfitPercent = 0.03,
            currentExitPrice = BigDecimal("101.50")
        )

        assertEquals(ProfitProtectionDecision.Exit(BigDecimal("101.50")), decision)
    }

    @Test
    fun `does not activate before half of configured take profit`() {
        val decision = policy.evaluate(
            position = position(PositionSide.LONG),
            currentPrice = BigDecimal("101.49"),
            atr = BigDecimal("0.50"),
            takeProfitPercent = 0.03,
            currentExitPrice = null
        )

        assertEquals(ProfitProtectionDecision.NoChange, decision)
    }

    private fun position(side: PositionSide) = OpenPosition(
        instrumentId = "instrument",
        instrumentName = "Инструмент",
        direction = if (side == PositionSide.LONG) OrderDirection.BUY else OrderDirection.SELL,
        side = side,
        entryPrice = BigDecimal("100"),
        quantity = 1,
        lotSize = 1,
        entryTime = Instant.EPOCH
    )
}
