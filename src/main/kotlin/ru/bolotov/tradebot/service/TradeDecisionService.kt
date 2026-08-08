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
            "Исполнение торгового решения: ${marketData.instrumentName}, " +
                "сигнал=${signal.direction}, уверенность=${signal.confidence}"
        }

        val signalDirection = signal.toOrderDirection() ?: return TradeDecisionResult()
        val currentPosition = currentPositions[marketData.instrumentId]

        return when {
            currentPosition == null -> openPosition(
                accountId = accountId,
                marketData = marketData,
                signal = signal,
                signalDirection = signalDirection,
                strategyName = strategyName,
                strategyExplanation = strategyExplanation,
                currentPositions = currentPositions
            )

            currentPosition.direction != signalDirection -> closePosition(
                accountId = accountId,
                position = currentPosition
            )

            else -> TradeDecisionResult()
        }
    }

    private suspend fun openPosition(
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
                "Продажа ${marketData.instrumentName} пропущена: короткие позиции не поддерживаются"
            }
            return TradeDecisionResult()
        }

        val portfolioCapital = getPortfolioCapital(accountId)
        val calculatedPositionSize = positionSizingService.calculatePositionSize(
            marketData = marketData,
            portfolioCapital = portfolioCapital,
            currentPositions = currentPositions
        ).positionSizeOrNull(marketData.instrumentName) ?: return TradeDecisionResult()

        val brokerLimits = orderExecutionService.getBrokerLotLimits(
            accountId = accountId,
            instrumentId = marketData.instrumentId,
            price = marketData.currentPrice
        ) ?: run {
            tradeDecisionLogger.warn {
                "Лимиты брокера недоступны для ${marketData.instrumentName}; открытие пропущено"
            }
            return TradeDecisionResult()
        }

        val positionSize = positionSizingService.applyBrokerLimits(
            positionSize = calculatedPositionSize,
            marketData = marketData,
            portfolioCapital = portfolioCapital,
            brokerLimits = brokerLimits
        ).positionSizeOrNull(marketData.instrumentName) ?: return TradeDecisionResult()

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

    private suspend fun closePosition(
        accountId: String,
        position: OpenPosition
    ): TradeDecisionResult {
        tradeDecisionLogger.info {
            "Закрытие позиции ${position.instrumentName} по обратному сигналу"
        }
        val result = positionLifecycleService.closePosition(accountId, position, "SIGNAL_CLOSE")
        return TradeDecisionResult(
            closedInstrumentId = result.position.instrumentId.takeIf { result.removeFromState }
        )
    }

    private fun Signal.toOrderDirection(): OrderDirection? = when (direction) {
        ru.bolotov.tradebot.strategy.OrderDirection.BUY -> OrderDirection.BUY
        ru.bolotov.tradebot.strategy.OrderDirection.SELL -> OrderDirection.SELL
        ru.bolotov.tradebot.strategy.OrderDirection.HOLD -> null
    }

    private fun PositionSizingResult.positionSizeOrNull(
        instrumentName: String
    ): PositionSize? = when (this) {
        is PositionSizingResult.Allowed -> positionSize
        is PositionSizingResult.Rejected -> {
            tradeDecisionLogger.warn {
                "Открытие позиции $instrumentName отменено: ${reason.description}"
            }
            null
        }
    }

    private suspend fun getPortfolioCapital(accountId: String): BigDecimal =
        try {
            operationsService.getPortfolioSync(accountId).totalAmountCurrencies?.value
                ?: BigDecimal.ZERO
        } catch (error: Exception) {
            tradeDecisionLogger.error(error) { "Не удалось получить стоимость портфеля" }
            BigDecimal.ZERO
        }
}

data class TradeDecisionResult(
    val openedPosition: OpenPosition? = null,
    val closedInstrumentId: String? = null
)
