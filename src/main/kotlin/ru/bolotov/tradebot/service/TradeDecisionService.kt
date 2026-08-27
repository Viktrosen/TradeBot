package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.broker.*

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.service.data.ShortRiskCheck
import ru.bolotov.tradebot.config.PositionSizingConfig
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.Signal
import ru.bolotov.tradebot.strategy.regime.MarketRegime
import java.math.BigDecimal

private val tradeDecisionLogger = KotlinLogging.logger {}

@Service
class TradeDecisionService(
    private val positionSizingService: PositionSizingService,
    private val orderExecutionService: OrderExecutionService,
    private val operationsService: ru.ttech.piapi.core.OperationsServiceSync,
    private val positionLifecycleService: PositionLifecycleService,
    private val positionSizingConfig: PositionSizingConfig,
    private val shortTradingRiskService: ShortTradingRiskService
) {

    /**
     * Применяет риск-проверки, рассчитывает допустимый объём и передаёт заявку
     * в жизненный цикл позиции. Для короткой позиции дополнительно проверяется
     * маржинальная доступность у брокера.
     */
    suspend fun executeTrade(
        accountId: String,
        marketData: MarketData,
        signal: Signal,
        strategyId: String,
        strategyName: String,
        strategyExplanation: String,
        marketRegime: MarketRegime,
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
                strategyId = strategyId,
                strategyName = strategyName,
                strategyExplanation = strategyExplanation,
                marketRegime = marketRegime,
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
        strategyId: String,
        strategyName: String,
        strategyExplanation: String,
        marketRegime: MarketRegime,
        currentPositions: Map<String, OpenPosition>
    ): TradeDecisionResult {
        val side = signalDirection.toPositionSide()
        if (side == PositionSide.SHORT) {
            val rejection = shortOpenRejection(accountId, marketData.instrumentId)
            if (rejection != null) {
                tradeDecisionLogger.warn {
                    "Шорт ${marketData.instrumentName} пропущен: $rejection"
                }
                return TradeDecisionResult()
            }
        }

        val portfolioCapital = getPortfolioCapital(accountId)
        val calculatedPositionSize = positionSizingService.calculatePositionSize(
            marketData = marketData,
            portfolioCapital = portfolioCapital,
            currentPositions = currentPositions,
            side = side
        ).positionSizeOrNull(marketData.instrumentName) ?: return TradeDecisionResult()

        val brokerLimits = orderExecutionService.getBrokerLotLimits(
            accountId = accountId,
            instrumentId = marketData.instrumentId,
            price = marketData.currentPrice,
            direction = signalDirection.name
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
            strategyId = strategyId,
            strategyName = strategyName,
            strategyExplanation = strategyExplanation,
            marketRegime = marketRegime
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

    private fun OrderDirection.toPositionSide(): PositionSide = when (this) {
        OrderDirection.BUY -> PositionSide.LONG
        OrderDirection.SELL -> PositionSide.SHORT
    }

    private fun shortOpenRejection(accountId: String, instrumentId: String): String? {
        if (!positionSizingConfig.shortTradingEnabled) {
            return "торговля в шорт выключена в настройках риска"
        }
        return when (val check = shortTradingRiskService.canOpenShort(accountId, instrumentId)) {
            is ShortRiskCheck.Allowed -> null
            is ShortRiskCheck.Rejected -> check.reason
        }
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
            operationsService.getPortfolioSync(accountId).totalAmountPortfolio.let { amount ->
                BigDecimal.valueOf(amount.units).add(BigDecimal.valueOf(amount.nano.toLong(), 9))
            }
        } catch (error: Exception) {
            tradeDecisionLogger.error(error) { "Не удалось получить стоимость портфеля" }
            BigDecimal.ZERO
        }
}

data class TradeDecisionResult(
    val openedPosition: OpenPosition? = null,
    val closedInstrumentId: String? = null
)
