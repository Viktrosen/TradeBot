package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.contract.v1.TradesStreamRequest
import ru.tinkoff.piapi.contract.v1.TradesStreamResponse
import ru.ttech.piapi.core.InvestApi
import ru.ttech.piapi.core.UsersServiceSync
import ru.bolotov.tradebot.broker.getMarginAttributesSync
import ru.bolotov.tradebot.domain.model.PositionSide
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean

private val reconciliationLogger = KotlinLogging.logger {}

/** Сверяет локальные позиции и защитные заявки с брокером после событий и по расписанию. */
@Service
class BrokerProtectionReconciliationService(
    private val investApi: InvestApi,
    private val brokerPortfolioSyncService: BrokerPortfolioSyncService,
    private val positionProtectionService: PositionProtectionService,
    private val positionLifecycleService: PositionLifecycleService,
    private val orderExecutionService: OrderExecutionService,
    private val usersService: UsersServiceSync,
    private val portfolioSnapshotService: PortfolioSnapshotService,
    private val eventPublisherService: EventPublisherService,
    @Qualifier("sandboxEnabled") private val sandboxEnabled: Boolean,
    @Value("\${broker.protection.reconciliation-delay-ms:180000}")
    private val reconciliationDelayMs: Long,
    @Value("\${trading.short.min-margin-sufficiency}")
    private val minimumMarginSufficiency: Double
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reconciliationInProgress = AtomicBoolean(false)
    private val reconnectInProgress = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val reconnectDelays = longArrayOf(5_000, 15_000, 60_000)

    private var accountId: String? = null
    private var positionsProvider: (() -> Collection<OpenPosition>)? = null
    private var onPositionClosed: (suspend (OpenPosition) -> Unit)? = null
    private var streamJob: Job? = null
    private var periodicJob: Job? = null
    private var reconnectAttempt = 0

    /** Запускает поток исполнений и резервную периодическую сверку для выбранного счёта. */
    fun start(
        accountId: String,
        positionsProvider: () -> Collection<OpenPosition>,
        onPositionClosed: suspend (OpenPosition) -> Unit
    ) {
        if (sandboxEnabled) return

        stop()
        started.set(true)
        this.accountId = accountId
        this.positionsProvider = positionsProvider
        this.onPositionClosed = onPositionClosed
        subscribeToTrades(accountId)
        startPeriodicReconciliation()
        requestReconciliation("запуск")
    }

    /** Останавливает поток и фоновые задачи сверки при остановке бота. */
    fun stop() {
        started.set(false)
        streamJob?.cancel()
        streamJob = null
        periodicJob?.cancel()
        periodicJob = null
        reconnectAttempt = 0
    }

    /** Ставит внеплановую сверку в очередь после события, способного изменить позицию. */
    fun requestReconciliation(reason: String) {
        if (sandboxEnabled || accountId == null || !reconciliationInProgress.compareAndSet(false, true)) return

        scope.launch {
            try {
                reconcile(reason)
            } finally {
                reconciliationInProgress.set(false)
            }
        }
    }

    /** Выполняет начальную сверку портфеля и защитных заявок после запуска. */
    suspend fun reconcileAtStartup(
        accountId: String,
        positions: Collection<OpenPosition>,
        onPositionClosed: suspend (OpenPosition) -> Unit
    ) {
        if (sandboxEnabled || positions.isEmpty()) return
        reconcilePositions(accountId, positions.toList(), "запуск приложения", onPositionClosed)
    }

    private fun subscribeToTrades(accountId: String) {
        streamJob?.cancel()
        streamJob = scope.launch {
            runCatching {
                val stream = investApi.ordersStreamServiceAsync.tradesStream(
                    TradesStreamRequest.newBuilder().addAccounts(accountId).build(),
                    ::handleTradeStreamMessage
                )
                stream.join()
            }.onFailure(::handleStreamError)
        }
        reconciliationLogger.info { "Подключён поток исполнений заявок T-Invest для защитных заявок" }
    }

    private fun handleTradeStreamMessage(response: TradesStreamResponse) {
        reconnectAttempt = 0
        val trade = response.takeIf(TradesStreamResponse::hasOrderTrades)?.orderTrades ?: return
        if (trade.instrumentUid.isBlank() || trade.tradesCount == 0) return
        val position = currentPositions().firstOrNull { it.instrumentId == trade.instrumentUid } ?: return
        if (!matchesCloseDirection(trade.direction.name, position)) return

        requestReconciliation("исполнение продажи из пользовательского потока")
    }

    private fun handleStreamError(error: Throwable) {
        reconciliationLogger.warn(error) { "Поток исполнений T-Invest прерван; запускаем переподключение" }
        if (!started.get()) return
        requestReconciliation("ошибка потока исполнений")
        if (!reconnectInProgress.compareAndSet(false, true)) return

        scope.launch {
            try {
                val delayMs = reconnectDelays[reconnectAttempt.coerceAtMost(reconnectDelays.lastIndex)]
                delay(delayMs)
                accountId?.takeIf { started.get() }?.let(::subscribeToTrades)
                reconnectAttempt++
                requestReconciliation("переподключение потока исполнений")
            } finally {
                reconnectInProgress.set(false)
            }
        }
    }

    private fun startPeriodicReconciliation() {
        periodicJob = scope.launch {
            while (isActive) {
                delay(reconciliationDelayMs)
                if (currentPositions().isNotEmpty()) {
                    requestReconciliation("резервная периодическая сверка")
                }
            }
        }
    }

    private suspend fun reconcile(reason: String) {
        val selectedAccountId = accountId ?: return
        val positions = currentPositions().toList()
        if (positions.isEmpty()) return

        reconcilePositions(selectedAccountId, positions, reason, onPositionClosed)
    }

    private suspend fun reconcilePositions(
        accountId: String,
        positions: List<OpenPosition>,
        reason: String,
        onClosed: (suspend (OpenPosition) -> Unit)?
    ) {
        val activePositions = closeShortsWithUnsafeMargin(accountId, positions, onClosed)
        if (activePositions.isEmpty()) return

        val brokerQuantities = brokerPortfolioSyncService.getBrokerPositionQuantities(accountId)
        val snapshot = positionProtectionService.loadBrokerSnapshot(accountId) ?: return
        val closedPositions = activePositions.filter { position ->
            !position.isPresentAtBroker(brokerQuantities[position.instrumentId])
        }
        closedPositions.forEach { position ->
            val brokerQuantity = brokerQuantities[position.instrumentId] ?: BigDecimal.ZERO
            if (brokerQuantity != BigDecimal.ZERO) {
                reconciliationLogger.error {
                    "КРИТИЧЕСКОЕ расхождение после защитной заявки: ${position.instrumentName}, " +
                        "ожидалось нулевое количество после закрытия ${position.side}, " +
                        "но у брокера осталось $brokerQuantity. Локальная позиция будет закрыта, " +
                        "остаток не будет автоматически принят ботом. Проверьте операции брокера."
                }
            }
            val triggered = positionProtectionService.findTriggeredProtection(position, snapshot)
            if (triggered == null) {
                reconciliationLogger.warn {
                    "Позиция ${position.instrumentName} отсутствует у брокера, но исполненная защитная заявка не найдена"
                }
                return@forEach
            }

            val exchangeOrderId = triggered.stopOrder.takeIf { it.hasExchangeOrderId() }?.exchangeOrderId
            val execution = exchangeOrderId?.let { orderExecutionService.getExecutedOrder(accountId, it) }
            val closePrice = execution?.price ?: triggered.stopOrder.price.toBigDecimal()
            val closeCommission = execution?.commission ?: BigDecimal.ZERO
            val result = positionLifecycleService.recordBrokerProtectionClose(
                accountId = accountId,
                position = position,
                triggeredOrderId = triggered.orderId,
                reason = triggered.reason,
                closePrice = closePrice,
                closeCommission = closeCommission,
                executionOrderId = exchangeOrderId,
                executionStatus = execution?.status ?: triggered.stopOrder.status.name,
                executedLots = execution?.lotsExecuted
            )
            if (result.removeFromState) {
                onClosed?.invoke(position)
            }
        }

        if (closedPositions.isNotEmpty()) {
            portfolioSnapshotService.takeSnapshot(accountId)
            eventPublisherService.publishPositionsChanged()
        }
        reconciliationLogger.debug { "Сверка защитных заявок завершена: $reason" }
    }

    private fun currentPositions(): Collection<OpenPosition> = positionsProvider?.invoke() ?: emptyList()

    private suspend fun closeShortsWithUnsafeMargin(
        accountId: String,
        positions: List<OpenPosition>,
        onClosed: (suspend (OpenPosition) -> Unit)?
    ): List<OpenPosition> {
        val shortPositions = positions.filter { it.side == PositionSide.SHORT }
        if (shortPositions.isEmpty()) return positions

        val margin = usersService.getMarginAttributesSync(accountId)
        val sufficiency = margin.fundsSufficiencyLevel.toBigDecimal()
        val missingFunds = margin.amountOfMissingFunds.toBigDecimal()
        val minimumSufficiency = minimumMarginSufficiency.toBigDecimal()
        val marginUnsafe = missingFunds > BigDecimal.ZERO || sufficiency < minimumSufficiency
        if (!marginUnsafe) return positions

        reconciliationLogger.error {
            "Маржинальные показатели небезопасны: достаточность=$sufficiency, " +
                    "недостающие средства=$missingFunds. Закрываем ${shortPositions.size} шорт-позиций"
        }
        val closedIds = mutableSetOf<String>()
        for (position in shortPositions) {
            val result = positionLifecycleService.closePositionWithRetry(
                accountId = accountId,
                position = position,
                reason = "MARGIN_RISK_CLOSE",
                explanation = "Шорт закрыт из-за недостаточной маржи: " +
                    "достаточность=$sufficiency, недостающие средства=$missingFunds RUB"
            )
            if (result.removeFromState) {
                onClosed?.invoke(result.position)
                closedIds += result.position.positionId
            }
        }
        return positions.filterNot { it.positionId in closedIds }
    }

    private fun matchesCloseDirection(direction: String, position: OpenPosition): Boolean =
        when (position.side) {
            PositionSide.LONG -> direction == "ORDER_DIRECTION_SELL"
            PositionSide.SHORT -> direction == "ORDER_DIRECTION_BUY"
        }

    private fun OpenPosition.isPresentAtBroker(quantity: BigDecimal?): Boolean = when (side) {
        PositionSide.LONG -> quantity != null && quantity > BigDecimal.ZERO
        PositionSide.SHORT -> quantity != null && quantity < BigDecimal.ZERO
    }
}
