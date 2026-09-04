package ru.bolotov.tradebot.service.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.bolotov.tradebot.strategy.MarketData
import java.math.BigDecimal

class AiMarketContextTest {

    @Test
    fun `adds VWAP and percentage deviation to AI market context`() {
        val context = AiMarketContext.from(marketData(price = "102", vwap = "100"))

        assertEquals(BigDecimal("100"), context.vwap)
        assertEquals(2.0, context.vwapDeviationPercent)
    }

    @Test
    fun `does not calculate VWAP deviation when VWAP is unavailable`() {
        val context = AiMarketContext.from(marketData(price = "102", vwap = null))

        assertNull(context.vwap)
        assertNull(context.vwapDeviationPercent)
    }

    private fun marketData(price: String, vwap: String?) = MarketData(
        instrumentId = "test",
        instrumentName = "TEST",
        currentPrice = BigDecimal(price),
        ema5 = null,
        ema21 = null,
        rsi = null,
        macd = null,
        bollingerBands = null,
        atr = null,
        volume = 0,
        avgVolume = 0,
        spread = BigDecimal.ZERO,
        volatility = 0.0,
        vwap = vwap?.let(::BigDecimal)
    )
}
