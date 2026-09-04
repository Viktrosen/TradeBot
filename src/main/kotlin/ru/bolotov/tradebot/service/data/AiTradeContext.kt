package ru.bolotov.tradebot.service.data

import ru.bolotov.tradebot.service.OpenPosition
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.Signal
import ru.bolotov.tradebot.strategy.regime.MarketRegimeDecision
import ru.bolotov.tradebot.strategy.regime.StrategySelection

/** Полный безопасный для сериализации контекст запроса к AI-фильтру. */
data class AiTradeContext(
    val marketRegime: AiMarketRegimeContext,
    val strategy: AiStrategyContext,
    val signal: AiSignalContext,
    val market: AiMarketContext,
    val position: AiPositionContext?
) {
    companion object {
        fun from(
            marketData: MarketData,
            signal: Signal,
            selection: StrategySelection,
            regimeDecision: MarketRegimeDecision,
            position: OpenPosition?
        ) = AiTradeContext(
            marketRegime = AiMarketRegimeContext(
                confirmed = regimeDecision.regime.name,
                candidate = regimeDecision.candidate.name,
                confirmationCandles = regimeDecision.consecutiveCandles,
                summary = regimeDecision.summary
            ),
            strategy = AiStrategyContext(
                id = selection.id,
                name = selection.strategy.name,
                explanation = selection.strategy.getExplanation(marketData),
                details = selection.strategy.getAiDetails(marketData),
                candlestickPattern = marketData.candlestickPattern?.pattern?.name,
                candlestickConfidence = marketData.candlestickPattern?.confidence,
                candlestickTimeframe = marketData.candlestickPattern?.candleKey?.substringBefore(':')
            ),
            signal = AiSignalContext(
                direction = signal.direction.name,
                confidence = signal.confidence,
                reason = signal.reason
            ),
            market = AiMarketContext.from(marketData),
            position = position?.let { AiPositionContext.from(it, marketData.currentPrice) }
        )
    }
}

/** Внутренний контекст выбора стратегии; не является API-контрактом. */
data class AiMarketRegimeContext(
    val confirmed: String,
    val candidate: String,
    val confirmationCandles: Int,
    val summary: String
)
