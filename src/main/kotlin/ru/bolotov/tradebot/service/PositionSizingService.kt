package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.config.PositionSizingConfig
import ru.bolotov.tradebot.strategy.MarketData
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

@Service
class PositionSizingService(
    private val config: PositionSizingConfig,
    private val eventPublisherService: EventPublisherService
) {
    fun calculatePositionSize(
        marketData: MarketData,
        portfolioCapital: BigDecimal,
        currentPositions: Map<String, OpenPosition>
    ): PositionSizingResult {
        if (currentPositions.size >= config.maxPositions) {
            return reject(PositionSizingRejection.MAX_POSITIONS_REACHED)
        }

        val lotPrice = marketData.currentPrice * marketData.lotSize.toBigDecimal()
        if (lotPrice <= BigDecimal.ZERO || portfolioCapital <= BigDecimal.ZERO) {
            return reject(PositionSizingRejection.INVALID_MARKET_DATA)
        }

        val capitalBudget = calculateCapitalBudget(portfolioCapital, currentPositions)
            ?: return rejectCapitalLimit(portfolioCapital, currentPositions)
        val positionBudget = portfolioCapital * config.positionSizePercent.toBigDecimal()
        val allowedBudget = minOf(positionBudget, capitalBudget)
        val quantity = lotsFor(allowedBudget, lotPrice)

        if (quantity == 0L) {
            return reject(PositionSizingRejection.POSITION_SIZE_TOO_SMALL_FOR_LOT)
        }

        val positionSize = createPositionSize(
            quantity = quantity,
            lotPrice = lotPrice,
            portfolioCapital = portfolioCapital,
            marketData = marketData
        )
        logCalculatedPosition(marketData, positionBudget, capitalBudget, positionSize)
        return PositionSizingResult.Allowed(positionSize)
    }

    fun applyBrokerLimits(
        positionSize: PositionSize,
        marketData: MarketData,
        portfolioCapital: BigDecimal,
        brokerLimits: BrokerLotLimits?
    ): PositionSizingResult {
        if (brokerLimits == null) {
            return PositionSizingResult.Allowed(positionSize)
        }

        val lotPrice = marketData.currentPrice * marketData.lotSize.toBigDecimal()
        val brokerLots = brokerLotsAvailable(brokerLimits)
        val cashLots = cashLotsAvailable(brokerLimits, lotPrice)
        val finalQuantity = minOf(positionSize.quantity, brokerLots, cashLots)
        if (finalQuantity == 0L) {
            return reject(PositionSizingRejection.BROKER_LIMIT_EXCEEDED)
        }

        val finalValue = lotPrice * finalQuantity.toBigDecimal()
        if (finalQuantity < positionSize.quantity) {
            logger.info {
                "Лимиты брокера уменьшили позицию ${marketData.instrumentName}: " +
                    "${positionSize.quantity} -> $finalQuantity лотов"
            }
        }

        return PositionSizingResult.Allowed(
            positionSize.copy(
                quantity = finalQuantity,
                value = finalValue,
                capitalUsagePercent = capitalUsagePercent(finalValue, portfolioCapital)
            )
        )
    }

    private fun calculateCapitalBudget(
        portfolioCapital: BigDecimal,
        currentPositions: Map<String, OpenPosition>
    ): BigDecimal? {
        val usedCapital = usedCapital(currentPositions)
        val maximumCapital = portfolioCapital * config.maxCapitalUsage.toBigDecimal()
        return (maximumCapital - usedCapital).takeIf { it > BigDecimal.ZERO }
    }

    private fun rejectCapitalLimit(
        portfolioCapital: BigDecimal,
        currentPositions: Map<String, OpenPosition>
    ): PositionSizingResult.Rejected {
        val usagePercent = capitalUsagePercent(usedCapital(currentPositions), portfolioCapital)
        eventPublisherService.publishCapitalUsageLimitReached(
            usagePercent,
            config.maxCapitalUsage * PERCENT_MULTIPLIER.toDouble()
        )
        return reject(PositionSizingRejection.CAPITAL_USAGE_LIMIT_EXCEEDED)
    }

    private fun usedCapital(currentPositions: Map<String, OpenPosition>): BigDecimal =
        currentPositions.values.fold(BigDecimal.ZERO) { total, position ->
            total + position.entryPrice * position.quantity.toBigDecimal() * position.lotSize.toBigDecimal()
        }

    private fun createPositionSize(
        quantity: Long,
        lotPrice: BigDecimal,
        portfolioCapital: BigDecimal,
        marketData: MarketData
    ): PositionSize {
        val value = lotPrice * quantity.toBigDecimal()
        val stopLossPrice = marketData.currentPrice * (
            BigDecimal.ONE - config.stopLossPercent.toBigDecimal()
        )
        return PositionSize(
            quantity = quantity,
            value = value,
            stopLossPrice = stopLossPrice,
            atr = marketData.atr,
            capitalUsagePercent = capitalUsagePercent(value, portfolioCapital)
        )
    }

    private fun lotsFor(amount: BigDecimal, lotPrice: BigDecimal): Long =
        amount.divide(lotPrice, LOT_SCALE, RoundingMode.DOWN).toLong()

    private fun brokerLotsAvailable(brokerLimits: BrokerLotLimits): Long =
        (brokerLimits.maxBuyLots.toBigDecimal() * config.brokerLimitUsage.toBigDecimal())
            .setScale(0, RoundingMode.DOWN)
            .toLong()

    private fun cashLotsAvailable(
        brokerLimits: BrokerLotLimits,
        lotPrice: BigDecimal
    ): Long = lotsFor(
        amount = (brokerLimits.availableBuyMoney - config.minOrderCashBuffer.toBigDecimal())
            .coerceAtLeast(BigDecimal.ZERO),
        lotPrice = lotPrice
    )

    private fun capitalUsagePercent(value: BigDecimal, portfolioCapital: BigDecimal): Double =
        if (portfolioCapital > BigDecimal.ZERO) {
            value
                .multiply(PERCENT_MULTIPLIER)
                .divide(portfolioCapital, MONEY_SCALE, RoundingMode.HALF_UP)
                .toDouble()
        } else {
            0.0
        }

    private fun logCalculatedPosition(
        marketData: MarketData,
        positionBudget: BigDecimal,
        capitalBudget: BigDecimal,
        positionSize: PositionSize
    ) {
        logger.info {
            "Расчёт позиции ${marketData.instrumentName}: " +
                "доля=${config.positionSizePercent * PERCENT_MULTIPLIER.toDouble()}%, " +
                "бюджет позиции=$positionBudget ₽, " +
                "остаток лимита=$capitalBudget ₽, " +
                "итог=${positionSize.quantity} лотов на ${positionSize.value} ₽"
        }
    }

    private fun reject(reason: PositionSizingRejection): PositionSizingResult.Rejected =
        PositionSizingResult.Rejected(reason)

    private companion object {
        val PERCENT_MULTIPLIER = BigDecimal("100")
        const val LOT_SCALE = 0
        const val MONEY_SCALE = 8
    }
}

data class PositionSize(
    val quantity: Long,
    val value: BigDecimal,
    val stopLossPrice: BigDecimal?,
    val atr: BigDecimal?,
    val capitalUsagePercent: Double
)
