package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class VotingStrategy(
    private val crossEmaStrategy: CrossEmaStrategy,
    private val rsiStrategy: RsiStrategy,
    private val macdStrategy: MacdStrategy,
    private val bbStrategy: BollingerBandsStrategy,
    private val superTrendStrategy: SuperTrendStrategy,  // НОВОЕ
    private val vwapStrategy: VwapStrategy  // НОВОЕ
) : ConfigurableStrategy {

    override var name = "Voting"
    override var description = "Голосование с весами"

    private var weights: Map<String, Int> = mapOf(
        "EMA" to 3, 
        "RSI" to 2, 
        "MACD" to 2, 
        "BB" to 2,
        "SUPER_TREND" to 3,  // НОВОЕ
        "VWAP" to 2  // НОВОЕ
    )

    private val supportedIndicators = setOf("EMA", "RSI", "MACD", "BB", "SUPER_TREND", "VWAP")

    fun getWeights(): Map<String, Int> = weights.toMap()

    override fun configure(config: StrategyConfiguration) {
        when (config) {
            is StrategyConfiguration.Voting -> {
                val normalizedWeights = config.weights.mapKeys { (indicator, _) -> indicator.uppercase() }
                require(normalizedWeights.keys.all { it in supportedIndicators }) {
                    "Неизвестные индикаторы в стратегии голосования: " +
                        normalizedWeights.keys.filterNot { it in supportedIndicators }.joinToString(", ")
                }
                require(normalizedWeights.size >= 2) {
                    "Для стратегии голосования необходимо не менее двух индикаторов"
                }
                require(normalizedWeights.values.all { it in 1..10 }) {
                    "Вес каждого индикатора должен быть от 1 до 10"
                }

                weights = normalizedWeights
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
        val configuredWeight = weights.values.sum()

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

        // НОВОЕ: SuperTrend
        val superTrendSignal = superTrendStrategy.analyze(data)
        when (superTrendSignal.direction) {
            OrderDirection.BUY -> buyScore += weights["SUPER_TREND"] ?: 0
            OrderDirection.SELL -> sellScore += weights["SUPER_TREND"] ?: 0
            else -> {}
        }
        if (superTrendSignal.direction != OrderDirection.HOLD) {
            explanations.add("SUPER_TREND: ${superTrendSignal.direction}")
        }

        // НОВОЕ: VWAP
        val vwapSignal = vwapStrategy.analyze(data)
        when (vwapSignal.direction) {
            OrderDirection.BUY -> buyScore += weights["VWAP"] ?: 0
            OrderDirection.SELL -> sellScore += weights["VWAP"] ?: 0
            else -> {}
        }
        if (vwapSignal.direction != OrderDirection.HOLD) {
            explanations.add("VWAP: ${vwapSignal.direction}")
        }

        return when {
            buyScore > sellScore -> Signal(
                direction = OrderDirection.BUY,
                confidence = buyScore.toDouble() / configuredWeight,
                reason = "${explanations.joinToString(", ")} → BUY (${buyScore} vs ${sellScore})"
            )
            sellScore > buyScore -> Signal(
                direction = OrderDirection.SELL,
                confidence = sellScore.toDouble() / configuredWeight,
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
            append("📈 SuperTrend\n\n${superTrendStrategy.getExplanation(data)}\n\n")  // НОВОЕ
            append("📊 VWAP\n\n${vwapStrategy.getExplanation(data)}\n\n")  // НОВОЕ

            val signal = analyze(data)
            append("🎯 Итоговое решение: ${signal.direction}")
            signal.reason?.let { append(" ($it)") }
        }
    }
}
