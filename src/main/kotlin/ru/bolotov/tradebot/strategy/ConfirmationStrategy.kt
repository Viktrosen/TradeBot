package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class ConfirmationStrategy : ConfigurableStrategy {

    override var name = "Confirmation"
    override var description = "Подтверждение сигналов"

    private var requiredIndicators: List<String> = listOf("EMA", "RSI")

    override fun configure(config: StrategyConfiguration) {
        when (config) {
            is StrategyConfiguration.Confirmation -> {
                requiredIndicators = config.requiredIndicators
                name = "Confirmation (${requiredIndicators.joinToString(" + ")})"
                description = "Стратегия подтверждения. Требуется согласие индикаторов: ${requiredIndicators.joinToString(", ")}"
                logger.info { "🔧 ConfirmationStrategy настроена: $requiredIndicators" }
            }
            else -> logger.warn { "⚠️ Неподдерживаемая конфигурация для ConfirmationStrategy" }
        }
    }

    override fun analyze(data: MarketData): Signal {
        val signals = mutableMapOf<String, Signal>()

        requiredIndicators.forEach { indicator ->
            val signal = when (indicator.uppercase()) {
                "EMA" -> analyzeEMA(data)
                "RSI" -> analyzeRSI(data)
                else -> Signal.HOLD
            }
            signals[indicator] = signal
        }

        val allBuy = signals.values.all { it.direction == OrderDirection.BUY }
        val allSell = signals.values.all { it.direction == OrderDirection.SELL }

        return when {
            allBuy -> {
                val avgConfidence = signals.values.map { it.confidence }.average()
                Signal(
                    direction = OrderDirection.BUY,
                    confidence = avgConfidence,
                    reason = buildReason(signals, "BUY")
                )
            }
            allSell -> {
                val avgConfidence = signals.values.map { it.confidence }.average()
                Signal(
                    direction = OrderDirection.SELL,
                    confidence = avgConfidence,
                    reason = buildReason(signals, "SELL")
                )
            }
            else -> Signal.HOLD
        }
    }

    private fun analyzeEMA(data: MarketData): Signal {
        val ema5 = data.ema5 ?: return Signal.HOLD
        val ema21 = data.ema21 ?: return Signal.HOLD

        return when {
            ema5 > ema21 -> Signal(OrderDirection.BUY, 0.7, "EMA: ↑")
            ema5 < ema21 -> Signal(OrderDirection.SELL, 0.7, "EMA: ↓")
            else -> Signal.HOLD
        }
    }

    private fun analyzeRSI(data: MarketData): Signal {
        val rsi = data.rsi ?: return Signal.HOLD

        return when {
            rsi < 30 -> Signal(OrderDirection.BUY, 0.8, "RSI: перепродан (${"%.1f".format(rsi)})")
            rsi > 70 -> Signal(OrderDirection.SELL, 0.8, "RSI: перекуплен (${"%.1f".format(rsi)})")
            rsi < 20 -> Signal(OrderDirection.BUY, 0.95, "RSI: экстремально перепродан")
            rsi > 80 -> Signal(OrderDirection.SELL, 0.95, "RSI: экстремально перекуплен")
            else -> Signal.HOLD
        }
    }

    private fun buildReason(signals: Map<String, Signal>, direction: String): String {
        return signals.entries.joinToString(" + ") { (name, signal) ->
            signal.reason?.replace("$name: ", "") ?: name
        } + " → $direction"
    }

    override fun getExplanation(data: MarketData): String {
        return buildString {
            append("🔍 Анализ по стратегии ПОДТВЕРЖДЕНИЯ\n")
            append("Требуемые индикаторы: ${requiredIndicators.joinToString(" + ")}\n\n")

            requiredIndicators.forEach { indicator ->
                when (indicator.uppercase()) {
                    "EMA" -> {
                        append("📈 EMA(5): ${data.ema5?.let { "%.2f".format(it) } ?: "—"}\n")
                        append("📉 EMA(21): ${data.ema21?.let { "%.2f".format(it) } ?: "—"}\n")
                        if (data.ema5 != null && data.ema21 != null) {
                            append("   ${if (data.ema5 > data.ema21) "✅ BUY" else if (data.ema5 < data.ema21) "❌ SELL" else "⚖️ HOLD"}\n")
                        }
                    }
                    "RSI" -> {
                        append("📊 RSI: ${data.rsi?.let { "%.1f".format(it) } ?: "—"}\n")
                        data.rsi?.let {
                            append("   ${when {
                                it < 30 -> "🟢 BUY (перепродан)"
                                it > 70 -> "🔴 SELL (перекуплен)"
                                else -> "⚪ HOLD"
                            }}\n")
                        }
                    }
                }
                append("\n")
            }

            val signal = analyze(data)
            append("🎯 ИТОГ: ${signal.direction}")
            if (signal.direction != OrderDirection.HOLD) {
                append(" (confidence: ${"%.0f".format(signal.confidence * 100)}%)")
            }
        }
    }
}