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
        val strategy = strategyManager.getStrategyById(strategyId) ?: run {
            return selectForNewPosition(currentRegime)
        }
        return StrategySelection(strategyId, strategy, currentRegime)
    }

    private companion object {
        val STRATEGY_BY_REGIME = mapOf(
            MarketRegime.STRONG_UPTREND to "ema",
            MarketRegime.STRONG_DOWNTREND to "ema",
            MarketRegime.FLAT to "candlestick",
            MarketRegime.VOLATILE to "voting"
        )
    }
}
