package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.awaitClose
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.api.ClosedTradeResponse
import ru.bolotov.tradebot.api.DashboardMetricsResponse
import ru.bolotov.tradebot.api.DashboardResponse
import ru.bolotov.tradebot.api.OpenPositionResponse
import ru.bolotov.tradebot.domain.model.OrderDirection as DomainOrderDirection
import ru.bolotov.tradebot.strategy.CandlestickPatternStrategy
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.MarketDataProvider
import ru.bolotov.tradebot.strategy.OrderDirection
import ru.bolotov.tradebot.strategy.StrategyManager
import ru.bolotov.tradebot.strategy.TradingStrategy
import ru.tinkoff.piapi.contract.v1.LastPrice
import ru.tinkoff.piapi.contract.v1.MarketDataResponse
import ru.tinkoff.piapi.core.InvestApi
import ru.tinkoff.piapi.core.stream.StreamProcessor
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

private val logger = KotlinLogging.logger {}

@Service
class TradingBotService(
    private val marketDataProvider: MarketDataProvider,
    private val investApi: InvestApi,
    private val strategyManager: StrategyManager,
    private val candlestickPatternStrategy: CandlestickPatternStrategy,
    private val eventPublisherService: EventPublisherService,
    private val brokerAccountService: BrokerAccountService,
    private val instrumentSelectionService: InstrumentSelectionService,
    private val strategyConfigurationService: TradingStrategyConfigurationService,
    private val brokerPortfolioSyncService: BrokerPortfolioSyncService,
    private val tradeDecisionService: TradeDecisionService,
    private val positionLifecycleService: PositionLifecycleService,
    private val portfolioSnapshotService: PortfolioSnapshotService,
    private val tradeEventService: TradeEventService,
    @Value("\${trading.loop.delay-ms:7200000}") private val loopDelayMs: Long
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _activeInstruments = MutableStateFlow<List<String>>(emptyList())
    val activeInstruments: StateFlow<List<String>> = _activeInstruments.asStateFlow()

    private val _openPositions = MutableStateFlow<Map<String, OpenPosition>>(emptyMap())
    val openPositions: StateFlow<Map<String, OpenPosition>> = _openPositions.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var accountId: String? = null
    private var priceStreamJob: Job? = null
    private var schedulerJob: Job? = null

    private val stopLossPercent = 0.02
    private val takeProfitPercent = 0.03
    private val emergencyCloseChunkSize = 2
    private val emergencyCloseChunkDelayMs = 1500L
    private val signalDebounceMs = 5000L
    private val lastSignalTime = ConcurrentHashMap<String, Instant>()
    private val processedCandlestickSignals = ConcurrentHashMap<String, String>()
    private val isRescanningInstruments = AtomicBoolean(false)
    private var isPortfolioRestored = false
    private var isClosingPositions = false

    init {
        runBlocking {
            accountId = brokerAccountService.initializeAccount()
            instrumentSelectionService.loadFilterConfiguration()
            selectInitialInstruments()
            strategyConfigurationService.loadLastConfiguration()
            restorePositionsFromBroker()
        }
    }

    suspend fun start() {
        if (_isRunning.value) {
            logger.warn { "Бот уже запущен" }
            return
        }
        if (accountId == null) {
            logger.error { "Не указан брокерский счёт. Невозможно запустить бота." }
            return
        }

        _isRunning.value = true
        logger.info { "Запуск торгового бота в реактивном режиме" }
        eventPublisherService.publishBotStatusChanged("RUNNING")
        startPriceStream()
        startScheduler()
    }

    fun stop() {
        _isRunning.value = false
        priceStreamJob?.cancel()
        schedulerJob?.cancel()
        logger.info { "Бот остановлен" }
        eventPublisherService.publishBotStatusChanged("STOPPED")
    }

    suspend fun rescanInstruments(): List<String> {
        if (!isRescanningInstruments.compareAndSet(false, true)) {
            logger.warn { "Рескан инструментов уже выполняется, новый запуск пропущен" }
            return _activeInstruments.value
        }

        try {
            applySelectedInstruments(instrumentSelectionService.selectByCurrentFilters())
            restartPriceStreamIfRunning()
            return _activeInstruments.value
        } finally {
            isRescanningInstruments.set(false)
        }
    }

    fun updateInstruments(instruments: List<String>) {
        _activeInstruments.value = instruments
        logger.info { "Обновлён список инструментов: $instruments" }
        restartPriceStreamIfRunning()
    }

    fun updateInstrumentFilters(
        minDailyVolume: Long,
        minVolatility: Double,
        maxVolatility: Double,
        maxCount: Int
    ) {
        instrumentSelectionService.updateFilters(minDailyVolume, minVolatility, maxVolatility, maxCount)
        logger.info { "Для применения новых фильтров вызовите /internal/command/instruments/rescan" }
    }

    fun switchToSimpleStrategy(strategyName: String) {
        strategyConfigurationService.switchToSimpleStrategy(strategyName)
        restartPriceStreamIfRunning()
    }

    fun switchToVotingStrategy(weights: Map<String, Int>) {
        strategyConfigurationService.switchToVotingStrategy(weights)
        restartPriceStreamIfRunning()
    }

    fun switchToConfirmationStrategy(requiredIndicators: List<String>) {
        strategyConfigurationService.switchToConfirmationStrategy(requiredIndicators)
        restartPriceStreamIfRunning()
    }

    fun switchToCandlestickStrategy(timeframe: CandlestickPatternStrategy.CandleTimeframe, minConfidence: Double) {
        strategyConfigurationService.switchToCandlestickStrategy(timeframe, minConfidence)
        restartPriceStreamIfRunning()
    }

    fun getOpenPositions(): List<OpenPositionResponse> =
        _openPositions.value.values.map { position ->
            OpenPositionResponse(
                positionId = position.positionId,
                instrumentId = position.instrumentId,
                instrumentName = position.instrumentName,
                direction = position.direction.name,
                entryPrice = position.entryPrice,
                quantity = position.quantity,
                lotSize = position.lotSize,
                entryCommission = position.entryCommission,
                entryTime = position.entryTime.toString()
            )
        }

    fun getDashboard(): DashboardResponse {
        val closedEvents = tradeEventService.findProcessedCloseEvents()
        val todayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
        val realizedPnl = closedEvents.fold(BigDecimal.ZERO) { total, event -> total + (event.pnl ?: BigDecimal.ZERO) }
        val dailyPnl = closedEvents
            .filter { (it.processedAt ?: it.createdAt) >= todayStart }
            .fold(BigDecimal.ZERO) { total, event -> total + (event.pnl ?: BigDecimal.ZERO) }
        val winRate = if (closedEvents.isEmpty()) 0.0 else {
            closedEvents.count { (it.pnl ?: BigDecimal.ZERO) > BigDecimal.ZERO }.toDouble() / closedEvents.size * 100
        }

        return DashboardResponse(
            openPositions = getOpenPositions(),
            closedTrades = closedEvents.map { event ->
                val openEvent = event.positionId?.let(tradeEventService::findOpenEvent)
                ClosedTradeResponse(
                    positionId = requireNotNull(event.positionId),
                    instrumentId = event.instrumentId,
                    instrumentName = event.instrumentName,
                    direction = event.direction.name,
                    entryPrice = openEvent?.price ?: event.price,
                    entryTime = openEvent?.processedAt?.toString() ?: openEvent?.createdAt?.toString(),
                    closePrice = event.price,
                    quantity = event.quantity,
                    lotSize = event.lotSize,
                    realizedPnl = event.pnl ?: BigDecimal.ZERO,
                    closedAt = (event.processedAt ?: event.createdAt).toString()
                )
            },
            metrics = DashboardMetricsResponse(
                realizedPnl = realizedPnl,
                dailyPnl = dailyPnl,
                winRate = winRate,
                closedTradesCount = closedEvents.size
            )
        )
    }

    data class ManualCloseResult(val status: String, val closed: Boolean)

    suspend fun closePosition(positionId: String): ManualCloseResult {
        val position = _openPositions.value.values.firstOrNull { it.positionId == positionId }
            ?: return ManualCloseResult("already_closed", false)
        val currentAccountId = accountId ?: return ManualCloseResult("account_not_selected", false)
        val result = positionLifecycleService.closePosition(currentAccountId, position, "MANUAL_CLOSE")
        if (result.removeFromState) {
            _openPositions.value = _openPositions.value - position.instrumentId
        }
        return if (result.closed) ManualCloseResult("closed", true) else ManualCloseResult("close_failed", false)
    }

    suspend fun closeAllPositionsAsync() {
        if (isClosingPositions) {
            logger.warn { "Закрытие позиций уже запущено" }
            return
        }

        val currentAccountId = accountId
        if (currentAccountId == null) {
            logger.warn { "Закрытие позиций пропущено: брокерский счёт не выбран" }
            return
        }

        isClosingPositions = true
        try {
            val positions = _openPositions.value.values.toList()
            logger.info { "Начинаем закрытие ${positions.size} позиций" }
            val results = positionLifecycleService.closePositionsInChunks(
                accountId = currentAccountId,
                positions = positions,
                chunkSize = emergencyCloseChunkSize,
                chunkDelayMs = emergencyCloseChunkDelayMs
            )
            removeClosedPositions(results.mapNotNull { it.position.instrumentId.takeIf { _ -> it.removeFromState } })
            logger.info { "Закрытие всех позиций завершено" }
        } finally {
            isClosingPositions = false
        }
    }

    fun getActiveInstruments(): List<String> = _activeInstruments.value
    suspend fun getActiveInstrumentDetails(): List<Map<String, String>> = _activeInstruments.value.map { uid ->
        val info = marketDataProvider.getInstrumentInfo(uid)
        mapOf("id" to uid, "ticker" to (info?.ticker ?: uid), "name" to (info?.name ?: "Инструмент"))
    }
    fun getStatus(): Boolean = _isRunning.value
    fun getCurrentStrategy(): TradingStrategy = strategyManager.getCurrentStrategy()

    private suspend fun selectInitialInstruments() {
        try {
            applySelectedInstruments(instrumentSelectionService.selectByCurrentFilters())
        } catch (e: Exception) {
            logger.error(e) {
                "Не удалось выполнить первичный отбор инструментов через Tinkoff API. " +
                        "Приложение продолжит запуск без активных инструментов."
            }
            _activeInstruments.value = emptyList()
        }
    }

    private fun applySelectedInstruments(selectedInstruments: List<SelectedInstrument>) {
        _activeInstruments.value = instrumentSelectionService.mergeWithOpenPositions(
            selectedInstruments,
            _openPositions.value.keys
        )
    }

    private suspend fun restorePositionsFromBroker() {
        if (isPortfolioRestored) {
            logger.debug { "Портфель уже был восстановлен, пропускаем повторную синхронизацию" }
            return
        }

        val result = brokerPortfolioSyncService.restorePositions(accountId)
        _openPositions.value = result.positions
        _activeInstruments.value = (_activeInstruments.value + result.instrumentIds).distinct()
        isPortfolioRestored = true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startPriceStream() {
        priceStreamJob = scope.launch {
            val instruments = _activeInstruments.value
            if (instruments.isEmpty()) {
                logger.warn { "Нет активных инструментов для подписки" }
                return@launch
            }

            logger.info { "Подключение к стриму цен для ${instruments.size} инструментов" }
            subscribeToLastPrices(instruments)
                .mapNotNull { lastPrice ->
                    logger.info { "Получена цена для ${lastPrice.instrumentUid}" }
                    enrichMarketData(lastPrice)
                }
                .flatMapLatest { marketData -> buildSignalFlow(marketData) }
                .catch { error -> logger.error(error) { "Ошибка в стриме цен" } }
                .collect { signal ->
                    when (signal) {
                        is BotSignal.Close -> closePositionByRisk(signal.position)
                        is BotSignal.Trade -> executeTradeSignal(signal)
                    }
                }
        }
    }

    private fun subscribeToLastPrices(instrumentUids: List<String>): Flow<LastPrice> = callbackFlow {
        val streamId = "trade_bot_stream_${System.currentTimeMillis()}"
        val streamService = investApi.marketDataStreamService
        val processor = StreamProcessor<MarketDataResponse> { response ->
            if (response.hasLastPrice()) trySend(response.lastPrice)
        }
        val onErrorCallback = Consumer<Throwable> { error ->
            if (error is io.grpc.StatusRuntimeException && error.status.code == io.grpc.Status.Code.CANCELLED) {
                logger.debug { "Стрим $streamId отменён штатно" }
            } else {
                logger.error(error) { "Ошибка стрима $streamId" }
            }
            close(error)
        }

        val subscription = streamService.newStream(streamId, processor, onErrorCallback)
        subscription.subscribeLastPrices(instrumentUids)
        logger.info { "Стрим $streamId запущен, подписаны ${instrumentUids.size} инструментов" }

        awaitClose {
            logger.info { "Закрытие стрима $streamId" }
            try {
                subscription.unsubscribeLastPrices(instrumentUids)
                subscription.cancel()
            } catch (e: Exception) {
                logger.debug(e) { "Ошибка при закрытии стрима $streamId" }
            }
        }
    }

    private suspend fun enrichMarketData(lastPrice: LastPrice): MarketData? {
        return try {
            val marketData = marketDataProvider.fetchMarketData(lastPrice.instrumentUid)
            val patternResult = if (strategyManager.isCandlestickStrategyActive()) {
                candlestickPatternStrategy.analyzePatternWithCandles(lastPrice.instrumentUid)
                    .takeIf { it.pattern != null && it.confidence >= candlestickPatternStrategy.minConfidence }
            } else {
                null
            }

            logger.info {
                "Свечной анализ для ${lastPrice.instrumentUid}: " +
                        "pattern=${patternResult?.pattern}, direction=${patternResult?.direction}, confidence=${patternResult?.confidence}"
            }
            marketData?.copy(candlestickPattern = patternResult)
        } catch (e: Exception) {
            logger.error(e) { "Ошибка обогащения рыночных данных для ${lastPrice.instrumentUid}" }
            null
        }
    }

    private fun buildSignalFlow(marketData: MarketData) = flow {
        logger.info {
            "marketData для ${marketData.instrumentName}: candlestickPattern=${marketData.candlestickPattern?.direction}"
        }

        val position = _openPositions.value[marketData.instrumentId]
        if (position != null && checkStopLossOrTakeProfit(position, marketData.currentPrice)) {
            emit(BotSignal.Close(position))
            return@flow
        }

        val now = Instant.now()
        val lastTime = lastSignalTime[marketData.instrumentId]
        if (lastTime != null && now.toEpochMilli() - lastTime.toEpochMilli() <= signalDebounceMs) return@flow

        val strategy = strategyManager.getCurrentStrategy()
        val signal = strategy.analyze(marketData)
        logger.info {
            "Анализ: инструмент=${marketData.instrumentName}, паттерн=${marketData.candlestickPattern?.direction}, " +
                    "сигнал=${signal.direction}, уверенность=${signal.confidence}"
        }

        if (signal.direction == OrderDirection.HOLD || signal.confidence <= 0.5) {
            logger.debug {
                "Сигнал отклонён: ${marketData.instrumentName}, причина=${if (signal.direction == OrderDirection.HOLD) "HOLD" else "низкая уверенность=${signal.confidence}"}"
            }
            return@flow
        }

        if (canExecuteSignal(marketData, signal)) {
            logger.info { "Сигнал принят: ${marketData.instrumentName} -> ${signal.direction}" }
            marketData.candlestickPattern?.candleKey?.let { candleKey ->
                processedCandlestickSignals[marketData.instrumentId] = candleKey
            }
            lastSignalTime[marketData.instrumentId] = now
            emit(BotSignal.Trade(marketData, signal, strategy.name, strategy.getExplanation(marketData)))
        } else {
            lastSignalTime[marketData.instrumentId] = now
        }
    }

    private suspend fun canExecuteSignal(
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal
    ): Boolean {
        val candleKey = marketData.candlestickPattern?.candleKey
        if (candleKey != null && processedCandlestickSignals[marketData.instrumentId] == candleKey) {
            logger.debug {
                "Повторный сигнал свечного паттерна для ${marketData.instrumentName} на той же свече пропущен"
            }
            return false
        }

        if (signal.direction != OrderDirection.SELL) return true
        val restoredPosition = brokerPortfolioSyncService.synchronizeLongPositionForSell(
            accountId = accountId,
            marketData = marketData,
            existingPosition = _openPositions.value[marketData.instrumentId]
        ) ?: return false
        _openPositions.value += (restoredPosition.instrumentId to restoredPosition)
        return true
    }

    private suspend fun executeTradeSignal(signal: BotSignal.Trade) {
        val currentAccountId = accountId
        if (currentAccountId == null) {
            logger.warn { "Сигнал пропущен: брокерский счёт не выбран" }
            return
        }

        val result = tradeDecisionService.executeTrade(
            accountId = currentAccountId,
            marketData = signal.marketData,
            signal = signal.signal,
            strategyName = signal.strategyName,
            strategyExplanation = signal.strategyExplanation,
            currentPositions = _openPositions.value
        )

        result.openedPosition?.let { position ->
            _openPositions.value += (position.instrumentId to position)
        }
        result.closedInstrumentId?.let { instrumentId ->
            _openPositions.value -= instrumentId
        }
    }

    private suspend fun closePositionByRisk(position: OpenPosition) {
        val currentAccountId = accountId
        if (currentAccountId == null) {
            logger.warn { "Закрытие позиции пропущено: брокерский счёт не выбран" }
            return
        }

        val result = positionLifecycleService.closePosition(currentAccountId, position, "CLOSE")
        if (result.removeFromState) {
            _openPositions.value = _openPositions.value - result.position.instrumentId
        }
    }

    private fun checkStopLossOrTakeProfit(position: OpenPosition, currentPrice: BigDecimal): Boolean {
        val pnlPercent = if (position.direction == DomainOrderDirection.BUY) {
            (currentPrice - position.entryPrice) / position.entryPrice
        } else {
            (position.entryPrice - currentPrice) / position.entryPrice
        }.toDouble()

        return when {
            pnlPercent <= -stopLossPercent -> {
                logger.warn { "Стоп-лосс для ${position.instrumentName}: ${"%.2f".format(pnlPercent * 100)}%" }
                true
            }

            pnlPercent >= takeProfitPercent -> {
                logger.info { "Тейк-профит для ${position.instrumentName}: ${"%.2f".format(pnlPercent * 100)}%" }
                true
            }

            else -> false
        }
    }

    private fun startScheduler() {
        schedulerJob = scope.launch {
            while (_isRunning.value) {
                try {
                    portfolioSnapshotService.takeSnapshot(accountId)
                    delay(loopDelayMs)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        logger.debug { "Планировщик остановлен" }
                        return@launch
                    }
                    logger.error(e) { "Ошибка в планировщике" }
                    delay(10000)
                }
            }
        }
    }

    private fun restartPriceStreamIfRunning() {
        if (_isRunning.value) {
            priceStreamJob?.cancel()
            startPriceStream()
            logger.info { "Стрим цен перезапущен" }
        }
    }

    private fun removeClosedPositions(instrumentIds: List<String>) {
        if (instrumentIds.isEmpty()) return
        _openPositions.value -= instrumentIds.toSet()
    }
}

private sealed class BotSignal {
    data class Trade(
        val marketData: MarketData,
        val signal: ru.bolotov.tradebot.strategy.Signal,
        val strategyName: String,
        val strategyExplanation: String
    ) : BotSignal()

    data class Close(val position: OpenPosition) : BotSignal()
}
