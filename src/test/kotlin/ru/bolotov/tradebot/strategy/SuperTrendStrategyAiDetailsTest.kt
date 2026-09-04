package ru.bolotov.tradebot.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SuperTrendStrategyAiDetailsTest {

    @Test
    fun `exposes calculated ATR bands for AI after strategy state is initialized`() {
        val strategy = SuperTrendStrategy(atrPeriod = 10, multiplier = 3.0)
        val marketData = marketData(price = "100", atr = "1")

        strategy.analyze(marketData)
        strategy.analyze(marketData)

        val details = requireNotNull(strategy.getAiDetails(marketData))
        assertEquals("UPTREND", details.trendDirection)
        assertEquals(BigDecimal("103.0"), details.upperBand)
        assertEquals(BigDecimal("97.0"), details.lowerBand)
    }

    private fun marketData(price: String, atr: String) = MarketData(
        instrumentId = "test",
        instrumentName = "TEST",
        currentPrice = BigDecimal(price),
        ema5 = null,
        ema21 = null,
        rsi = null,
        macd = null,
        bollingerBands = null,
        atr = BigDecimal(atr),
        volume = 0,
        avgVolume = 0,
        spread = BigDecimal.ZERO,
        volatility = 0.0
    )
}
