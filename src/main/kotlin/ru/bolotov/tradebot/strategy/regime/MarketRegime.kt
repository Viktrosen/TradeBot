package ru.bolotov.tradebot.strategy.regime

/**
 * Состояние рынка инструмента, определённое по закрытой M5-свече.
 *
 * Режим используется только для выбора стратегии для нового входа. Он не отменяет
 * защитные закрытия и не меняет стратегию уже открытой позиции.
 */
enum class MarketRegime {
    // Существующие
    STRONG_UPTREND,
    STRONG_DOWNTREND,
    FLAT,
    VOLATILE,
    UNCERTAIN,
    // НОВЫЕ
    WEAK_TREND,           // Тренд есть, но слабый (ADX < 25)
    FLAT_LOW_VOL,         // Тихий боковик (CHOP > 61.8, ATR < 0.5%)
    FLAT_HIGH_VOL,        // Волатильный боковик (CHOP > 61.8, ATR 0.5-1.0%)
    EXTREME_VOLATILE      // Экстремальная волатильность (ATR > 2.0%) — нет входов
}

data class MarketRegimeDecision(
    val regime: MarketRegime,
    val candidate: MarketRegime,
    val consecutiveCandles: Int,
    val summary: String
)
