package ru.bolotov.tradebot.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import java.math.BigDecimal
import java.time.Instant

class OpenPositionTest {
    @Test
    fun `calculates positive pnl for profitable long`() {
        val position = position(side = PositionSide.LONG, entryPrice = "100")

        assertEquals(BigDecimal("200"), position.calculateUnrealizedPnl(BigDecimal("120")))
    }

    @Test
    fun `calculates positive pnl for profitable short`() {
        val position = position(side = PositionSide.SHORT, entryPrice = "100")

        assertEquals(BigDecimal("200"), position.calculateUnrealizedPnl(BigDecimal("80")))
    }

    private fun position(side: PositionSide, entryPrice: String): OpenPosition = OpenPosition(
        instrumentId = "instrument",
        instrumentName = "Инструмент",
        direction = if (side == PositionSide.LONG) OrderDirection.BUY else OrderDirection.SELL,
        side = side,
        entryPrice = BigDecimal(entryPrice),
        quantity = 2,
        lotSize = 5,
        entryTime = Instant.EPOCH
    )
}
