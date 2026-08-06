package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.Signal
import java.math.BigDecimal

private val tradeDecisionLogger = KotlinLogging.logger {}

@Service
class TradeDecisionService(
    private val positionSizingService: PositionSizingService,
    private val orderExecutionService: OrderExecutionService,
    private val operationsService: ru.tinkoff.piapi.core.OperationsService,
    private val positionLifecycleService: PositionLifecycleService
) {

    suspend fun executeTrade(
        accountId: String,
        marketData: MarketData,
        signal: Signal,
        strategyName: String,
        strategyExplanation: String,
        currentPositions: Map<String, OpenPosition>
    ): TradeDecisionResult {
        tradeDecisionLogger.info {
            "Исполнение торгового решения: ${marketData.instrumentName}, сигнал=${signal.direction}, уверенность=${signal.confidence}"
        }
        val currentPosition = currentPositions[marketData.instrumentId]
        val signalDirection = when (signal.direction) {
            ru.bolotov.tradebot.strategy.OrderDirection.BUY -> OrderDirection.BUY
            ru.bolotov.tradebot.strategy.OrderDirection.SELL -> OrderDirection.SELL
            ru.bolotov.tradebot.strategy.OrderDirection.HOLD -> return TradeDecisionResult()
        }

        return when {
            currentPosition == null -> openIfAllowed(
                accountId,
                marketData,
                signal,
                signalDirection,
                strategyName,
                strategyExplanation,
                currentPositions
            )

            currentPosition.direction != signalDirection -> {
                tradeDecisionLogger.info {
                    "Закрытие позиции по сигналу: ${currentPosition.direction} -> $signalDirection"
                }
                val result = positionLifecycleService.closePosition(accountId, currentPosition, "SIGNAL_CLOSE")
                TradeDecisionResult(closedInstrumentId = result.position.instrumentId.takeIf { result.removeFromState })
            }

            else -> {
                tradeDecisionLogger.debug {
                    "Сигнал ${signal.direction} совпадает с текущей позицией, удерживаем позицию"
                }
                TradeDecisionResult()
            }
        }
    }

    private suspend fun openIfAllowed(
        accountId: String,
        marketData: MarketData,
        signal: Signal,
        signalDirection: OrderDirection,
        strategyName: String,
        strategyExplanation: String,
        currentPositions: Map<String, OpenPosition>
    ): TradeDecisionResult {
        if (signalDirection == OrderDirection.SELL) {
            tradeDecisionLogger.warn {
                "SELL для ${marketData.instrumentName} не исполнен: длинная позиция не была синхронизирована"
            }
            return TradeDecisionResult()
        }

        val availableCapital = getAvailableCapital(accountId)
        if (!positionSizingService.canOpenNewPosition(availableCapital, currentPositions)) {
            tradeDecisionLogger.warn {
                "Нельзя открыть новую позицию: сработал лимит капитала или количества позиций"
            }
            return TradeDecisionResult()
        }

        val calculatedPositionSize = positionSizingService.calculatePositionSize(
            marketData = marketData,
            availableCapital = availableCapital,
            currentPositions = currentPositions
        )
        val brokerLimits = orderExecutionService.getBrokerLotLimits(
            accountId = accountId,
            instrumentId = marketData.instrumentId,
            price = marketData.currentPrice
        ) ?: run {
            tradeDecisionLogger.warn {
                "Лимиты брокера недоступны для ${marketData.instrumentName}, открытие пропущено"
            }
            return TradeDecisionResult()
        }

        val positionSize = positionSizingService.applyBrokerLimits(
            positionSize = calculatedPositionSize,
            marketData = marketData,
            availableCapital = availableCapital,
            brokerLimits = brokerLimits
        )
        if (positionSize.quantity <= 0) {
            tradeDecisionLogger.warn {
                "Лимиты брокера или риска не позволяют открыть ${marketData.instrumentName}"
            }
            return TradeDecisionResult()
        }

        val estimatedOrderAmount = orderExecutionService.estimateOrderAmount(
            accountId = accountId,
            instrumentId = marketData.instrumentId,
            quantity = positionSize.quantity,
            price = marketData.currentPrice,
            direction = "BUY"
        ) ?: positionSize.value

        if (!hasEnoughFunds(accountId, estimatedOrderAmount)) {
            tradeDecisionLogger.warn {
                "Недостаточно средств: нужно $estimatedOrderAmount, доступно $availableCapital"
            }
            return TradeDecisionResult()
        }

        val openedPosition = positionLifecycleService.openPosition(
            accountId = accountId,
            marketData = marketData,
            signal = signal,
            positionSize = positionSize,
            strategyName = strategyName,
            strategyExplanation = strategyExplanation
        )
        return TradeDecisionResult(openedPosition = openedPosition)
    }

    private suspend fun getAvailableCapital(accountId: String): BigDecimal =
        try {
            operationsService.getPortfolioSync(accountId).totalAmountCurrencies?.value ?: BigDecimal.ZERO
        } catch (e: Exception) {
            tradeDecisionLogger.error(e) { "Ошибка получения баланса" }
            BigDecimal.ZERO
        }

    private suspend fun hasEnoughFunds(accountId: String, requiredAmount: BigDecimal): Boolean =
        try {
            val cash = operationsService.getPortfolioSync(accountId).totalAmountCurrencies?.value ?: BigDecimal.ZERO
            cash >= requiredAmount
        } catch (e: Exception) {
            tradeDecisionLogger.error(e) { "Ошибка проверки баланса" }
            false
        }
}

data class TradeDecisionResult(
    val openedPosition: OpenPosition? = null,
    val closedInstrumentId: String? = null
)

