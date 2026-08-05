package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class ConfirmationStrategy : ConfigurableStrategy {

    override var name = "Confirmation"
    override var description = "Подтверждение сигналов"

    private var requiredIndicators: List<String> = listOf("EMA", "RSI", "MACD", "BB")

    private val supportedIndicators = setOf("EMA", "RSI", "MACD", "BB", "BOLLINGER")

    override fun configure(config: StrategyConfiguration) {
        when (config) {
            is StrategyConfiguration.Confirmation -> {
                val normalizedIndicators = config.requiredIndicators.map { it.uppercase() }.distinct()
                require(normalizedIndicators.isNotEmpty()) {
                    "Для стратегии подтверждения необходимо указать хотя бы один индикатор"
                }
                require(normalizedIndicators.all { it in supportedIndicators }) {
                    "Неизвестные индикаторы: " +
                        normalizedIndicators.filterNot { it in supportedIndicators }.joinToString(", ")
                }

                requiredIndicators = normalizedIndicators
                name = "Confirmation (${requiredIndicators.joinToString(" + ")})"
                description = "Стратегия подтверждения. Требуется согласие индикаторов: ${requiredIndicators.joinToString(", ")}"
                logger.info { "🔧 ConfirmationStrategy настроена: $requiredIndicators" }
            }
            else -> logger.warn { "⚠️ Неподдерживаемая конфигурация для ConfirmationStrategy" }
        }
    }

    override fun analyze(data: MarketData): Signal {
        if (requiredIndicators.isEmpty()) return Signal.HOLD

        val signals = mutableMapOf<String, Signal>()

        requiredIndicators.forEach { indicator ->
            val signal = when (indicator.uppercase()) {
                "EMA" -> analyzeEMA(data)
                "RSI" -> analyzeRSI(data)
                "MACD" -> analyzeMACD(data)
                "BB", "BOLLINGER" -> analyzeBollingerBands(data)
                else -> Signal.HOLD
            }
            signals[indicator] = signal
        }

        val allBuy = signals.values.all { it.direction == OrderDirection.BUY }
        val allSell = signals.values.all { it.direction == OrderDirection.SELL }

        val buyCount = signals.values.count { it.direction == OrderDirection.BUY }
        val sellCount = signals.values.count { it.direction == OrderDirection.SELL }
        val totalIndicators = signals.size

        return when {
            // Все согласны → высокая уверенность
            allBuy -> {
                val avgConfidence = signals.values.map { it.confidence }.average()
                Signal(
                    OrderDirection.BUY,
                    minOf(avgConfidence * 1.2, 0.95),
                    buildReason(signals, "BUY", "ВСЕ $totalIndicators/$totalIndicators")
                )
            }
            allSell -> {
                val avgConfidence = signals.values.map { it.confidence }.average()
                Signal(
                    OrderDirection.SELL,
                    minOf(avgConfidence * 1.2, 0.95),
                    buildReason(signals, "SELL", "ВСЕ $totalIndicators/$totalIndicators")
                )
            }
            // 3 из 4 или 2 из 3
            buyCount >= totalIndicators - 1 && buyCount > sellCount -> {
                val avgConfidence = signals.values.filter { it.direction == OrderDirection.BUY }.map { it.confidence }.average()
                Signal(
                    OrderDirection.BUY,
                    avgConfidence,
                    buildReason(signals, "BUY", "$buyCount/$totalIndicators")
                )
            }
            sellCount >= totalIndicators - 1 && sellCount > buyCount -> {
                val avgConfidence = signals.values.filter { it.direction == OrderDirection.SELL }.map { it.confidence }.average()
                Signal(
                    OrderDirection.SELL,
                    avgConfidence,
                    buildReason(signals, "SELL", "$sellCount/$totalIndicators")
                )
            }
            else -> Signal.HOLD
        }
    }

    private fun analyzeEMA(data: MarketData): Signal {
        val ema5 = data.ema5 ?: return Signal.HOLD
        val ema21 = data.ema21 ?: return Signal.HOLD

        return when {
            ema5 > ema21 -> Signal(OrderDirection.BUY, 0.7, "EMA: ↑ (${"%.2f".format(ema5)} > ${"%.2f".format(ema21)})")
            ema5 < ema21 -> Signal(OrderDirection.SELL, 0.7, "EMA: ↓ (${"%.2f".format(ema5)} < ${"%.2f".format(ema21)})")
            else -> Signal.HOLD
        }
    }

    private fun analyzeRSI(data: MarketData): Signal {
        val rsi = data.rsi ?: return Signal.HOLD

        return when {
            rsi < 20 -> Signal(OrderDirection.BUY, 0.95, "RSI: экстремально перепродан (${"%.1f".format(rsi)})")
            rsi < 30 -> Signal(OrderDirection.BUY, 0.8, "RSI: перепродан (${"%.1f".format(rsi)})")
            rsi > 80 -> Signal(OrderDirection.SELL, 0.95, "RSI: экстремально перекуплен (${"%.1f".format(rsi)})")
            rsi > 70 -> Signal(OrderDirection.SELL, 0.8, "RSI: перекуплен (${"%.1f".format(rsi)})")
            else -> Signal.HOLD
        }
    }

    private fun analyzeMACD(data: MarketData): Signal {
        val macd = data.macd ?: return Signal.HOLD

        return when {
            macd.macdLine > macd.signalLine && macd.isPositive ->
                Signal(OrderDirection.BUY, 0.8, "MACD: ↑ (>0)")
            macd.macdLine > macd.signalLine ->
                Signal(OrderDirection.BUY, 0.6, "MACD: ↑")
            macd.macdLine < macd.signalLine && !macd.isPositive ->
                Signal(OrderDirection.SELL, 0.8, "MACD: ↓ (<0)")
            macd.macdLine < macd.signalLine ->
                Signal(OrderDirection.SELL, 0.6, "MACD: ↓")
            else -> Signal.HOLD
        }
    }

    private fun analyzeBollingerBands(data: MarketData): Signal {
        val bb = data.bollingerBands ?: return Signal.HOLD
        val percentB = bb.percentB

        return when {
            percentB < 0.1 -> {
                val confidence = 0.7 + (0.1 - percentB) * 2
                Signal(OrderDirection.BUY, confidence.coerceIn(0.6, 0.9), "BB: у нижней полосы")
            }
            percentB < 0.2 -> Signal(OrderDirection.BUY, 0.6, "BB: близко к нижней")
            percentB > 0.9 -> {
                val confidence = 0.7 + (percentB - 0.9) * 2
                Signal(OrderDirection.SELL, confidence.coerceIn(0.6, 0.9), "BB: у верхней полосы")
            }
            percentB > 0.8 -> Signal(OrderDirection.SELL, 0.6, "BB: близко к верхней")
            else -> Signal.HOLD
        }
    }

    private fun buildReason(signals: Map<String, Signal>, direction: String, strength: String): String {
        val activeSignals = signals.filter { it.value.direction != OrderDirection.HOLD }
        val signalList = activeSignals.map { (name, _) -> name }.joinToString(" + ")
        return "$signalList → $direction ($strength)"
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
                    "MACD" -> {
                        data.macd?.let {
                            append("📉 MACD: ${"%.4f".format(it.macdLine)} vs ${"%.4f".format(it.signalLine)}\n")
                            append("   ${when {
                                it.macdLine > it.signalLine -> "✅ BUY"
                                it.macdLine < it.signalLine -> "❌ SELL"
                                else -> "⚖️ HOLD"
                            }}\n")
                        } ?: append("📉 MACD: —\n")
                    }
                    "BB", "BOLLINGER" -> {
                        data.bollingerBands?.let {
                            append("📏 BB: ${"%.2f".format(data.currentPrice)} в канале [${"%.2f".format(it.lowerBand)} - ${"%.2f".format(it.upperBand)}]\n")
                            append("   ${when {
                                it.percentB < 0.2 -> "🟢 BUY (у нижней)"
                                it.percentB > 0.8 -> "🔴 SELL (у верхней)"
                                else -> "⚪ HOLD"
                            }}\n")
                        } ?: append("📏 BB: —\n")
                    }
                }
                append("\n")
            }

            val signal = analyze(data)
            append("🎯 ИТОГ: ${signal.direction}")
            if (signal.direction != OrderDirection.HOLD) {
                append(" (confidence: ${"%.0f".format(signal.confidence * 100)}%)")
            }
            signal.reason?.let { append("\nПричина: $it") }
        }
    }
}
