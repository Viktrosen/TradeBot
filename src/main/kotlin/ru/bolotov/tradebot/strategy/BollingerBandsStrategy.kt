package ru.bolotov.tradebot.strategy

import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class BollingerBandsStrategy : TradingStrategy {

    override val name = "Bollinger Bands"
    override val description = "Полосы Боллинджера. BUY при цене у нижней полосы, SELL при цене у верхней полосы"

    override fun analyze(data: MarketData): Signal {
        val bb = data.bollingerBands ?: return Signal.HOLD

        val percentB = bb.percentB

        return when {
            // Экстремально у нижней полосы (0-10%)
            percentB < 0.1 -> {
                val confidence = 0.8 + (0.1 - percentB) * 2
                Signal(
                    direction = OrderDirection.BUY,
                    confidence = confidence.coerceIn(0.6, 0.95),
                    reason = "BB: цена у нижней полосы (${"%.2f".format(data.currentPrice)} ≈ ${"%.2f".format(bb.lowerBand)})"
                )
            }
            // У нижней полосы (10-20%)
            percentB < 0.2 -> {
                Signal(
                    direction = OrderDirection.BUY,
                    confidence = 0.65,
                    reason = "BB: цена близка к нижней полосе"
                )
            }
            // Экстремально у верхней полосы (90-100%)
            percentB > 0.9 -> {
                val confidence = 0.8 + (percentB - 0.9) * 2
                Signal(
                    direction = OrderDirection.SELL,
                    confidence = confidence.coerceIn(0.6, 0.95),
                    reason = "BB: цена у верхней полосы (${"%.2f".format(data.currentPrice)} ≈ ${"%.2f".format(bb.upperBand)})"
                )
            }
            // У верхней полосы (80-90%)
            percentB > 0.8 -> {
                Signal(
                    direction = OrderDirection.SELL,
                    confidence = 0.65,
                    reason = "BB: цена близка к верхней полосе"
                )
            }
            else -> Signal.HOLD
        }
    }

    override fun getExplanation(data: MarketData): String {
        val bb = data.bollingerBands

        return buildString {
            append("📏 Анализ по стратегии Bollinger Bands\n\n")

            if (bb != null) {
                append("Верхняя полоса: ${"%.2f".format(bb.upperBand)}\n")
                append("Средняя полоса: ${"%.2f".format(bb.middleBand)}\n")
                append("Нижняя полоса: ${"%.2f".format(bb.lowerBand)}\n")
                append("Ширина канала: ${"%.2f".format(bb.bandwidth)}%%\n")
                append("Позиция цены (%%B): ${"%.1f".format(bb.percentB * 100)}%%\n\n")

                when {
                    bb.percentB < 0.1 -> append("🟢 Цена у нижней полосы — сигнал к покупке\n")
                    bb.percentB < 0.2 -> append("🟢 Цена близка к нижней полосе — слабый сигнал к покупке\n")
                    bb.percentB > 0.9 -> append("🔴 Цена у верхней полосы — сигнал к продаже\n")
                    bb.percentB > 0.8 -> append("🔴 Цена близка к верхней полосе — слабый сигнал к продаже\n")
                    else -> append("⚪ Цена в середине канала — сигнала нет\n")
                }
            } else {
                append("Нет данных Bollinger Bands\n")
            }
        }
    }
}