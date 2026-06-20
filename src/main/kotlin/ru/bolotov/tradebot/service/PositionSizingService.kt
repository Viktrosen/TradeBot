package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.config.PositionSizingConfig
import ru.bolotov.tradebot.strategy.MarketData
import java.math.BigDecimal

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
        val lotSize = marketData.lotSize.toBigDecimal()
        val lotPrice = price * lotSize
        val atr = marketData.atr ?: (price * BigDecimal("0.01"))
        val stopDistance = atr * BigDecimal("1.5")
        val stopLossPrice = price - stopDistance

        val riskPerTrade = config.riskPerTrade.toBigDecimal()
        val riskAmount = availableCapital * riskPerTrade
        val riskPerLot = stopDistance * lotSize
        val riskBasedQuantity = (riskAmount / riskPerLot).toLong()

        val maxPositions = config.maxPositions
        val usedCapital = currentPositions.values.sumOf {
            (it.entryPrice * BigDecimal.valueOf(it.quantity) * BigDecimal.valueOf(it.lotSize.toLong())).toDouble()
        }.toBigDecimal()
        val freeCapital = availableCapital - usedCapital
        val targetCapitalPerPosition = freeCapital / (maxPositions - currentPositions.size).coerceAtLeast(1).toBigDecimal()
        val allocationBasedQuantity = (targetCapitalPerPosition / lotPrice).toLong()

        val rawQuantity = minOf(riskBasedQuantity, allocationBasedQuantity)
            .coerceAtLeast(1L)
            .coerceAtMost((config.maxPositionSize.toBigDecimal() / lotPrice).toLong())

        val positionValue = lotPrice * BigDecimal.valueOf(rawQuantity)
        val finalQuantity = if (
            config.allowMinPositionSizeUpscale &&
            positionValue < config.minPositionSize.toBigDecimal() &&
            availableCapital > config.minPositionSize.toBigDecimal()
        ) {
            (config.minPositionSize.toBigDecimal() / lotPrice).toLong().coerceAtLeast(1L)
        } else {
            rawQuantity
        }

        val finalValue = lotPrice * BigDecimal.valueOf(finalQuantity)
        val capitalUsagePercent = (finalValue / availableCapital * BigDecimal(100)).toDouble()

        logger.info { "📊 Расчёт позиции для ${marketData.instrumentName}" }
        logger.info { "   Цена: $price, лотность: ${marketData.lotSize}, цена лота: $lotPrice, ATR: $atr, стоп: $stopDistance (${stopLossPrice})" }
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

    fun applyBrokerLimits(
        positionSize: PositionSize,
        marketData: MarketData,
        availableCapital: BigDecimal,
        brokerLimits: BrokerLotLimits?
    ): PositionSize {
        if (brokerLimits == null) return positionSize

        val lotPrice = marketData.currentPrice * marketData.lotSize.toBigDecimal()
        val safeBrokerLots = (brokerLimits.maxBuyLots.toBigDecimal() * config.brokerLimitUsage.toBigDecimal()).toLong()
        val cashLimitedLots = ((brokerLimits.availableBuyMoney - config.minOrderCashBuffer.toBigDecimal())
            .coerceAtLeast(BigDecimal.ZERO) / lotPrice).toLong()
        val finalQuantity = minOf(positionSize.quantity, safeBrokerLots, cashLimitedLots).coerceAtLeast(0L)
        val finalValue = lotPrice * finalQuantity.toBigDecimal()

        if (finalQuantity < positionSize.quantity) {
            logger.info {
                "Broker limits reduced ${marketData.instrumentName}: " +
                        "${positionSize.quantity} -> $finalQuantity lots, available=${brokerLimits.availableBuyMoney}"
            }
        }

        return positionSize.copy(
            quantity = finalQuantity,
            value = finalValue,
            capitalUsagePercent = if (availableCapital > BigDecimal.ZERO) {
                (finalValue / availableCapital * BigDecimal(100)).toDouble()
            } else {
                0.0
            }
        )
    }

    fun canOpenNewPosition(
        availableCapital: BigDecimal,
        currentPositions: Map<String, OpenPosition>
    ): Boolean {
        val usedCapital = currentPositions.values.sumOf {
            (it.entryPrice * BigDecimal.valueOf(it.quantity) * BigDecimal.valueOf(it.lotSize.toLong())).toDouble()
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
