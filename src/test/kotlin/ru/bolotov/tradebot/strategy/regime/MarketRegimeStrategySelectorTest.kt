package ru.bolotov.tradebot.strategy.regime

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import ru.bolotov.tradebot.strategy.StrategyManager

class MarketRegimeStrategySelectorTest {

    @Test
    fun `weak trend has no automatic entry strategy while SuperTrend is under validation`() {
        val selector = MarketRegimeStrategySelector(mock(StrategyManager::class.java))

        assertNull(selector.selectForNewPosition(MarketRegime.WEAK_TREND))
    }

    @Test
    fun `legacy SuperTrend position uses risk exits only during validation`() {
        val selector = MarketRegimeStrategySelector(mock(StrategyManager::class.java))

        assertNull(selector.selectForOpenPosition("supertrend", MarketRegime.WEAK_TREND))
    }
}
