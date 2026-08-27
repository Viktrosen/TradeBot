package ru.bolotov.tradebot.strategy.regime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.bolotov.tradebot.strategy.MarketData
import java.math.BigDecimal

class MarketRegimeServiceTest {

    @Test
    fun `confirms new regime only after configured closed candles`() {
        val service = regimeService()
        val marketData = marketData(
            instrumentId = "uptrend",
            price = "110",
            ema50 = "105",
            ema200 = "100",
            atr = "0.2"
        )

        assertEquals(MarketRegime.UNCERTAIN, service.evaluate(marketData).regime)
        assertEquals(MarketRegime.UNCERTAIN, service.evaluate(marketData).regime)
        assertEquals(MarketRegime.STRONG_UPTREND, service.evaluate(marketData).regime)
    }

    @Test
    fun `classifies low volatility near moving averages as flat`() {
        val service = regimeService()
        val marketData = marketData(
            instrumentId = "flat",
            price = "100",
            ema50 = "100.1",
            ema200 = "100",
            atr = "0.1"
        )

        repeat(2) { service.evaluate(marketData) }

        assertEquals(MarketRegime.FLAT, service.evaluate(marketData).regime)
    }

    private fun regimeService() = MarketRegimeService(
        confirmationCandles = 3,
        strongTrendEmaSpreadPercent = 0.60,
        flatEmaSpreadPercent = 0.20,
        volatileAtrPercent = 0.70,
        flatAtrPercent = 0.25
    )

    private fun marketData(
        instrumentId: String,
        price: String,
        ema50: String,
        ema200: String,
        atr: String
    ) = MarketData(
        instrumentId = instrumentId,
        instrumentName = instrumentId,
        currentPrice = BigDecimal(price),
        ema5 = BigDecimal(price),
        ema21 = BigDecimal(price),
        ema50 = BigDecimal(ema50),
        ema200 = BigDecimal(ema200),
        rsi = null,
        macd = null,
        bollingerBands = null,
        atr = BigDecimal(atr),
        volume = 1,
        avgVolume = 1,
        spread = BigDecimal.ZERO,
        volatility = 0.0,
        strategyCandleKey = "M5:1:0"
    )
}
