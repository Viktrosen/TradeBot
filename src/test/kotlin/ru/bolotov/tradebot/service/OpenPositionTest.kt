package ru.bolotov.tradebot.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import java.math.BigDecimal
import java.time.Instant

class OpenPositionTest {

    @Test
    fun `calculates positive pnl for profitable long`() {
        val position = position(side = PositionSide.LONG, entryPrice = "100", quantity = 2, lotSize = 5)

        assertEquals(BigDecimal("200"), position.calculateUnrealizedPnl(BigDecimal("120")))
    }

    @Test
    fun `calculates positive pnl for profitable short`() {
        val position = position(side = PositionSide.SHORT, entryPrice = "100", quantity = 2, lotSize = 5)

        assertEquals(BigDecimal("200"), position.calculateUnrealizedPnl(BigDecimal("80")))
    }

    @Test
    fun `long excursions use the highest and lowest observed prices including close`() {
        val metrics = position(PositionSide.LONG)
            .observePrice(BigDecimal("103"))
            .observePrice(BigDecimal("98"))
            .excursionAt(BigDecimal("102"))

        assertEquals(BigDecimal("3.000000"), metrics?.mfePercent)
        assertEquals(BigDecimal("2.000000"), metrics?.maePercent)
    }

    @Test
    fun `short excursions preserve favourable and adverse direction`() {
        val metrics = position(PositionSide.SHORT)
            .observePrice(BigDecimal("96"))
            .observePrice(BigDecimal("102"))
            .excursionAt(BigDecimal("99"))

        assertEquals(BigDecimal("4.000000"), metrics?.mfePercent)
        assertEquals(BigDecimal("2.000000"), metrics?.maePercent)
    }

    @Test
    fun `restored position does not invent excursion values`() {
        assertNull(position(PositionSide.LONG, complete = false).excursionAt(BigDecimal("105")))
    }

    private fun position(
        side: PositionSide,
        complete: Boolean = true,
        entryPrice: String = "100",
        quantity: Long = 1,
        lotSize: Int = 1
    ) = OpenPosition(
        instrumentId = "instrument",
        instrumentName = "Instrument",
        direction = if (side == PositionSide.LONG) OrderDirection.BUY else OrderDirection.SELL,
        side = side,
        entryPrice = BigDecimal(entryPrice),
        quantity = quantity,
        lotSize = lotSize,
        entryTime = Instant.EPOCH,
        excursionTrackingComplete = complete
    )
}
