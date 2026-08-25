package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.MarketDataProvider
import ru.bolotov.tradebot.strategy.Signal
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val positionLifecycleLogger = KotlinLogging.logger {}

@Service
class PositionLifecycleService(
    private val marketDataProvider: MarketDataProvider,
    private val orderExecutionService: OrderExecutionService,
    private val tradeEventService: TradeEventService,
    private val eventPublisherService: EventPublisherService,
    private val positionProtectionService: PositionProtectionService
) {
    private val closingPositionIds = ConcurrentHashMap.newKeySet<String>()
    private val openingInstrumentKeys = ConcurrentHashMap.newKeySet<String>()

    suspend fun openPosition(
        accountId: String,
        marketData: MarketData,
        signal: Signal,
        positionSize: PositionSize,
        strategyName: String,
        strategyExplanation: String
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
                strategyName = strategyName,
                strategyExplanation = strategyExplanation
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
        strategyName: String,
        strategyExplanation: String
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
            strategyName = strategyName,
            signal = signal,
            strategyExplanation = strategyExplanation
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

        val fill = waitForOpenFill(accountId, marketData, orderResult)
        val executedQuantity = fill?.executedLots ?: 0L
        if (fill == null || executedQuantity == 0L) {
            orderResult.orderId?.let { orderId ->
                cancelUnfilledOrder(accountId, orderId, marketData.instrumentName)
            }
            tradeEventService.markOpenEventFailed(savedEvent, orderResult, fill)
            return null
        }

        if (!fill.filled) {
            orderResult.orderId?.let { orderId ->
                cancelUnfilledOrder(accountId, orderId, marketData.instrumentName)
            }
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
            stopLossPrice = positionSize.stopLossPrice,
            atr = positionSize.atr
        )

        positionLifecycleLogger.info {
            "Открыта позиция: $side ${marketData.instrumentName} " +
                    "($executedQuantity лотов, ${"%.0f".format(totalValue)} RUB, " +
                    "${"%.1f".format(totalValue * BigDecimal(100) / positionSize.value)}% от рассчитанного размера)"
        }

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

    suspend fun closePosition(accountId: String, position: OpenPosition, reason: String = "CLOSE"): ClosePositionResult {
        val marketData = marketDataProvider.fetchMarketData(position.instrumentId)
            ?: return ClosePositionResult(position, closed = false, removeFromState = false)
        return closePositionAtPrice(accountId, position, marketData.currentPrice, reason, marketOrder = false)
    }

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
        if (!positionProtectionService.cancelProtection(accountId, position.positionId, position.instrumentName)) {
            closingPositionIds.remove(position.positionId)
            positionLifecycleLogger.warn {
                "Закрытие ${position.instrumentName} отложено: защитная пара сейчас изменяется или не отменена"
            }
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

    fun recordBrokerProtectionClose(
        accountId: String,
        position: OpenPosition,
        triggeredOrderId: String,
        reason: String,
        closePrice: BigDecimal,
        closeCommission: BigDecimal
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
                explanation = closeExplanation(reason, pnl)
            )
            closeEvent?.let(eventPublisherService::publishTradeExecuted)
            positionLifecycleLogger.info {
                "Защитная заявка брокера исполнилась для ${position.instrumentName}: " +
                    "причина=$reason, цена=$closePrice, комиссия=$closeCommission, P&L=$pnl"
            }
            ClosePositionResult(position, closed = closeEvent != null, removeFromState = true)
        } finally {
            closingPositionIds.remove(position.positionId)
        }
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
        if (!positionProtectionService.cancelProtection(accountId, position.positionId, position.instrumentName)) {
            closingPositionIds.remove(position.positionId)
            positionLifecycleLogger.warn {
                "Закрытие ${position.instrumentName} отложено: защитная пара сейчас изменяется или не отменена"
            }
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
        closeEvent?.let(eventPublisherService::publishTradeExecuted)
        closingPositionIds.remove(position.positionId)
        return ClosePositionResult(position, closed = true, removeFromState = true)
    }

    private fun closeExplanation(reason: String, pnl: BigDecimal): String = when (reason) {
        "STOP_LOSS" -> "Позиция закрыта по стоп-лоссу. Итоговый P&L: $pnl RUB"
        "TAKE_PROFIT" -> "Позиция закрыта по тейк-профиту. Итоговый P&L: $pnl RUB"
        else -> "Закрытие позиции рыночной заявкой ($reason), P&L: $pnl RUB"
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

    private fun Signal.toPositionSideOrNull(): PositionSide? = when (direction) {
        ru.bolotov.tradebot.strategy.OrderDirection.BUY -> PositionSide.LONG
        ru.bolotov.tradebot.strategy.OrderDirection.SELL -> PositionSide.SHORT
        ru.bolotov.tradebot.strategy.OrderDirection.HOLD -> null
    }

    private fun PositionSide.openDirection(): OrderDirection = when (this) {
        PositionSide.LONG -> OrderDirection.BUY
        PositionSide.SHORT -> OrderDirection.SELL
    }
}

data class ClosePositionResult(
    val position: OpenPosition,
    val closed: Boolean,
    val removeFromState: Boolean
)

private data class CloseExecutionTotals(
    val totalValue: BigDecimal,
    val totalCommission: BigDecimal
)
