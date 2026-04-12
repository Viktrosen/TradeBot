package ru.bolotov.tradebot.strategy

import org.springframework.stereotype.Component

@Component
class RsiStrategy : TradingStrategy {
    override val name = "RSI"
    override val description = "Сигнал при RSI < 30 (покупка) или RSI > 70 (продажа)"

    override fun analyze(data: MarketData): Signal {
        val rsi = data.rsi ?: return Signal(OrderDirection.HOLD, 0.0)
        return when {
            rsi < 30 -> Signal(OrderDirection.BUY, 0.8)
            rsi > 70 -> Signal(OrderDirection.SELL, 0.8)
            else -> Signal(OrderDirection.HOLD, 0.0)
        }
    }

    override fun getExplanation(data: MarketData): String = """
        📊 Анализ по стратегии $name
        
        RSI: ${String.format("%.1f", data.rsi ?: 0.0)}
        
        ${when {
        data.rsi != null && data.rsi < 30 ->
            "RSI ниже 30 — зона перепроданности, сигнал к покупке"
        data.rsi != null && data.rsi > 70 ->
            "RSI выше 70 — зона перекупленности, сигнал к продаже"
        else -> "RSI в нейтральной зоне (30-70) — сигнала нет"
    }}
        
        Объём торгов: ${data.volume} (средний: ${data.avgVolume})
    """.trimIndent()
}