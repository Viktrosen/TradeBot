package ru.bolotov.tradebot.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class SuperTrendStrategyTest {

    private val strategy = SuperTrendStrategy(atrPeriod = 2, multiplier = 1.0)

    @Test
    fun `does not create a signal until closed candle history can establish a direction`() {
        val signal = strategy.analyze(marketData(candles = candles().take(3)))

        assertEquals(OrderDirection.HOLD, signal.direction)
        assertNull(strategy.getAiDetails(marketData(candles = candles().take(3))))
    }

    @Test
    fun `emits sell only when the last completed candle reverses the calculated direction`() {
        val data = marketData(candles = candles().take(4))

        val signal = strategy.analyze(data)

        assertEquals(OrderDirection.SELL, signal.direction)
        assertEquals("DOWNTREND", requireNotNull(strategy.getAiDetails(data)).trendDirection)
    }

    @Test
    fun `same candle history yields the same signal after strategy recreation`() {
        val replay = SuperTrendStrategy(atrPeriod = 2, multiplier = 1.0)
        val data = marketData(candles = candles())

        assertEquals(OrderDirection.BUY, strategy.analyze(data).direction)
        assertEquals(OrderDirection.BUY, replay.analyze(data).direction)
        assertEquals(strategy.getAiDetails(data), replay.getAiDetails(data))
    }

    private fun marketData(candles: List<StrategyCandle>) = MarketData(
        instrumentId = "test",
        instrumentName = "TEST",
        currentPrice = candles.lastOrNull()?.close ?: BigDecimal("100"),
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
        closedCandles = candles
    )

    private fun candles(): List<StrategyCandle> = listOf(
        candle(0, "100", "101", "99", "100"),
        candle(1, "101", "102", "100", "101"),
        candle(2, "102", "103", "101", "102"),
        candle(3, "102", "103", "99", "99"),
        candle(4, "100", "106", "100", "105")
    )

    private fun candle(index: Long, open: String, high: String, low: String, close: String) = StrategyCandle(
        key = "M5:$index:0",
        time = Instant.ofEpochSecond(index * 300),
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = 1
    )
}
