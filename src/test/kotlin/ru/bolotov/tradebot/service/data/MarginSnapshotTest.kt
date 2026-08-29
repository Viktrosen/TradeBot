package ru.bolotov.tradebot.service.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MarginSnapshotTest {
    @Test
    fun `does not treat a cash only account as having margin load`() {
        val margin = marginSnapshot(startingMargin = "0", minimalMargin = "0")

        assertFalse(margin.hasCurrentMarginLoad())
    }

    @Test
    fun `recognizes existing initial margin requirement`() {
        val margin = marginSnapshot(startingMargin = "250", minimalMargin = "100")

        assertTrue(margin.hasCurrentMarginLoad())
    }

    private fun marginSnapshot(startingMargin: String, minimalMargin: String) = MarginSnapshot(
        fundsSufficiencyLevel = BigDecimal.ZERO,
        liquidPortfolio = BigDecimal("1000"),
        startingMargin = BigDecimal(startingMargin),
        minimalMargin = BigDecimal(minimalMargin),
        missingFunds = BigDecimal.ZERO
    )
}
