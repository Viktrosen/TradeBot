package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class VotingStrategy(
    private val crossEmaStrategy: CrossEmaStrategy,
    private val rsiStrategy: RsiStrategy
) : ConfigurableStrategy {

    override var name = "Voting"
    override var description = "Голосование с весами"

    private var weights: Map<String, Int> = mapOf("EMA" to 3, "RSI" to 2)

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
            append("📈 Анализ по стратегии Cross EMA\n\n")
            append(crossEmaStrategy.getExplanation(data))
            append("\n\n")
            append("📊 Анализ по стратегии RSI\n\n")
            append(rsiStrategy.getExplanation(data))
            append("\n\n")

            val signal = analyze(data)
            append("Итоговое решение: ${signal.direction}")
        }
    }
}