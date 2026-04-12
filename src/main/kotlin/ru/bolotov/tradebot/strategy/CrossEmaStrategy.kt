package ru.bolotov.tradebot.strategy

import org.springframework.stereotype.Component

@Component
class CrossEmaStrategy : TradingStrategy {
    override val name = "Cross EMA"
    override val description = "Сигнал при пересечении EMA(5) и EMA(21)"

    override fun analyze(data: MarketData): Signal {
        val ema5 = data.ema5 ?: return Signal(OrderDirection.HOLD, 0.0)
        val ema21 = data.ema21 ?: return Signal(OrderDirection.HOLD, 0.0)
        return when {
            ema5 > ema21 -> Signal(OrderDirection.BUY, 0.7)
            ema5 < ema21 -> Signal(OrderDirection.SELL, 0.7)
            else -> Signal(OrderDirection.HOLD, 0.0)
        }
    }

    override fun getExplanation(data: MarketData): String = """
        📈 Анализ по стратегии $name
        
        EMA(5): ${data.ema5?.toPlainString() ?: "нет данных"}
        EMA(21): ${data.ema21?.toPlainString() ?: "нет данных"}
        
        ${when {
        data.ema5 != null && data.ema21 != null && data.ema5 > data.ema21 ->
            "Короткая EMA пересекла длинную снизу вверх — сигнал к покупке"
        data.ema5 != null && data.ema21 != null && data.ema5 < data.ema21 ->
            "Короткая EMA пересекла длинную сверху вниз — сигнал к продаже"
        else -> "Нет чёткого сигнала"
    }}
        
        Объём торгов: ${data.volume} (средний: ${data.avgVolume})
        Волатильность: ${String.format("%.1f", data.volatility)}%
    """.trimIndent()
}