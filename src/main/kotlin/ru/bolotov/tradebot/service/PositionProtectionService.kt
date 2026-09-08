package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.broker.*

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.config.PositionSizingConfig
import ru.bolotov.tradebot.domain.model.PositionProtectionEntity
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.domain.model.ProfitProtectionStage
import ru.bolotov.tradebot.domain.model.ProtectionUpdateStatus
import ru.bolotov.tradebot.domain.repository.PositionProtectionRepository
import ru.bolotov.tradebot.service.data.ProtectionOrderPair
import ru.bolotov.tradebot.service.data.ProtectionPrices
import ru.bolotov.tradebot.service.data.ProfitProtectionDecision
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.contract.v1.StopOrder
import ru.tinkoff.piapi.contract.v1.StopOrderDirection
import ru.tinkoff.piapi.contract.v1.StopOrderStatusOption
import ru.tinkoff.piapi.contract.v1.StopOrderType
import ru.ttech.piapi.core.InstrumentsServiceSync
import ru.ttech.piapi.core.StopOrdersServiceSync
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val protectionLogger = KotlinLogging.logger {}

/** Создаёт, заменяет, отменяет и сверяет брокерские stop-loss и take-profit заявки. */
@Service
class PositionProtectionService(
    private val stopOrdersService: StopOrdersServiceSync,
    private val instrumentsService: InstrumentsServiceSync,
    private val positionProtectionRepository: PositionProtectionRepository,
    private val positionSizingConfig: PositionSizingConfig,
    private val profitProtectionPolicy: ProfitProtectionPolicy,
    @Qualifier("sandboxEnabled") private val sandboxEnabled: Boolean
) {
    private val protectionLocks = ConcurrentHashMap.newKeySet<String>()

    /** Создаёт пару защитных заявок для новой позиции, кроме песочницы. */
    fun createProtection(accountId: String, position: OpenPosition): ProtectionCreationResult {
        if (sandboxEnabled) return skippedInSandbox(position)

        return withPositionLock(position.positionId) {
            val existing = positionProtectionRepository.findByPositionId(position.positionId)
            if (existing?.hasActivePair == true) return@withPositionLock ProtectionCreationResult.Created
            createInitialProtection(accountId, position, existing)
        } ?: ProtectionCreationResult.Failed(IllegalStateException("Защита позиции уже изменяется"))
    }

    /** Безопасно заменяет защитные уровни всех переданных позиций после изменения риск-настроек. */
    fun replaceProtectionForOpenPositions(
        accountId: String,
        positions: Collection<OpenPosition>,
        stopLossPercent: Double,
        takeProfitPercent: Double
    ): ProtectionReplacementResult {
        if (sandboxEnabled || positions.isEmpty()) return ProtectionReplacementResult.Success

        positions.forEach { position ->
            when (val result = replaceProtection(accountId, position, stopLossPercent, takeProfitPercent)) {
                ProtectionReplacementResult.Success -> Unit
                is ProtectionReplacementResult.Failed -> return result
            }
        }
        return ProtectionReplacementResult.Success
    }

    /** Отменяет все известные защитные заявки позиции при её закрытии. */
    fun cancelProtection(accountId: String, positionId: String, instrumentName: String): Boolean {
        if (sandboxEnabled) return true

        return withPositionLock(positionId) {
            val protection = positionProtectionRepository.findByPositionId(positionId) ?: return@withPositionLock true
            cancelAndClearAllOrders(accountId, protection, instrumentName)
        } ?: false
    }

    /** Завершает учёт исполненной защиты и отменяет вторую заявку пары. */
    fun completeTriggeredProtection(
        accountId: String,
        positionId: String,
        triggeredOrderId: String,
        instrumentName: String
    ) {
        if (sandboxEnabled) return

        withPositionLock(positionId) {
            val protection = positionProtectionRepository.findByPositionId(positionId) ?: return@withPositionLock
            protection.allOrderIds
                .filterNot { it == triggeredOrderId }
                .forEach { cancelStopOrder(accountId, it, instrumentName) }
            positionProtectionRepository.delete(protection)
        }
    }

    /** Получает одним запросом активные stop-заявки счёта для сверки. */
    fun loadBrokerSnapshot(accountId: String): BrokerProtectionSnapshot? {
        if (sandboxEnabled) return BrokerProtectionSnapshot.empty()

        return runCatching {
            val orders = stopOrdersService.getStopOrdersSync(
                accountId,
                Instant.now().minus(30, ChronoUnit.DAYS),
                Instant.now(),
                StopOrderStatusOption.STOP_ORDER_STATUS_ALL
            )
            BrokerProtectionSnapshot(orders.associateBy(StopOrder::getStopOrderId))
        }.onFailure { error ->
            protectionLogger.error(error) { "Не удалось получить статусы защитных заявок брокера" }
        }.getOrNull()
    }

    /** Определяет, какая сохранённая защита позиции исчезла или исполнилась у брокера. */
    fun findTriggeredProtection(position: OpenPosition, snapshot: BrokerProtectionSnapshot): TriggeredProtection? {
        val protection = positionProtectionRepository.findByPositionId(position.positionId) ?: return null
        return listOfNotNull(
            protection.stopLossOrderId?.let { id -> snapshot.ordersById[id]?.let { id to it } },
            protection.takeProfitOrderId?.let { id -> snapshot.ordersById[id]?.let { id to it } }
        ).firstOrNull { (_, order) -> order.status == StopOrderStatusOption.STOP_ORDER_STATUS_EXECUTED }
            ?.let { (orderId, order) ->
                val reason = if (orderId == protection.stopLossOrderId) "STOP_LOSS" else "TAKE_PROFIT"
                TriggeredProtection(orderId, reason, order)
            }
    }

    /** Восстанавливает отсутствующие защитные заявки для ещё открытых локальных позиций. */
    fun reconcileProtection(accountId: String, positions: Collection<OpenPosition>) {
        if (sandboxEnabled) return

        val snapshot = loadBrokerSnapshot(accountId) ?: return
        val openPositions = positions.associateBy(OpenPosition::positionId)
        positionProtectionRepository.findAll().forEach { protection ->
            if (protection.positionId !in openPositions) {
                cancelProtection(accountId, protection.positionId, protection.instrumentId)
            }
        }
        positions.forEach { position -> reconcilePositionProtection(accountId, position, snapshot) }
    }

    /**
     * Обновляет бот-управляемый уровень защиты прибыли. Брокерский SL при этом
     * не заменяется: он остаётся независимой аварийной защитой на случай сбоя.
     */
    fun evaluateProfitProtection(
        position: OpenPosition,
        currentPrice: BigDecimal,
        atr: BigDecimal?
    ): ProfitProtectionDecision = withPositionLock(position.positionId) {
        // Re-read state while holding the position lock: overlapping ticks must
        // never overwrite a more favourable trailing level with an older one.
        val protection = positionProtectionRepository.findByPositionId(position.positionId)
            ?: return@withPositionLock ProfitProtectionDecision.NoChange
        val decision = profitProtectionPolicy.evaluate(
            position = position,
            currentPrice = currentPrice,
            atr = atr,
            takeProfitPercent = positionSizingConfig.takeProfitPercent,
            currentExitPrice = protection.managedExitPrice
        )
        if (decision !is ProfitProtectionDecision.Updated) return@withPositionLock decision

        val previousStage = protection.profitProtectionStage
        protection.managedExitPrice = decision.exitPrice
        protection.profitProtectionStage = decision.stage
        protection.updatedAt = Instant.now()
        positionProtectionRepository.save(protection)
        if (previousStage != decision.stage) {
            protectionLogger.info {
                "Сопровождение прибыли ${position.instrumentName}: ${decision.stage}, " +
                    "уровень выхода=${decision.exitPrice}"
            }
        } else {
            protectionLogger.debug {
                "Уровень сопровождения прибыли ${position.instrumentName} обновлён: ${decision.exitPrice}"
            }
        }
        decision
    } ?: ProfitProtectionDecision.NoChange

    /** Возвращает сохранённые уровни защиты для отображения открытых позиций. */
    fun getProtectionStates(): Map<String, PositionProtectionState> =
        positionProtectionRepository.findAll().associate { protection ->
            protection.positionId to PositionProtectionState(
                brokerStopLossPrice = protection.brokerStopLossPrice,
                managedExitPrice = protection.managedExitPrice,
                profitProtectionStage = protection.profitProtectionStage
            )
        }

    private fun createInitialProtection(
        accountId: String,
        position: OpenPosition,
        existing: PositionProtectionEntity?
    ): ProtectionCreationResult = runCatching {
        val pair = createOrderPair(accountId, position, positionSizingConfig.stopLossPercent, positionSizingConfig.takeProfitPercent)
        val protection = existing ?: PositionProtectionEntity(positionId = position.positionId, instrumentId = position.instrumentId)
        protection.stopLossOrderId = pair.stopLossOrderId
        protection.brokerStopLossPrice = pair.stopLossPrice
        protection.takeProfitOrderId = pair.takeProfitOrderId
        protection.replacementStopLossOrderId = null
        protection.replacementTakeProfitOrderId = null
        protection.updateStatus = ProtectionUpdateStatus.ACTIVE
        protection.updatedAt = Instant.now()
        positionProtectionRepository.save(protection)
        protectionLogger.info {
            "Создана брокерская защита для ${position.instrumentName}: SL=${pair.stopLossOrderId}; " +
                "тейк-профит контролируется ботом, чтобы исключить двойное исполнение"
        }
        ProtectionCreationResult.Created
    }.getOrElse { error ->
        protectionLogger.error(error) { "Не удалось создать защитные заявки для ${position.instrumentName}" }
        ProtectionCreationResult.Failed(error)
    }

    private fun replaceProtection(
        accountId: String,
        position: OpenPosition,
        stopLossPercent: Double,
        takeProfitPercent: Double
    ): ProtectionReplacementResult = withPositionLock(position.positionId) {
        val protection = positionProtectionRepository.findByPositionId(position.positionId)
            ?: return@withPositionLock createMissingProtection(accountId, position, stopLossPercent, takeProfitPercent)

        recoverInterruptedReplacement(accountId, position, protection)
        val current = positionProtectionRepository.findByPositionId(position.positionId) ?: return@withPositionLock ProtectionReplacementResult.Failed(
            "Не найдена защита позиции ${position.instrumentName} после восстановления"
        )
        current.updateStatus = ProtectionUpdateStatus.CANCELLING_PREVIOUS
        current.updatedAt = Instant.now()
        positionProtectionRepository.save(current)

        if (!cancelCurrentPair(accountId, current, position.instrumentName)) {
            return@withPositionLock ProtectionReplacementResult.Failed(
                "Не удалось отменить прежний SL ${position.instrumentName}; новая защита не создавалась"
            )
        }

        val pair = try {
            createOrderPair(accountId, position, stopLossPercent, takeProfitPercent)
        } catch (error: Exception) {
            current.stopLossOrderId = null
            current.takeProfitOrderId = null
            current.updateStatus = ProtectionUpdateStatus.ACTIVE
            current.updatedAt = Instant.now()
            positionProtectionRepository.save(current)
            return@withPositionLock ProtectionReplacementResult.Failed(
                "Прежний SL отменён, но новый не создан для ${position.instrumentName}: ${error.message}"
            )
        }

        current.stopLossOrderId = pair.stopLossOrderId
        current.brokerStopLossPrice = pair.stopLossPrice
        current.takeProfitOrderId = null
        current.replacementStopLossOrderId = null
        current.replacementTakeProfitOrderId = null
        current.updateStatus = ProtectionUpdateStatus.ACTIVE
        current.updatedAt = Instant.now()
        positionProtectionRepository.save(current)
        protectionLogger.info { "Обновлён брокерский SL для ${position.instrumentName}; TP контролируется ботом" }
        ProtectionReplacementResult.Success
    } ?: ProtectionReplacementResult.Failed("Защита позиции ${position.instrumentName} уже изменяется")

    private fun createMissingProtection(
        accountId: String,
        position: OpenPosition,
        stopLossPercent: Double,
        takeProfitPercent: Double
    ): ProtectionReplacementResult = try {
        val pair = createOrderPair(accountId, position, stopLossPercent, takeProfitPercent)
        positionProtectionRepository.save(
            PositionProtectionEntity(
                positionId = position.positionId,
                instrumentId = position.instrumentId,
                stopLossOrderId = pair.stopLossOrderId,
                brokerStopLossPrice = pair.stopLossPrice,
                takeProfitOrderId = pair.takeProfitOrderId
            )
        )
        ProtectionReplacementResult.Success
    } catch (error: Exception) {
        ProtectionReplacementResult.Failed("Не удалось создать защиту ${position.instrumentName}: ${error.message}")
    }

    private fun recoverInterruptedReplacement(accountId: String, position: OpenPosition, protection: PositionProtectionEntity) {
        if (protection.updateStatus == ProtectionUpdateStatus.ACTIVE) return

        protectionLogger.warn { "Восстанавливаем незавершённую замену защиты ${position.instrumentName}" }
        if (protection.replacementStopLossOrderId == null) {
            cancelReplacementPair(accountId, protection, position.instrumentName)
            protection.updateStatus = ProtectionUpdateStatus.ACTIVE
            positionProtectionRepository.save(protection)
            return
        }

        if (cancelCurrentPair(accountId, protection, position.instrumentName)) {
            protection.stopLossOrderId = protection.replacementStopLossOrderId
            protection.takeProfitOrderId = protection.replacementTakeProfitOrderId
            protection.replacementStopLossOrderId = null
            protection.replacementTakeProfitOrderId = null
            protection.updateStatus = ProtectionUpdateStatus.ACTIVE
            protection.updatedAt = Instant.now()
            positionProtectionRepository.save(protection)
        }
    }

    private fun reconcilePositionProtection(accountId: String, position: OpenPosition, snapshot: BrokerProtectionSnapshot) {
        val protection = positionProtectionRepository.findByPositionId(position.positionId)
        if (protection == null) {
            createProtection(accountId, position)
            return
        }
        if (protection.updateStatus != ProtectionUpdateStatus.ACTIVE) {
            withPositionLock(position.positionId) { recoverInterruptedReplacement(accountId, position, protection) }
            return
        }
        if (!removeLegacyTakeProfit(accountId, position, protection, snapshot.activeOrderIds)) return
        backfillBrokerStopLossPrice(position, protection, snapshot)
        if (protection.hasActivePairIn(snapshot.activeOrderIds)) return

        protectionLogger.warn { "Брокерский стоп-лосс ${position.instrumentName} отсутствует; восстанавливаем защиту" }
        if (cancelProtection(accountId, position.positionId, position.instrumentName)) {
            createProtection(accountId, position)
        }
    }

    /**
     * Enriches an old local protection record from the stop order already returned
     * by reconciliation. It performs no broker-side mutation and never invents a
     * price when the broker cannot confirm an active order.
     */
    private fun backfillBrokerStopLossPrice(
        position: OpenPosition,
        protection: PositionProtectionEntity,
        snapshot: BrokerProtectionSnapshot
    ) {
        if (protection.brokerStopLossPrice != null) return
        val stopOrderId = protection.stopLossOrderId ?: return
        val brokerOrder = snapshot.ordersById[stopOrderId]
            ?.takeIf { it.status == StopOrderStatusOption.STOP_ORDER_STATUS_ACTIVE }
            ?: return

        protection.brokerStopLossPrice = brokerOrder.stopPrice.toBigDecimal()
        protection.updatedAt = Instant.now()
        positionProtectionRepository.save(protection)
        protectionLogger.info {
            "Сверка защиты ${position.instrumentName}: сохранена подтверждённая цена брокерского SL=" +
                protection.brokerStopLossPrice
        }
    }

    private fun createOrderPair(accountId: String, position: OpenPosition, stopLossPercent: Double, takeProfitPercent: Double): ProtectionOrderPair {
        val prices = calculateProtectionPrices(position, stopLossPercent, takeProfitPercent)
        val stopLossOrderId = createStopOrder(accountId, position, prices.stopLossPrice, StopOrderType.STOP_ORDER_TYPE_STOP_LOSS)
        return ProtectionOrderPair(stopLossOrderId, prices.stopLossPrice)
    }

    private fun calculateProtectionPrices(position: OpenPosition, stopLossPercent: Double, takeProfitPercent: Double): ProtectionPrices {
        val priceIncrement = getPriceIncrement(position.instrumentId)
        val (stopLossPrice, takeProfitPrice) = when (position.side) {
            PositionSide.LONG -> {
                position.entryPrice.multiply(BigDecimal.ONE.subtract(stopLossPercent.toBigDecimal()))
                    .roundToIncrement(priceIncrement, RoundingMode.DOWN) to
                    position.entryPrice.multiply(BigDecimal.ONE.add(takeProfitPercent.toBigDecimal()))
                        .roundToIncrement(priceIncrement, RoundingMode.UP)
            }

            PositionSide.SHORT -> {
                position.entryPrice.multiply(BigDecimal.ONE.add(stopLossPercent.toBigDecimal()))
                    .roundToIncrement(priceIncrement, RoundingMode.UP) to
                    position.entryPrice.multiply(BigDecimal.ONE.subtract(takeProfitPercent.toBigDecimal()))
                        .roundToIncrement(priceIncrement, RoundingMode.DOWN)
            }
        }
        require(stopLossPrice > BigDecimal.ZERO) { "Цена стоп-лосса должна быть больше нуля" }
        require(takeProfitPrice > BigDecimal.ZERO) { "Цена тейк-профита должна быть больше нуля" }
        return ProtectionPrices(stopLossPrice, takeProfitPrice)
    }

    private fun cancelAndClearAllOrders(accountId: String, protection: PositionProtectionEntity, instrumentName: String): Boolean {
        val cancellationResults = protection.allOrderIds.map { orderId ->
            cancelStopOrder(accountId, orderId, instrumentName)
        }
        val cancelled = cancellationResults.all { it }
        if (cancelled) positionProtectionRepository.delete(protection)
        return cancelled
    }

    private fun cancelCurrentPair(accountId: String, protection: PositionProtectionEntity, instrumentName: String): Boolean {
        protection.updateStatus = ProtectionUpdateStatus.CANCELLING_PREVIOUS
        positionProtectionRepository.save(protection)
        val activeOrderIds = loadBrokerSnapshot(accountId)?.activeOrderIds ?: return false
        return listOfNotNull(protection.stopLossOrderId, protection.takeProfitOrderId)
            .all { orderId -> orderId !in activeOrderIds || cancelStopOrder(accountId, orderId, instrumentName) }
    }

    private fun cancelReplacementPair(accountId: String, protection: PositionProtectionEntity, instrumentName: String) {
        listOfNotNull(protection.replacementStopLossOrderId, protection.replacementTakeProfitOrderId)
            .forEach { cancelStopOrder(accountId, it, instrumentName) }
        protection.replacementStopLossOrderId = null
        protection.replacementTakeProfitOrderId = null
    }

    /** Удаляет устаревший брокерский TP, созданный до перехода на единственный SL. */
    private fun removeLegacyTakeProfit(
        accountId: String,
        position: OpenPosition,
        protection: PositionProtectionEntity,
        activeOrderIds: Set<String>
    ): Boolean {
        val takeProfitOrderId = protection.takeProfitOrderId ?: return true
        if (takeProfitOrderId in activeOrderIds && !cancelStopOrder(accountId, takeProfitOrderId, position.instrumentName)) {
            protectionLogger.error {
                "Не удалось отменить устаревший брокерский TP ${position.instrumentName}; " +
                    "сопровождение позиции остановлено, чтобы не создавать вторую встречную заявку"
            }
            return false
        }
        protection.takeProfitOrderId = null
        protection.updatedAt = Instant.now()
        positionProtectionRepository.save(protection)
        protectionLogger.warn {
            "Устаревший брокерский TP ${position.instrumentName} отменён: тейк-профит контролируется ботом"
        }
        return true
    }

    private fun getPriceIncrement(instrumentId: String): BigDecimal {
        val quotation = instrumentsService.getInstrumentByUIDSync(instrumentId).instrument.minPriceIncrement
        return quotation.toBigDecimal().takeIf { it > BigDecimal.ZERO } ?: error("Для $instrumentId не задан шаг цены")
    }

    private fun createStopOrder(
        accountId: String,
        position: OpenPosition,
        price: BigDecimal,
        type: StopOrderType
    ): String = stopOrdersService.postStopOrderGoodTillCancelSync(
            position.instrumentId,
            position.quantity,
            price.toQuotation(),
            price.toQuotation(),
            position.stopOrderDirection(),
            accountId,
            type,
            UUID.randomUUID(),
            confirmMarginTrade = position.side == PositionSide.SHORT
        )

    private fun OpenPosition.stopOrderDirection(): StopOrderDirection = when (side) {
        PositionSide.LONG -> StopOrderDirection.STOP_ORDER_DIRECTION_SELL
        PositionSide.SHORT -> StopOrderDirection.STOP_ORDER_DIRECTION_BUY
    }

    private fun cancelStopOrder(accountId: String, orderId: String, instrumentName: String): Boolean = runCatching {
        stopOrdersService.cancelStopOrderSync(accountId, orderId)
        protectionLogger.info { "Отменена защитная заявка $orderId для $instrumentName" }
        true
    }.getOrElse { error ->
        protectionLogger.warn(error) { "Не удалось отменить защитную заявку $orderId для $instrumentName" }
        false
    }

    private fun skippedInSandbox(position: OpenPosition): ProtectionCreationResult {
        protectionLogger.info { "Брокерские защитные заявки пропущены для ${position.instrumentName}: включена песочница" }
        return ProtectionCreationResult.SkippedInSandbox
    }

    private fun <T> withPositionLock(positionId: String, action: () -> T): T? {
        if (!protectionLocks.add(positionId)) return null
        return try {
            action()
        } finally {
            protectionLocks.remove(positionId)
        }
    }

    private val PositionProtectionEntity.hasActivePair: Boolean
        get() = stopLossOrderId != null && updateStatus == ProtectionUpdateStatus.ACTIVE

    private val PositionProtectionEntity.allOrderIds: List<String>
        get() = listOfNotNull(stopLossOrderId, takeProfitOrderId, replacementStopLossOrderId, replacementTakeProfitOrderId)

    private fun PositionProtectionEntity.hasActivePairIn(activeOrderIds: Set<String>): Boolean =
        stopLossOrderId in activeOrderIds

    private fun BigDecimal.roundToIncrement(increment: BigDecimal, roundingMode: RoundingMode): BigDecimal =
        divide(increment, 0, roundingMode).multiply(increment)

    private fun BigDecimal.toQuotation(): Quotation {
        val units = toLong()
        val nano = remainder(BigDecimal.ONE).movePointRight(9).setScale(0, RoundingMode.HALF_UP).toInt()
        return Quotation.newBuilder().setUnits(units).setNano(nano).build()
    }
}

sealed interface ProtectionCreationResult {
    data object Created : ProtectionCreationResult
    data object SkippedInSandbox : ProtectionCreationResult
    data class Failed(val error: Throwable) : ProtectionCreationResult
}

sealed interface ProtectionReplacementResult {
    data object Success : ProtectionReplacementResult
    data class Failed(val message: String) : ProtectionReplacementResult
}

data class BrokerProtectionSnapshot(val ordersById: Map<String, StopOrder>) {
    val activeOrderIds: Set<String> = ordersById.values
        .filter { it.status == StopOrderStatusOption.STOP_ORDER_STATUS_ACTIVE }
        .mapTo(mutableSetOf(), StopOrder::getStopOrderId)

    companion object {
        fun empty() = BrokerProtectionSnapshot(emptyMap())
    }
}

data class TriggeredProtection(val orderId: String, val reason: String, val stopOrder: StopOrder)

data class PositionProtectionState(
    val brokerStopLossPrice: BigDecimal?,
    val managedExitPrice: BigDecimal?,
    val profitProtectionStage: ProfitProtectionStage
)
