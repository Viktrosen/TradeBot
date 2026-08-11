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

        val stopDistance = calculateStopDistance(marketData)
        val riskPerLot = stopDistance * marketData.lotSize.toBigDecimal()
        if (riskPerLot <= BigDecimal.ZERO) {
            return reject(PositionSizingRejection.INVALID_MARKET_DATA)
        }

        val capitalBudget = calculateCapitalBudget(portfolioCapital, currentPositions)
            ?: return rejectCapitalLimit(portfolioCapital, currentPositions)
        val minimumLots = minimumLotsFor(lotPrice)
        val limits = calculateLotLimits(
            lotPrice = lotPrice,
            riskPerLot = riskPerLot,
            capitalBudget = capitalBudget,
            portfolioCapital = portfolioCapital
        )

        val rejection = rejectionForMinimum(limits, minimumLots)
        if (rejection != null) {
            return reject(rejection)
        }

        val quantity = minOf(
            limits.riskLots,
            limits.capitalLots,
            limits.maxPositionLots
        )
        val positionSize = createPositionSize(
            quantity = quantity,
            lotPrice = lotPrice,
            portfolioCapital = portfolioCapital,
            marketData = marketData,
            stopDistance = stopDistance
        )

        logCalculatedPosition(marketData, limits, positionSize)
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
        val finalValue = lotPrice * finalQuantity.toBigDecimal()

        if (finalValue < config.minPositionSize.toBigDecimal()) {
            return reject(PositionSizingRejection.BROKER_LIMIT_EXCEEDED)
        }

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

    private fun calculateStopDistance(marketData: MarketData): BigDecimal {
        val atr = marketData.atr ?: marketData.currentPrice * DEFAULT_ATR_PERCENT
        return atr * STOP_LOSS_ATR_MULTIPLIER
    }

    private fun calculateCapitalBudget(
        portfolioCapital: BigDecimal,
        currentPositions: Map<String, OpenPosition>
    ): BigDecimal? {
        val usedCapital = currentPositions.values.fold(BigDecimal.ZERO) { total, position ->
            total + position.entryPrice * position.quantity.toBigDecimal() * position.lotSize.toBigDecimal()
        }
        val maximumCapital = portfolioCapital * config.maxCapitalUsage.toBigDecimal()
        return (maximumCapital - usedCapital).takeIf { it > BigDecimal.ZERO }
    }

    private fun rejectCapitalLimit(
        portfolioCapital: BigDecimal,
        currentPositions: Map<String, OpenPosition>
    ): PositionSizingResult.Rejected {
        val usedCapital = currentPositions.values.fold(BigDecimal.ZERO) { total, position ->
            total + position.entryPrice * position.quantity.toBigDecimal() * position.lotSize.toBigDecimal()
        }
        val usagePercent = capitalUsagePercent(usedCapital, portfolioCapital)
        eventPublisherService.publishCapitalUsageLimitReached(
            usagePercent,
            config.maxCapitalUsage * PERCENT_MULTIPLIER.toDouble()
        )
        return reject(PositionSizingRejection.CAPITAL_USAGE_LIMIT_EXCEEDED)
    }

    private fun calculateLotLimits(
        lotPrice: BigDecimal,
        riskPerLot: BigDecimal,
        capitalBudget: BigDecimal,
        portfolioCapital: BigDecimal
    ): LotLimits {
        val riskAmount = portfolioCapital * config.riskPerTrade.toBigDecimal()

        return LotLimits(
            riskLots = lotsFor(riskAmount, riskPerLot),
            capitalLots = lotsFor(capitalBudget, lotPrice),
            maxPositionLots = lotsFor(config.maxPositionSize.toBigDecimal(), lotPrice)
        )
    }

    private fun minimumLotsFor(lotPrice: BigDecimal): Long =
        config.minPositionSize.toBigDecimal()
            .divide(lotPrice, LOT_SCALE, RoundingMode.CEILING)
            .toLong()

    private fun lotsFor(amount: BigDecimal, lotPrice: BigDecimal): Long =
        amount.divide(lotPrice, LOT_SCALE, RoundingMode.DOWN).toLong()

    private fun rejectionForMinimum(
        limits: LotLimits,
        minimumLots: Long
    ): PositionSizingRejection? = when {
        limits.riskLots < minimumLots -> PositionSizingRejection.RISK_LIMIT_EXCEEDED
        limits.capitalLots < minimumLots ->
            PositionSizingRejection.INSUFFICIENT_CAPITAL_FOR_MIN_POSITION
        limits.maxPositionLots < minimumLots -> PositionSizingRejection.MAX_POSITION_SIZE_EXCEEDED
        else -> null
    }

    private fun createPositionSize(
        quantity: Long,
        lotPrice: BigDecimal,
        portfolioCapital: BigDecimal,
        marketData: MarketData,
        stopDistance: BigDecimal
    ): PositionSize {
        val value = lotPrice * quantity.toBigDecimal()
        return PositionSize(
            quantity = quantity,
            value = value,
            stopLossPrice = marketData.currentPrice - stopDistance,
            atr = marketData.atr,
            capitalUsagePercent = capitalUsagePercent(value, portfolioCapital)
        )
    }

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
        limits: LotLimits,
        positionSize: PositionSize
    ) {
        logger.info {
            "Расчёт позиции ${marketData.instrumentName}: " +
                "риск=${limits.riskLots}, капитал=${limits.capitalLots}, " +
                "максимум=${limits.maxPositionLots}, итог=${positionSize.quantity} лотов " +
                "на ${positionSize.value} ₽"
        }
    }

    private fun reject(reason: PositionSizingRejection): PositionSizingResult.Rejected =
        PositionSizingResult.Rejected(reason)

    private data class LotLimits(
        val riskLots: Long,
        val capitalLots: Long,
        val maxPositionLots: Long
    )

    private companion object {
        val DEFAULT_ATR_PERCENT = BigDecimal("0.01")
        val STOP_LOSS_ATR_MULTIPLIER = BigDecimal("1.5")
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
