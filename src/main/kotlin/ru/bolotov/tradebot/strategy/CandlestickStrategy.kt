package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class CandlestickStrategy : ConfigurableStrategy {

    override var name = "Candlestick"
    override var description = "Стратегия на основе свечных паттернов"

    private var requiredPatterns: List<String> = listOf("ENGULFING", "HAMMER", "DOJI")

    override fun configure(config: StrategyConfiguration) {
        when (config) {
            is StrategyConfiguration.Candlestick -> {
                requiredPatterns = config.requiredPatterns
                name = "Candlestick (${requiredPatterns.joinToString(" + ")})"
                description = "Стратегия свечных паттернов. Паттерны: ${requiredPatterns.joinToString(", ")}"
                logger.info { "🕯️ CandlestickStrategy настроена: $requiredPatterns" }
            }
            else -> logger.warn { "⚠️ Неподдерживаемая конфигурация для CandlestickStrategy" }
        }
    }

    override fun analyze(data: MarketData): Signal {
        val patternResult = data.candlestickPattern

        if (patternResult == null || patternResult.pattern == null) {
            return Signal.HOLD
        }

        val patternName = patternResult.pattern.name

        return if (requiredPatterns.any { patternName.contains(it) }) {
            Signal(
                direction = patternResult.direction,
                confidence = patternResult.confidence,
                reason = patternResult.description
            )
        } else {
            Signal.HOLD
        }
    }

    override fun getExplanation(data: MarketData): String {
        return buildString {
            append("🕯️ Анализ по свечной стратегии\n")
            append("Отслеживаемые паттерны: ${requiredPatterns.joinToString(", ")}\n\n")

            val pattern = data.candlestickPattern
            if (pattern != null && pattern.pattern != null) {
                append("✅ Обнаружен паттерн: ${pattern.description}\n")
                append("📊 Направление: ${pattern.direction}\n")
                append("🎯 Уверенность: ${"%.0f".format(pattern.confidence * 100)}%\n")
            } else {
                append("❌ Паттерны не обнаружены\n")
            }
        }
    }
}