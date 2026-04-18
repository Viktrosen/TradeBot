package ru.bolotov.tradebot.strategy

import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class MacdStrategy : TradingStrategy {

    override val name = "MACD"
    override val description = "Схождение/расхождение скользящих средних. BUY при MACD > Signal и MACD > 0, SELL при MACD < Signal и MACD < 0"

    override fun analyze(data: MarketData): Signal {
        val macd = data.macd ?: return Signal.HOLD

        return when {
            // Сильный BUY: MACD > Signal И MACD > 0
            macd.macdLine > macd.signalLine && macd.isPositive -> {
                Signal(
                    direction = OrderDirection.BUY,
                    confidence = 0.8,
                    reason = "MACD > Signal (${format(macd.macdLine)} > ${format(macd.signalLine)}) и MACD > 0"
                )
            }
            // Слабый BUY: только MACD > Signal
            macd.macdLine > macd.signalLine -> {
                Signal(
                    direction = OrderDirection.BUY,
                    confidence = 0.6,
                    reason = "MACD > Signal (${format(macd.macdLine)} > ${format(macd.signalLine)})"
                )
            }
            // Сильный SELL: MACD < Signal И MACD < 0
            macd.macdLine < macd.signalLine && !macd.isPositive -> {
                Signal(
                    direction = OrderDirection.SELL,
                    confidence = 0.8,
                    reason = "MACD < Signal (${format(macd.macdLine)} < ${format(macd.signalLine)}) и MACD < 0"
                )
            }
            // Слабый SELL: только MACD < Signal
            macd.macdLine < macd.signalLine -> {
                Signal(
                    direction = OrderDirection.SELL,
                    confidence = 0.6,
                    reason = "MACD < Signal (${format(macd.macdLine)} < ${format(macd.signalLine)})"
                )
            }
            else -> Signal.HOLD
        }
    }

    override fun getExplanation(data: MarketData): String {
        val macd = data.macd

        return buildString {
            append("📊 Анализ по стратегии MACD\n\n")

            if (macd != null) {
                append("MACD Line: ${format(macd.macdLine)}\n")
                append("Signal Line: ${format(macd.signalLine)}\n")
                append("Histogram: ${format(macd.histogram)}\n")
                append("MACD ${if (macd.isPositive) "> 0 (бычий)" else "< 0 (медвежий)"}\n\n")

                when {
                    macd.macdLine > macd.signalLine -> append("✅ MACD > Signal — сигнал к покупке\n")
                    macd.macdLine < macd.signalLine -> append("❌ MACD < Signal — сигнал к продаже\n")
                    else -> append("⚖️ MACD ≈ Signal — нейтрально\n")
                }
            } else {
                append("Нет данных MACD\n")
            }
        }
    }

    private fun format(value: BigDecimal): String = "%.4f".format(value)
}