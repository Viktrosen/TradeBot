package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.config.PositionSizingConfig
import ru.bolotov.tradebot.strategy.MarketData
import java.math.BigDecimal
import kotlin.math.min

private val logger = KotlinLogging.logger {}

@Service
class PositionSizingService(
    private val config: PositionSizingConfig
) {

    fun calculatePositionSize(
        marketData: MarketData,
        availableCapital: BigDecimal,
        currentPositions: Map<String, OpenPosition>
    ): PositionSize {
        val price = marketData.currentPrice
        val atr = marketData.atr ?: (price * BigDecimal("0.01"))
        val stopDistance = atr * BigDecimal("1.5")
        val stopLossPrice = price - stopDistance

        val riskPerTrade = config.riskPerTrade.toBigDecimal()
        val riskAmount = availableCapital * riskPerTrade
        val riskBasedQuantity = (riskAmount / stopDistance).toLong()

        val maxPositions = config.maxPositions
        val usedCapital = currentPositions.values.sumOf {
            (it.entryPrice * BigDecimal.valueOf(it.quantity)).toDouble()
        }.toBigDecimal()
        val freeCapital = availableCapital - usedCapital
        val targetCapitalPerPosition = freeCapital / (maxPositions - currentPositions.size).coerceAtLeast(1).toBigDecimal()
        val allocationBasedQuantity = (targetCapitalPerPosition / price).toLong()

        val rawQuantity = minOf(riskBasedQuantity, allocationBasedQuantity)
            .coerceAtLeast(1L)
            .coerceAtMost((config.maxPositionSize.toBigDecimal() / price).toLong())

        val positionValue = price * BigDecimal.valueOf(rawQuantity)
        val finalQuantity = if (positionValue < config.minPositionSize.toBigDecimal() && availableCapital > config.minPositionSize.toBigDecimal()) {
            (config.minPositionSize.toBigDecimal() / price).toLong().coerceAtLeast(1L)
        } else {
            rawQuantity
        }

        val finalValue = price * BigDecimal.valueOf(finalQuantity)
        val capitalUsagePercent = (finalValue / availableCapital * BigDecimal(100)).toDouble()

        logger.info { "📊 Расчёт позиции для ${marketData.instrumentName}" }
        logger.info { "   Цена: $price, ATR: $atr, стоп: $stopDistance (${stopLossPrice})" }
        logger.info { "   Риск-ориент: $riskBasedQuantity лотов (${"%.0f".format(riskAmount)} ₽)" }
        logger.info { "   Капитал-ориент: $allocationBasedQuantity лотов (${"%.0f".format(targetCapitalPerPosition)} ₽)" }
        logger.info { "   Итог: $finalQuantity лотов на ${"%.0f".format(finalValue)} ₽ (${"%.1f".format(capitalUsagePercent)}%)" }

        return PositionSize(
            quantity = finalQuantity,
            value = finalValue,
            stopLossPrice = stopLossPrice,
            atr = atr,
            capitalUsagePercent = capitalUsagePercent
        )
    }

    fun canOpenNewPosition(
        availableCapital: BigDecimal,
        currentPositions: Map<String, OpenPosition>
    ): Boolean {
        val usedCapital = currentPositions.values.sumOf {
            (it.entryPrice * BigDecimal.valueOf(it.quantity)).toDouble()
        }.toBigDecimal()

        val capitalUsage = usedCapital / availableCapital
        val maxUsage = config.maxCapitalUsage.toBigDecimal()

        if (capitalUsage > maxUsage) {
            logger.debug { "⚠️ Превышен лимит капитала: ${"%.1f".format(capitalUsage * BigDecimal(100))}% > ${"%.1f".format(maxUsage * BigDecimal(100))}%" }
            return false
        }

        if (currentPositions.size >= config.maxPositions) {
            logger.debug { "⚠️ Достигнут максимум позиций: ${currentPositions.size}/${config.maxPositions}" }
            return false
        }

        return true
    }
}

data class PositionSize(
    val quantity: Long,
    val value: BigDecimal,
    val stopLossPrice: BigDecimal?,
    val atr: BigDecimal?,
    val capitalUsagePercent: Double
)