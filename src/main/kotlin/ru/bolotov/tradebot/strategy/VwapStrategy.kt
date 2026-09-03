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
        val volume = data.volume
        val avgVolume = data.avgVolume

        // Расчёт VWAP как weighted average на основе доступных данных
        // VWAP = cumulative(Volume * Price) / cumulative(Volume)
        // Используем volume/avgVolume ratio как прокси для cumulative
        val vwap = calculateVWAP(currentPrice, volume, avgVolume)

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
        val vwap = calculateVWAP(data.currentPrice, data.volume, data.avgVolume)
        val deviation = (data.currentPrice.toDouble() - vwap.toDouble()) / vwap.toDouble() * 100
        return "VWAP deviation analysis; deviation=${"%.2f".format(deviation)}%; threshold=${deviationThreshold * 100}%"
    }

    private fun calculateVWAP(currentPrice: BigDecimal, volume: Long, avgVolume: Long): BigDecimal {
        // Упрощённый расчёт VWAP:
        // Если volume > avgVolume, значит цена смещена от VWAP
        // Используем volume ratio как корректировку к текущей цене
        return if (avgVolume > 0 && volume > 0) {
            val volumeRatio = volume.toDouble() / avgVolume.toDouble()
            // Если volume > avgVolume, цена выше VWAP;反之亦然
            val adjustment = (volumeRatio - 1.0) * 0.01 // 1% adjustment per volume ratio unit
            currentPrice.multiply(BigDecimal(1.0 - adjustment))
        } else {
            currentPrice // fallback
        }
    }
}
