package ru.bolotov.tradebot.strategy.regime

/**
 * Состояние рынка инструмента, определённое по закрытой M5-свече.
 *
 * Режим используется только для выбора стратегии для нового входа. Он не отменяет
 * защитные закрытия и не меняет стратегию уже открытой позиции.
 */
enum class MarketRegime {
    STRONG_UPTREND,
    STRONG_DOWNTREND,
    FLAT,
    VOLATILE,
    UNCERTAIN
}

data class MarketRegimeDecision(
    val regime: MarketRegime,
    val candidate: MarketRegime,
    val consecutiveCandles: Int,
    val summary: String
)
