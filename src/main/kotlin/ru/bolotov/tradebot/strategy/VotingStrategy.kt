package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class VotingStrategy(
    private val crossEmaStrategy: CrossEmaStrategy,
    private val rsiStrategy: RsiStrategy,
    private val macdStrategy: MacdStrategy,
    private val bbStrategy: BollingerBandsStrategy
) : ConfigurableStrategy {

    override var name = "Voting"
    override var description = "Голосование с весами"

    private var weights: Map<String, Int> = mapOf("EMA" to 3, "RSI" to 2, "MACD" to 2, "BB" to 2)

    override fun configure(config: StrategyConfiguration) {
        when (config) {
            is StrategyConfiguration.Voting -> {
                weights = config.weights
                name = "Voting (веса: $weights)"
                description = "Комбинированная стратегия с голосованием. Веса индикаторов: $weights"
                logger.info { "🔧 VotingStrategy настроена: $weights" }
            }
            else -> logger.warn { "⚠️ Неподдерживаемая конфигурация для VotingStrategy" }
        }
    }

    override fun analyze(data: MarketData): Signal {
        var buyScore = 0
        var sellScore = 0
        val explanations = mutableListOf<String>()

        // EMA
        val emaSignal = crossEmaStrategy.analyze(data)
        when (emaSignal.direction) {
            OrderDirection.BUY -> buyScore += weights["EMA"] ?: 0
            OrderDirection.SELL -> sellScore += weights["EMA"] ?: 0
            else -> {}
        }
        if (emaSignal.direction != OrderDirection.HOLD) {
            explanations.add("EMA: ${emaSignal.direction}")
        }

        // RSI
        val rsiSignal = rsiStrategy.analyze(data)
        when (rsiSignal.direction) {
            OrderDirection.BUY -> buyScore += weights["RSI"] ?: 0
            OrderDirection.SELL -> sellScore += weights["RSI"] ?: 0
            else -> {}
        }
        if (rsiSignal.direction != OrderDirection.HOLD) {
            explanations.add("RSI: ${rsiSignal.direction}")
        }

        // MACD
        val macdSignal = macdStrategy.analyze(data)
        when (macdSignal.direction) {
            OrderDirection.BUY -> buyScore += weights["MACD"] ?: 0
            OrderDirection.SELL -> sellScore += weights["MACD"] ?: 0
            else -> {}
        }
        if (macdSignal.direction != OrderDirection.HOLD) {
            explanations.add("MACD: ${macdSignal.direction}")
        }

        // Bollinger Bands
        val bbSignal = bbStrategy.analyze(data)
        when (bbSignal.direction) {
            OrderDirection.BUY -> buyScore += weights["BB"] ?: 0
            OrderDirection.SELL -> sellScore += weights["BB"] ?: 0
            else -> {}
        }
        if (bbSignal.direction != OrderDirection.HOLD) {
            explanations.add("BB: ${bbSignal.direction}")
        }

        return when {
            buyScore > sellScore -> Signal(
                direction = OrderDirection.BUY,
                confidence = buyScore.toDouble() / (buyScore + sellScore),
                reason = "${explanations.joinToString(", ")} → BUY (${buyScore} vs ${sellScore})"
            )
            sellScore > buyScore -> Signal(
                direction = OrderDirection.SELL,
                confidence = sellScore.toDouble() / (buyScore + sellScore),
                reason = "${explanations.joinToString(", ")} → SELL (${sellScore} vs ${buyScore})"
            )
            else -> Signal.HOLD
        }
    }

    override fun getExplanation(data: MarketData): String {
        return buildString {
            append("🧠 Комбинированная стратегия (веса: $weights)\n\n")
            append("📈 Cross EMA\n\n${crossEmaStrategy.getExplanation(data)}\n\n")
            append("📊 RSI\n\n${rsiStrategy.getExplanation(data)}\n\n")
            append("📉 MACD\n\n${macdStrategy.getExplanation(data)}\n\n")
            append("📏 Bollinger Bands\n\n${bbStrategy.getExplanation(data)}\n\n")

            val signal = analyze(data)
            append("🎯 Итоговое решение: ${signal.direction}")
            signal.reason?.let { append(" ($it)") }
        }
    }
}