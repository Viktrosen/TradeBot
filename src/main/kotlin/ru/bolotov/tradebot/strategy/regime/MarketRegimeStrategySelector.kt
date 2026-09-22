package ru.bolotov.tradebot.strategy.regime

import org.springframework.stereotype.Service
import ru.bolotov.tradebot.strategy.StrategyManager
import ru.bolotov.tradebot.strategy.TradingStrategy

data class StrategySelection(
    val id: String,
    val strategy: TradingStrategy,
    val regime: MarketRegime
)

/**
 * Сопоставляет подтверждённый режим рынка со стратегией нового входа.
 *
 * Для [MarketRegime.UNCERTAIN] намеренно не возвращает стратегию: это режим
 * ожидания, в котором бот не открывает новые позиции.
 */
@Service
class MarketRegimeStrategySelector(
    private val strategyManager: StrategyManager
) {
    /** Выбирает стратегию только для нового входа в позицию. */
    fun selectForNewPosition(regime: MarketRegime): StrategySelection? {
        val strategyId = STRATEGY_BY_REGIME[regime] ?: run {
            return null
        }
        val strategy = requireNotNull(strategyManager.getStrategyById(strategyId)) {
            "Не зарегистрирована стратегия '$strategyId' для режима $regime"
        }
        return StrategySelection(strategyId, strategy, regime)
    }

    /** Возвращает стратегию, которая сопровождала позицию при её открытии. */
    fun selectForOpenPosition(
        entryStrategyId: String?,
        currentRegime: MarketRegime
    ): StrategySelection? {
        val strategyId = entryStrategyId ?: return selectForNewPosition(currentRegime)
        // While SuperTrend is under replay validation, existing positions stay
        // protected by the normal risk exits only. A rewritten strategy must not
        // create a new discretionary reversal close for a position opened by the
        // former stateful implementation.
        if (strategyId == SUPER_TREND_ID) return null
        val strategy = strategyManager.getStrategyById(strategyId) ?: run {
            return selectForNewPosition(currentRegime)
        }
        return StrategySelection(strategyId, strategy, currentRegime)
    }

    private companion object {
        const val SUPER_TREND_ID = "supertrend"

        val STRATEGY_BY_REGIME = mapOf(
            // Существующие
            MarketRegime.STRONG_UPTREND to "ema",
            MarketRegime.STRONG_DOWNTREND to "ema",
            MarketRegime.FLAT to "candlestick",
            MarketRegime.VOLATILE to "voting",
            MarketRegime.UNCERTAIN to null,

            // НОВЫЕ
            // SuperTrend remains available for deterministic replay, but its
            // historical result is insufficient for automatic new entries.
            MarketRegime.WEAK_TREND to null,
            MarketRegime.FLAT_LOW_VOL to "candlestick",
            MarketRegime.FLAT_HIGH_VOL to "vwap",
            MarketRegime.EXTREME_VOLATILE to null  // Нет входов — защита
        )
    }
}
