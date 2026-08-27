package ru.bolotov.tradebot.strategy.regime.data

import ru.bolotov.tradebot.strategy.regime.MarketRegime

/** Последнее подтверждённое и кандидатное состояние рыночного режима инструмента. */
data class RegimeState(
    val regime: MarketRegime = MarketRegime.UNCERTAIN,
    val candidate: MarketRegime = MarketRegime.UNCERTAIN,
    val consecutiveCandles: Int = 0
)
