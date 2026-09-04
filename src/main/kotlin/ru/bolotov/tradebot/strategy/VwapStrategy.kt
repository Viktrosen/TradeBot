package ru.bolotov.tradebot.strategy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

/**
 * VWAP — Volume Weighted Average Price с отклонениями.
 *
 * Логика:
 * - Цена ниже нижней полосы VWAP (отклонение > threshold) → BUY (возврат к среднему)
 * - Цена выше верхней полосы VWAP (отклонение > threshold) → SELL (возврат к среднему)
 *
 * Стратегия работает на отскок от VWAP и рекомендуется для режимов FLAT_HIGH_VOL.
 */
@Component
class VwapStrategy(
    @Value("\${strategy.vwap.deviation-threshold:0.02}") private val deviationThreshold: Double
) : TradingStrategy {

    override val name = "VWAP"
    override val description = "Volume Weighted Average Price с отклонениями"

    override fun analyze(data: MarketData): Signal {
        val currentPrice = data.currentPrice
        val vwap = data.vwap ?: return Signal.HOLD
        if (vwap <= BigDecimal.ZERO) return Signal.HOLD

        val deviation = (currentPrice.toDouble() - vwap.toDouble()) / vwap.toDouble()

        return when {
            deviation < -deviationThreshold -> {
                logger.info { "🟢 VWAP: BUY сигнал для ${data.instrumentName} (отклонение ${"%.2f".format(deviation * 100)}%)" }
                Signal(
                    OrderDirection.BUY,
                    0.70,
                    "VWAP: цена ниже средней (отклонение ${"%.2f".format(deviation * 100)}%)"
                )
            }
            deviation > deviationThreshold -> {
                logger.info { "🔴 VWAP: SELL сигнал для ${data.instrumentName} (отклонение ${"%.2f".format(deviation * 100)}%)" }
                Signal(
                    OrderDirection.SELL,
                    0.70,
                    "VWAP: цена выше средней (отклонение ${"%.2f".format(deviation * 100)}%)"
                )
            }
            else -> Signal.HOLD
        }
    }

    override fun getExplanation(data: MarketData): String {
        val vwap = data.vwap ?: return "VWAP недоступен: недостаточно объёма закрытых свечей"
        val deviation = (data.currentPrice.toDouble() - vwap.toDouble()) / vwap.toDouble() * 100
        return "VWAP deviation analysis; deviation=${"%.2f".format(deviation)}%; threshold=${deviationThreshold * 100}%"
    }

}
