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
import ru.tinkoff.piapi.contract.v1.TradesStreamResponse
import ru.tinkoff.piapi.core.InvestApi
import ru.tinkoff.piapi.core.stream.StreamProcessor
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

private val reconciliationLogger = KotlinLogging.logger {}

@Service
class BrokerProtectionReconciliationService(
    private val investApi: InvestApi,
    private val brokerPortfolioSyncService: BrokerPortfolioSyncService,
    private val positionProtectionService: PositionProtectionService,
    private val positionLifecycleService: PositionLifecycleService,
    private val orderExecutionService: OrderExecutionService,
    private val portfolioSnapshotService: PortfolioSnapshotService,
    private val eventPublisherService: EventPublisherService,
    @Qualifier("sandboxEnabled") private val sandboxEnabled: Boolean,
    @Value("\${broker.protection.reconciliation-delay-ms:180000}")
    private val reconciliationDelayMs: Long
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reconciliationInProgress = AtomicBoolean(false)
    private val reconnectInProgress = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val reconnectDelays = longArrayOf(5_000, 15_000, 60_000)

    private var accountId: String? = null
    private var positionsProvider: (() -> Collection<OpenPosition>)? = null
    private var onPositionClosed: (suspend (OpenPosition) -> Unit)? = null
    private var streamKey: String? = null
    private var periodicJob: Job? = null
    private var reconnectAttempt = 0

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

    fun stop() {
        started.set(false)
        streamKey?.let(investApi.ordersStreamService::closeStream)
        streamKey = null
        periodicJob?.cancel()
        periodicJob = null
        reconnectAttempt = 0
    }

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

    suspend fun reconcileAtStartup(
        accountId: String,
        positions: Collection<OpenPosition>,
        onPositionClosed: suspend (OpenPosition) -> Unit
    ) {
        if (sandboxEnabled || positions.isEmpty()) return
        reconcilePositions(accountId, positions.toList(), "запуск приложения", onPositionClosed)
    }

    private fun subscribeToTrades(accountId: String) {
        streamKey = investApi.ordersStreamService.subscribeTrades(
            StreamProcessor(::handleTradeStreamMessage),
            Consumer(::handleStreamError),
            listOf(accountId)
        )
        reconciliationLogger.info { "Подключён поток исполнений заявок T-Invest для защитных заявок" }
    }

    private fun handleTradeStreamMessage(response: TradesStreamResponse) {
        reconnectAttempt = 0
        val trade = response.takeIf(TradesStreamResponse::hasOrderTrades)?.orderTrades ?: return
        if (trade.instrumentUid.isBlank() || trade.tradesCount == 0) return
        if (trade.direction.name != "ORDER_DIRECTION_SELL") return
        if (trade.instrumentUid !in currentPositions().map(OpenPosition::instrumentId)) return

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
        val brokerLongInstrumentIds = brokerPortfolioSyncService.getOpenLongInstrumentIds(accountId)
        val snapshot = positionProtectionService.loadBrokerSnapshot(accountId) ?: return
        val closedPositions = positions.filter { it.instrumentId !in brokerLongInstrumentIds }
        closedPositions.forEach { position ->
            val triggered = positionProtectionService.findTriggeredProtection(position, snapshot)
            if (triggered == null) {
                reconciliationLogger.warn {
                    "Позиция ${position.instrumentName} отсутствует у брокера, но исполненная защитная заявка не найдена"
                }
                return@forEach
            }

            val execution = triggered.stopOrder.takeIf { it.hasExchangeOrderId() }
                ?.exchangeOrderId
                ?.let { orderExecutionService.getExecutedOrder(accountId, it) }
            val closePrice = execution?.price ?: triggered.stopOrder.price.toBigDecimal()
            val closeCommission = execution?.commission ?: BigDecimal.ZERO
            val result = positionLifecycleService.recordBrokerProtectionClose(
                accountId = accountId,
                position = position,
                triggeredOrderId = triggered.orderId,
                reason = triggered.reason,
                closePrice = closePrice,
                closeCommission = closeCommission
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

    private fun MoneyValue.toBigDecimal(): BigDecimal = BigDecimal.valueOf(units)
        .add(BigDecimal.valueOf(nano.toLong(), 9))

    private fun currentPositions(): Collection<OpenPosition> = positionsProvider?.invoke() ?: emptyList()
}
