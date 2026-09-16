package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.BotOperationEventType
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.service.data.CloseExecutionTotals
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.MarketDataProvider
import ru.bolotov.tradebot.strategy.Signal
import ru.bolotov.tradebot.strategy.regime.MarketRegime
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val positionLifecycleLogger = KotlinLogging.logger {}

/** Управляет жизненным циклом позиции: открытие, исполнение, закрытие и фиксация P&L. */
@Service
class PositionLifecycleService(
    private val marketDataProvider: MarketDataProvider,
    private val orderExecutionService: OrderExecutionService,
    private val tradeEventService: TradeEventService,
    private val eventPublisherService: EventPublisherService,
    private val positionProtectionService: PositionProtectionService,
    private val operationJournal: BotOperationJournal
) {
    private val closingPositionIds = ConcurrentHashMap.newKeySet<String>()
    private val openingInstrumentKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * Открывает позицию с защитой от параллельных заявок по одному инструменту,
     * ждёт фактического исполнения и создаёт брокерские SL/TP в production.
     */
    /** Открывает позицию, ждёт фактического исполнения и ставит защиту в production. */
    suspend fun openPosition(
        accountId: String,
        marketData: MarketData,
        signal: Signal,
        positionSize: PositionSize,
        strategyId: String,
        strategyName: String,
        strategyExplanation: String,
        marketRegime: MarketRegime
    ): OpenPosition? {
        val operationKey = "$accountId:${marketData.instrumentId}"
        if (!openingInstrumentKeys.add(operationKey)) {
            positionLifecycleLogger.warn {
                "Открытие ${marketData.instrumentName} пропущено: операция по инструменту уже выполняется"
            }
            return null
        }

        return try {
            openPositionInternal(
                accountId = accountId,
                marketData = marketData,
                signal = signal,
                positionSize = positionSize,
                strategyId = strategyId,
                strategyName = strategyName,
                strategyExplanation = strategyExplanation,
                marketRegime = marketRegime
            )
        } finally {
            openingInstrumentKeys.remove(operationKey)
        }
    }

    private suspend fun openPositionInternal(
        accountId: String,
        marketData: MarketData,
        signal: Signal,
        positionSize: PositionSize,
        strategyId: String,
        strategyName: String,
        strategyExplanation: String,
        marketRegime: MarketRegime
    ): OpenPosition? {
        val side = signal.toPositionSideOrNull() ?: run {
            positionLifecycleLogger.error {
                "Открытие позиции ${marketData.instrumentName} отклонено: " +
                    "неподдерживаемый сигнал ${signal.direction}"
            }
            return null
        }

        val direction = side.openDirection()
        val positionId = UUID.randomUUID().toString()
        val savedEvent = tradeEventService.createPendingOpenEvent(
            positionId = positionId,
            marketData = marketData,
            direction = direction,
            positionSide = side,
            quantity = positionSize.quantity,
            lotSize = marketData.lotSize,
            totalValue = positionSize.value,
            strategyId = strategyId,
            strategyName = strategyName,
            signal = signal,
            strategyExplanation = strategyExplanation,
            marketRegime = marketRegime
        )

        val orderResult = orderExecutionService.placeOrder(
            accountId = accountId,
            instrumentId = marketData.instrumentId,
            quantity = positionSize.quantity,
            price = marketData.currentPrice,
            direction = direction.name,
            isMarginTrade = side == PositionSide.SHORT
        )

        if (!orderResult.success) {
            tradeEventService.markOpenEventFailed(savedEvent, orderResult)
            positionLifecycleLogger.error { "Ошибка открытия позиции: ${orderResult.error}" }
            return null
        }

        val initialFill = waitForOpenFill(accountId, marketData, orderResult)
        val fill = resolveOpenFillAfterTimeout(
            accountId = accountId,
            marketData = marketData,
            orderResult = orderResult,
            initialFill = initialFill,
            pendingEvent = savedEvent
        ) ?: return null
        val executedQuantity = fill.executedLots

        if (!fill.filled) {
            positionLifecycleLogger.warn {
                "Заявка на открытие ${marketData.instrumentName} исполнена частично: " +
                    "$executedQuantity из ${positionSize.quantity} лотов"
            }
        }

        val entryPrice = fill.executedPrice ?: orderResult.executedPrice ?: marketData.currentPrice
        val entryCommission = fill.executedCommission ?: orderResult.executedCommission ?: BigDecimal.ZERO
        val totalValue = entryPrice * executedQuantity.toBigDecimal() * marketData.lotSize.toBigDecimal()
        tradeEventService.markOpenEventProcessed(
            event = savedEvent,
            orderResult = orderResult,
            fill = fill,
            entryPrice = entryPrice,
            quantity = executedQuantity,
            totalValue = totalValue
        )

        val position = OpenPosition(
            positionId = positionId,
            instrumentId = marketData.instrumentId,
            instrumentName = marketData.instrumentName,
            direction = direction,
            side = side,
            entryPrice = entryPrice,
            quantity = executedQuantity,
            lotSize = marketData.lotSize,
            entryCommission = entryCommission,
            entryTime = Instant.now(),
            entryStrategyId = strategyId,
            stopLossPrice = positionSize.stopLossPrice,
            atr = positionSize.atr
        )

        positionLifecycleLogger.info {
            "Открыта позиция: $side ${marketData.instrumentName} " +
                    "($executedQuantity лотов, ${"%.0f".format(totalValue)} RUB, " +
                    "${"%.1f".format(totalValue * BigDecimal(100) / positionSize.value)}% от рассчитанного размера)"
        }
        logOrderExecution(
            operation = "OPEN",
            position = position,
            orderId = orderResult.orderId,
            price = entryPrice,
            executedLots = executedQuantity,
            context = mapOf("strategyId" to strategyId, "partiallyFilled" to !fill.filled)
        )

        eventPublisherService.publishTradeExecuted(savedEvent)
        if (!createBrokerProtection(accountId, position)) {
            return closeUnprotectedPosition(accountId, position)
        }
        return position
    }

    private fun createBrokerProtection(accountId: String, position: OpenPosition): Boolean =
        when (val result = positionProtectionService.createProtection(accountId, position)) {
            ProtectionCreationResult.Created,
            ProtectionCreationResult.SkippedInSandbox -> true

            is ProtectionCreationResult.Failed -> {
                positionLifecycleLogger.error(result.error) {
                    "Позиция ${position.instrumentName} открыта без защитных заявок"
                }
                false
            }
        }

    private suspend fun closeUnprotectedPosition(
        accountId: String,
        position: OpenPosition
    ): OpenPosition? {
        val closeResult = closePositionWithRetry(
            accountId = accountId,
            position = position,
            reason = "PROTECTION_SETUP_FAILED"
        )
        return position.takeUnless { closeResult.closed }
    }

    /** Закрывает позицию рыночной заявкой и записывает результат без повторных попыток. */
    suspend fun closePosition(accountId: String, position: OpenPosition, reason: String = "CLOSE"): ClosePositionResult {
        val marketData = marketDataProvider.fetchMarketData(position.instrumentId)
            ?: return ClosePositionResult(position, closed = false, removeFromState = false)
        return closePositionAtPrice(accountId, position, marketData.currentPrice, reason, marketOrder = false)
    }

    /**
     * Отменяет защитные заявки и закрывает позицию рыночной заявкой с повторными
     * попытками; результат фиксируется единственным CLOSE-событием.
     */
    /** Закрывает позицию с ограниченными повторами для аварийного и ручного потоков. */
    suspend fun closePositionWithRetry(
        accountId: String,
        position: OpenPosition,
        reason: String = "EMERGENCY_CLOSE",
        explanation: String? = null,
        maxRetries: Int = 3
    ): ClosePositionResult {
        val closeDirection = closeDirection(position)
        if (!tryMarkPositionClosing(accountId, position, closeDirection)) {
            return ClosePositionResult(position, closed = false, removeFromState = tradeEventService.hasCloseEvent(position.positionId))
        }
        if (!isMarketCloseSafeAfterProtectionCancellation(accountId, position)) {
            closingPositionIds.remove(position.positionId)
            return ClosePositionResult(position, closed = false, removeFromState = false)
        }

        var lastError: Exception? = null
        var remainingQuantity = position.quantity
        var totalCloseValue = BigDecimal.ZERO
        var totalCloseCommission = BigDecimal.ZERO

        for (attempt in 1..maxRetries) {
            try {
                positionLifecycleLogger.info {
                    "Закрытие позиции ${position.instrumentName}: причина=$reason, " +
                        "$closeDirection $remainingQuantity лотов рыночной заявкой"
                }
                val orderResult = orderExecutionService.placeMarketOrder(
                    accountId = accountId,
                    instrumentId = position.instrumentId,
                    quantity = remainingQuantity,
                    direction = closeDirection,
                    isMarginTrade = position.side == PositionSide.SHORT
                )

                if (orderResult.success) {
                    val fill = waitForCloseFill(
                        accountId = accountId,
                        position = position,
                        orderResult = orderResult,
                        releaseClosingLock = false
                    )
                    if (fill == null) {
                        lastError = Exception("Рыночная заявка на закрытие не исполнена")
                        if (attempt < maxRetries) delay(2000L * attempt)
                        continue
                    }

                    val executedQuantity = minOf(fill.executedLots, remainingQuantity)
                    if (executedQuantity == 0L) {
                        lastError = Exception("Рыночная заявка на закрытие не исполнила ни одного лота")
                        if (attempt < maxRetries) delay(2000L * attempt)
                        continue
                    }

                    accumulateCloseExecution(
                        position = position,
                        orderResult = orderResult,
                        fill = fill,
                        executedQuantity = executedQuantity,
                        totalCloseValue = totalCloseValue,
                        totalCloseCommission = totalCloseCommission
                    ).also { execution ->
                        totalCloseValue = execution.totalValue
                        totalCloseCommission = execution.totalCommission
                    }
                    remainingQuantity -= executedQuantity

                    if (remainingQuantity == 0L) {
                        return finishMarketClose(
                            position = position,
                            totalCloseValue = totalCloseValue,
                            totalCloseCommission = totalCloseCommission,
                            reason = reason,
                            explanation = explanation
                        )
                    }

                    positionLifecycleLogger.warn {
                        "Закрытие позиции ${position.instrumentName} исполнено частично: " +
                            "$executedQuantity лотов, осталось $remainingQuantity"
                    }
                    if (attempt < maxRetries) delay(2000L * attempt)
                    continue
                }

                lastError = Exception(orderResult.error)
                if (attempt < maxRetries) delay(2000L * attempt)
            } catch (e: Exception) {
                lastError = e
                positionLifecycleLogger.warn {
                    "Ошибка при закрытии ${position.instrumentName} (попытка $attempt): ${e.message}"
                }
                if (attempt < maxRetries) delay(2000L * attempt)
            }
        }

        closingPositionIds.remove(position.positionId)
        positionLifecycleLogger.error {
            "Не удалось закрыть позицию ${position.instrumentName} после $maxRetries попыток: ${lastError?.message}"
        }
        return ClosePositionResult(position, closed = false, removeFromState = false)
    }

    /** Закрывает набор позиций параллельными небольшими группами для аварийной остановки. */
    suspend fun closePositionsInChunks(
        accountId: String,
        positions: List<OpenPosition>,
        chunkSize: Int,
        chunkDelayMs: Long
    ): List<ClosePositionResult> {
        val results = mutableListOf<ClosePositionResult>()
        positions.chunked(chunkSize).forEach { chunk ->
            val chunkResults = coroutineScope {
                chunk.map { position ->
                    async(Dispatchers.IO) { closePositionWithRetry(accountId, position) }
                }.awaitAll()
            }
            results.addAll(chunkResults)
            delay(chunkDelayMs)
        }
        return results
    }

    /** Фиксирует закрытие, обнаруженное через исполнение брокерского SL или TP. */
    fun recordBrokerProtectionClose(
        accountId: String,
        position: OpenPosition,
        triggeredOrderId: String,
        reason: String,
        closePrice: BigDecimal,
        closeCommission: BigDecimal,
        executionOrderId: String? = null,
        executionStatus: String? = null,
        executedLots: Long? = null
    ): ClosePositionResult {
        if (tradeEventService.hasCloseEvent(position.positionId)) {
            return ClosePositionResult(position, closed = false, removeFromState = true)
        }
        if (!closingPositionIds.add(position.positionId)) {
            return ClosePositionResult(position, closed = false, removeFromState = false)
        }

        return try {
            positionProtectionService.completeTriggeredProtection(
                accountId = accountId,
                positionId = position.positionId,
                triggeredOrderId = triggeredOrderId,
                instrumentName = position.instrumentName
            )
            val pnl = calculatePnl(position, closePrice, closeCommission)
            val closeEvent = tradeEventService.saveCloseEventOnce(
                position = position,
                closePrice = closePrice,
                pnl = pnl,
                reason = reason,
                explanation = closeExplanation(reason, pnl),
                brokerOrderId = executionOrderId ?: triggeredOrderId,
                executionStatus = executionStatus,
                brokerOrderState = buildProtectionExecutionState(
                    stopOrderId = triggeredOrderId,
                    exchangeOrderId = executionOrderId,
                    executedLots = executedLots,
                    expectedLots = position.quantity
                )
            )
            closeEvent?.let(eventPublisherService::publishTradeExecuted)
            positionLifecycleLogger.info {
                "Защитная заявка брокера исполнилась для ${position.instrumentName}: " +
                    "причина=$reason, цена=$closePrice, комиссия=$closeCommission, P&L=$pnl"
            }
            if (closeEvent != null) {
                logOrderExecution(
                    operation = "BROKER_PROTECTION_CLOSE",
                    position = position,
                    orderId = executionOrderId ?: triggeredOrderId,
                    price = closePrice,
                    executedLots = executedLots ?: position.quantity,
                    context = mapOf("reason" to reason, "pnl" to pnl, "executionStatus" to executionStatus)
                )
            }
            ClosePositionResult(position, closed = closeEvent != null, removeFromState = true)
        } finally {
            closingPositionIds.remove(position.positionId)
        }
    }

    /**
     * Stops trading a local position whose broker quantity is absent or on the
     * opposite side. The broker confirms the state mismatch, but not the close
     * price, so no artificial P&L is stored.
     */
    fun recordBrokerReconciliationClose(
        position: OpenPosition,
        brokerQuantity: BigDecimal
    ): ClosePositionResult {
        val closeEvent = tradeEventService.saveBrokerReconciliationCloseOnce(position, brokerQuantity)
        if (closeEvent != null) {
            operationJournal.warn(
                eventType = BotOperationEventType.RECONCILIATION,
                message = "Локальная позиция ${position.instrumentName} снята с сопровождения: " +
                    "количество брокера=$brokerQuantity несовместимо со стороной ${position.side}",
                instrumentId = position.instrumentId,
                instrumentName = position.instrumentName,
                positionId = position.positionId,
                context = mapOf("brokerQuantity" to brokerQuantity, "side" to position.side.name)
            )
        }
        return ClosePositionResult(position, closed = closeEvent != null, removeFromState = true)
    }

    private suspend fun closePositionAtPrice(
        accountId: String,
        position: OpenPosition,
        closePrice: BigDecimal,
        reason: String,
        marketOrder: Boolean
    ): ClosePositionResult {
        val direction = closeDirection(position)
        if (!tryMarkPositionClosing(accountId, position, direction)) {
            return ClosePositionResult(position, closed = false, removeFromState = tradeEventService.hasCloseEvent(position.positionId))
        }
        if (!isMarketCloseSafeAfterProtectionCancellation(accountId, position)) {
            closingPositionIds.remove(position.positionId)
            return ClosePositionResult(position, closed = false, removeFromState = false)
        }

        try {
            val orderResult = if (marketOrder) {
                orderExecutionService.placeMarketOrder(
                    accountId = accountId,
                    instrumentId = position.instrumentId,
                    quantity = position.quantity,
                    direction = direction,
                    isMarginTrade = position.side == PositionSide.SHORT
                )
            } else {
                orderExecutionService.placeOrder(
                    accountId = accountId,
                    instrumentId = position.instrumentId,
                    quantity = position.quantity,
                    price = closePrice,
                    direction = direction,
                    isMarginTrade = position.side == PositionSide.SHORT
                )
            }

            if (!orderResult.success) {
                closingPositionIds.remove(position.positionId)
                positionLifecycleLogger.error { "Ошибка закрытия позиции: ${orderResult.error}" }
                return ClosePositionResult(position, closed = false, removeFromState = false)
            }

            val fill = waitForCloseFill(accountId, position, orderResult)
                ?: return ClosePositionResult(position, closed = false, removeFromState = false)
            if (!fill.filled || fill.executedLots != position.quantity) {
                return ClosePositionResult(position, closed = false, removeFromState = false)
            }
            val executedPrice = fill.executedPrice ?: closePrice
            val closeCommission = fill.executedCommission ?: BigDecimal.ZERO
            val pnl = calculatePnl(position, executedPrice, closeCommission)
            val closeEvent = tradeEventService.saveCloseEventOnce(position, executedPrice, pnl, reason)
            positionLifecycleLogger.info {
                "Позиция закрыта: ${position.direction} ${position.instrumentName}, P&L: $pnl RUB"
            }
            if (closeEvent != null) {
                logOrderExecution(
                    operation = "CLOSE",
                    position = position,
                    orderId = orderResult.orderId,
                    price = executedPrice,
                    executedLots = fill.executedLots,
                    context = mapOf("reason" to reason, "pnl" to pnl, "marketOrder" to marketOrder)
                )
            }
            closeEvent?.let(eventPublisherService::publishTradeExecuted)
            return ClosePositionResult(position, closed = true, removeFromState = true)
        } catch (e: Exception) {
            closingPositionIds.remove(position.positionId)
            throw e
        }
    }

    private fun tryMarkPositionClosing(accountId: String, position: OpenPosition, closeDirection: String): Boolean {
        if (tradeEventService.hasCloseEvent(position.positionId)) {
            positionLifecycleLogger.warn {
                "Позиция уже имеет CLOSE-событие, повторное закрытие пропущено: ${position.positionId}"
            }
            return false
        }

        val activeCloseOrder = orderExecutionService.findActiveOrder(
            accountId = accountId,
            instrumentId = position.instrumentId,
            direction = closeDirection
        )
        if (activeCloseOrder != null) {
            positionLifecycleLogger.warn {
                "У брокера уже есть активная заявка на закрытие ${position.instrumentName}: " +
                        "${activeCloseOrder.orderId}, status=${activeCloseOrder.executionStatus}"
            }
            return false
        }

        if (!closingPositionIds.add(position.positionId)) {
            positionLifecycleLogger.warn {
                "Позиция уже закрывается, дубль пропущен: ${position.positionId}"
            }
            return false
        }

        return true
    }

    /**
     * Prevents a second closing order when the broker stop order is already
     * executing or its cancellation cannot be confirmed.
     */
    private fun isMarketCloseSafeAfterProtectionCancellation(
        accountId: String,
        position: OpenPosition
    ): Boolean = when (val result = positionProtectionService.cancelProtectionForMarketClose(accountId, position)) {
        ProtectionCancellationResult.Cancelled -> true
        is ProtectionCancellationResult.Triggered -> {
            positionLifecycleLogger.warn {
                "Закрытие ${position.instrumentName} отложено: брокерская защита уже исполнена " +
                    "или исполняется (${result.protection.orderId})"
            }
            false
        }
        is ProtectionCancellationResult.Unconfirmed -> {
            positionLifecycleLogger.warn {
                "Закрытие ${position.instrumentName} отложено: не подтверждена отмена брокерской защиты: " +
                    result.reason
            }
            false
        }
    }

    private suspend fun waitForCloseFill(
        accountId: String,
        position: OpenPosition,
        orderResult: OrderResult,
        releaseClosingLock: Boolean = true
    ): OrderFillResult? {
        val orderId = orderResult.orderId
        if (orderId.isNullOrBlank()) {
            if (releaseClosingLock) {
                closingPositionIds.remove(position.positionId)
            }
            positionLifecycleLogger.warn {
                "Нет orderId для закрытия ${position.instrumentName}; CLOSE не сохраняем"
            }
            return null
        }

        val fill = orderExecutionService.waitForOrderFill(accountId, orderId)
        if (!fill.filled) {
            positionLifecycleLogger.warn {
                "Заявка на закрытие ${position.instrumentName} пока не исполнена: " +
                        "orderId=$orderId, status=${fill.executionStatus}. CLOSE не сохраняем."
            }
            orderExecutionService.cancelOrder(accountId, orderId)
            if (releaseClosingLock) {
                closingPositionIds.remove(position.positionId)
            }
            return fill
        }

        if (releaseClosingLock) {
            closingPositionIds.remove(position.positionId)
        }
        return fill
    }

    private fun accumulateCloseExecution(
        position: OpenPosition,
        orderResult: OrderResult,
        fill: OrderFillResult,
        executedQuantity: Long,
        totalCloseValue: BigDecimal,
        totalCloseCommission: BigDecimal
    ): CloseExecutionTotals {
        val closePrice = fill.executedPrice ?: orderResult.executedPrice ?: position.entryPrice
        val executionValue = closePrice * executedQuantity.toBigDecimal() * position.lotSize.toBigDecimal()
        val executionCommission = fill.executedCommission ?: orderResult.executedCommission ?: BigDecimal.ZERO
        return CloseExecutionTotals(
            totalValue = totalCloseValue + executionValue,
            totalCommission = totalCloseCommission + executionCommission
        )
    }

    private fun finishMarketClose(
        position: OpenPosition,
        totalCloseValue: BigDecimal,
        totalCloseCommission: BigDecimal,
        reason: String,
        explanation: String?
    ): ClosePositionResult {
        val closePrice = totalCloseValue.divide(
            position.quantity.toBigDecimal() * position.lotSize.toBigDecimal(),
            8,
            RoundingMode.HALF_UP
        )
        val pnl = calculatePnl(position, closePrice, totalCloseCommission)
        val closeEvent = tradeEventService.saveCloseEventOnce(
            position = position,
            closePrice = closePrice,
            pnl = pnl,
            reason = reason,
            explanation = explanation ?: closeExplanation(reason, pnl)
        )
        positionLifecycleLogger.info { "Закрыта позиция: ${position.instrumentName}, P&L: $pnl RUB" }
        closeEvent?.let {
            logOrderExecution(
                operation = "CLOSE",
                position = position,
                orderId = null,
                price = closePrice,
                executedLots = position.quantity,
                context = mapOf("reason" to reason, "pnl" to pnl, "marketOrder" to true)
            )
        }
        closeEvent?.let(eventPublisherService::publishTradeExecuted)
        closingPositionIds.remove(position.positionId)
        return ClosePositionResult(position, closed = true, removeFromState = true)
    }

    private fun closeExplanation(reason: String, pnl: BigDecimal): String = when (reason) {
        "STOP_LOSS" -> "Позиция закрыта по стоп-лоссу. Итоговый P&L: $pnl RUB"
        "TAKE_PROFIT" -> "Позиция закрыта по тейк-профиту. Итоговый P&L: $pnl RUB"
        else -> "Закрытие позиции рыночной заявкой ($reason), P&L: $pnl RUB"
    }

    /** Records a confirmed broker fill; attempted and unfilled orders remain in regular diagnostics. */
    private fun logOrderExecution(
        operation: String,
        position: OpenPosition,
        orderId: String?,
        price: BigDecimal,
        executedLots: Long,
        context: Map<String, Any?>
    ) {
        operationJournal.info(
            eventType = BotOperationEventType.ORDER_EXECUTION,
            message = "Исполнена заявка $operation: ${position.side} ${position.instrumentName}, " +
                "лотов=$executedLots, цена=$price",
            instrumentId = position.instrumentId,
            instrumentName = position.instrumentName,
            positionId = position.positionId,
            brokerOrderId = orderId,
            context = context + mapOf(
                "operation" to operation,
                "side" to position.side.name,
                "executedLots" to executedLots,
                "price" to price
            )
        )
    }

    private fun buildProtectionExecutionState(
        stopOrderId: String,
        exchangeOrderId: String?,
        executedLots: Long?,
        expectedLots: Long
    ): String = buildString {
        append("stop_order_id=$stopOrderId")
        exchangeOrderId?.let { append("; exchange_order_id=$it") }
        executedLots?.let { append("; lots_executed=$it") }
        append("; expected_lots=$expectedLots")
    }

    private suspend fun waitForOpenFill(
        accountId: String,
        marketData: MarketData,
        orderResult: OrderResult
    ): OrderFillResult? {
        val orderId = orderResult.orderId
        if (orderId.isNullOrBlank()) {
            positionLifecycleLogger.warn {
                "Нет orderId для открытия ${marketData.instrumentName}; OPEN будет помечен как FAILED"
            }
            return null
        }

        val fill = orderExecutionService.waitForOrderFill(accountId, orderId)
        if (!fill.filled) {
            positionLifecycleLogger.warn {
                "Заявка на открытие ${marketData.instrumentName} не исполнена: " +
                        "orderId=$orderId, status=${fill.executionStatus}. OPEN будет помечен как FAILED."
            }
        }
        return fill
    }

    /**
     * Resolves an opening after its initial wait. A timeout is not an execution
     * result: after cancellation we must read the broker state once more before
     * marking the event as failed or creating a position from a partial fill.
     */
    private suspend fun resolveOpenFillAfterTimeout(
        accountId: String,
        marketData: MarketData,
        orderResult: OrderResult,
        initialFill: OrderFillResult?,
        pendingEvent: ru.bolotov.tradebot.domain.model.TradeEvent
    ): OrderFillResult? {
        if (initialFill == null) {
            tradeEventService.markOpenEventFailed(pendingEvent, orderResult)
            return null
        }
        if (initialFill.filled) return initialFill

        val orderId = orderResult.orderId
        if (orderId.isNullOrBlank()) {
            tradeEventService.markOpenEventFailed(pendingEvent, orderResult, initialFill)
            return null
        }

        cancelUnfilledOrder(accountId, orderId, marketData.instrumentName)
        val finalFill = orderExecutionService.readOrderFill(accountId, orderId)
        if (finalFill.filled || finalFill.executedLots > 0L) return finalFill

        if (orderExecutionService.isTerminalUnfilled(finalFill)) {
            tradeEventService.markOpenEventFailed(pendingEvent, orderResult, finalFill)
            return null
        }

        tradeEventService.markOpenEventAwaitingReconciliation(pendingEvent, orderResult, finalFill)
        positionLifecycleLogger.warn {
            "Открытие ${marketData.instrumentName} ожидает сверки с брокером: " +
                "orderId=$orderId, status=${finalFill.executionStatus}"
        }
        return null
    }

    private fun cancelUnfilledOrder(accountId: String, orderId: String, instrumentName: String) {
        if (!orderExecutionService.cancelOrder(accountId, orderId)) {
            positionLifecycleLogger.error {
                "Не удалось отменить неисполненный остаток заявки $instrumentName: $orderId"
            }
        }
    }

    private fun calculatePnl(
        position: OpenPosition,
        closePrice: BigDecimal,
        closeCommission: BigDecimal = BigDecimal.ZERO
    ): BigDecimal {
        val grossPnl = when (position.side) {
            PositionSide.LONG -> (closePrice - position.entryPrice)
            PositionSide.SHORT -> (position.entryPrice - closePrice)
        } * position.quantity.toBigDecimal() * position.lotSize.toBigDecimal()
        return grossPnl - position.entryCommission - closeCommission
    }

    private fun closeDirection(position: OpenPosition): String =
        if (position.side == PositionSide.LONG) "SELL" else "BUY"

}

data class ClosePositionResult(
    val position: OpenPosition,
    val closed: Boolean,
    val removeFromState: Boolean
)
