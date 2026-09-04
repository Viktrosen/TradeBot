package ru.bolotov.tradebot.service.data

import ru.bolotov.tradebot.service.OpenPosition
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.Signal
import ru.bolotov.tradebot.strategy.TradingStrategy

/** Полный безопасный для сериализации контекст запроса к AI-фильтру. */
data class AiTradeContext(
    val strategy: AiStrategyContext,
    val signal: AiSignalContext,
    val market: AiMarketContext,
    val position: AiPositionContext?
) {
    companion object {
        fun from(
            marketData: MarketData,
            signal: Signal,
            strategy: TradingStrategy,
            position: OpenPosition?
        ) = AiTradeContext(
            strategy = AiStrategyContext(
                name = strategy.name,
                explanation = strategy.getExplanation(marketData),
                details = strategy.getAiDetails(marketData),
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
