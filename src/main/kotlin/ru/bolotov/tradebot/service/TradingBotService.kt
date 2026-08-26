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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.awaitClose
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.config.PositionSizingConfig
import ru.bolotov.tradebot.broker.getTradingStatusesSync
import ru.bolotov.tradebot.api.ClosedTradeResponse
import ru.bolotov.tradebot.api.DashboardMetricsResponse
import ru.bolotov.tradebot.api.DashboardResponse
import ru.bolotov.tradebot.api.OpenPositionResponse
import ru.bolotov.tradebot.domain.model.OrderDirection as DomainOrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.strategy.CandlestickPatternStrategy
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.MarketDataProvider
import ru.bolotov.tradebot.strategy.OrderDirection
import ru.bolotov.tradebot.strategy.StrategyManager
import ru.bolotov.tradebot.strategy.TradingStrategy
import ru.tinkoff.piapi.contract.v1.LastPrice
import ru.tinkoff.piapi.contract.v1.LastPriceInstrument
import ru.tinkoff.piapi.contract.v1.MarketDataResponse
import ru.tinkoff.piapi.contract.v1.MarketDataServerSideStreamRequest
import ru.tinkoff.piapi.contract.v1.SubscribeLastPriceRequest
import ru.tinkoff.piapi.contract.v1.SubscriptionAction
import ru.ttech.piapi.core.InvestApi
import ru.ttech.piapi.core.MarketDataServiceSync
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Service
class TradingBotService(
    private val marketDataProvider: MarketDataProvider,
    private val investApi: InvestApi,
    private val marketDataService: MarketDataServiceSync,
    private val strategyManager: StrategyManager,
    private val candlestickPatternStrategy: CandlestickPatternStrategy,
    private val eventPublisherService: EventPublisherService,
    private val brokerAccountService: BrokerAccountService,
    private val instrumentSelectionService: InstrumentSelectionService,
    private val strategyConfigurationService: TradingStrategyConfigurationService,
    private val brokerPortfolioSyncService: BrokerPortfolioSyncService,
    private val tradeDecisionService: TradeDecisionService,
    private val aiTradeSignalFilter: AiTradeSignalFilter,
    private val positionLifecycleService: PositionLifecycleService,
    private val portfolioSnapshotService: PortfolioSnapshotService,
    private val tradeEventService: TradeEventService,
    private val positionSizingConfig: PositionSizingConfig,
    private val positionProtectionService: PositionProtectionService,
    private val brokerProtectionReconciliationService: BrokerProtectionReconciliationService,
    @Value("\${trading.loop.delay-ms:7200000}") private val loopDelayMs: Long,
    @Value("\${instrument.rescan.interval-ms:7200000}") private val instrumentRescanIntervalMs: Long,
    @Value("\${trading.availability.refresh.interval-ms:60000}")
    private val tradingAvailabilityRefreshIntervalMs: Long,
    @Value("\${trading.exit.profit.min-strategy-confidence:0.80}")
    private val minProfitExitConfidence: Double,
    @Value("\${trading.exit.loss.min-strategy-confidence:0.80}")
    private val minLossExitConfidence: Double,
    @Value("\${trading.reentry-cooldown-candles:2}") private val reentryCooldownCandles: Long
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
    private var tradingAvailabilityJob: Job? = null

    private val emergencyCloseChunkSize = 2
    private val emergencyCloseChunkDelayMs = 1500L
    private val streamReconnectDelayMs = 5000L
    private val processedStrategyCandles = ConcurrentHashMap<String, String>()
    private val pendingLossExitConfirmations = ConcurrentHashMap<String, String>()
    private val reentryCooldownUntil = ConcurrentHashMap<String, Instant>()
    private val isRescanningInstruments = AtomicBoolean(false)
    private var isPortfolioRestored = false
    private var isClosingPositions = false
    private var nextInstrumentRescanAt: Instant = Instant.MAX
    private var lastTradingAvailability: Map<String, Boolean> = emptyMap()

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
        nextInstrumentRescanAt = Instant.now().plusMillis(instrumentRescanIntervalMs)
        startPriceStream()
        startBrokerProtectionReconciliation()
        startScheduler()
        startTradingAvailabilityUpdates()
    }

    fun stop() {
        _isRunning.value = false
        priceStreamJob?.cancel()
        schedulerJob?.cancel()
        tradingAvailabilityJob?.cancel()
        brokerProtectionReconciliationService.stop()
        logger.info { "Бот остановлен" }
        eventPublisherService.publishBotStatusChanged("STOPPED")
    }

    suspend fun rescanInstruments(): List<String> {
        if (!isRescanningInstruments.compareAndSet(false, true)) {
            logger.warn { "Рескан инструментов уже выполняется, новый запуск пропущен" }
            return _activeInstruments.value
        }

        try {
            val selectedInstruments = instrumentSelectionService.selectByCurrentFilters()
            if (selectedInstruments.isEmpty() && _activeInstruments.value.isNotEmpty()) {
                logger.info {
                    "Рескан не изменил список: не найдено доступных для торговли инструментов, " +
                        "сохраняем текущий список до следующей сессии"
                }
                return _activeInstruments.value
            }
            applySelectedInstruments(selectedInstruments)
            publishTradingAvailabilityIfChanged()
            restartPriceStreamIfRunning()
            return _activeInstruments.value
        } finally {
            isRescanningInstruments.set(false)
        }
    }

    fun updateInstruments(instruments: List<String>) {
        _activeInstruments.value = instruments
        publishTradingAvailabilityIfChanged()
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

    fun getOpenPositions(): List<OpenPositionResponse> {
        val positions = _openPositions.value.values.toList()
        val currentPrices = marketDataProvider.getCurrentPrices(
            positions.map(OpenPosition::instrumentId)
        )

        return positions.map { position ->
            position.toResponse(
                currentPrice = currentPrices[position.instrumentId],
                aiExplanation = tradeEventService.findOpenEvent(position.positionId)?.explanation?.aiExplanation()
            )
        }
    }

    private fun OpenPosition.toResponse(
        currentPrice: BigDecimal?,
        aiExplanation: String?
    ): OpenPositionResponse =
        OpenPositionResponse(
            positionId = positionId,
            instrumentId = instrumentId,
            instrumentName = instrumentName,
            direction = direction.name,
            positionSide = side.name,
            entryPrice = entryPrice,
            currentPrice = currentPrice,
            unrealizedPnl = currentPrice?.let { price ->
                calculateUnrealizedPnl(price)
            },
            quantity = quantity,
            lotSize = lotSize,
            entryCommission = entryCommission,
            entryTime = entryTime.toString(),
            aiExplanation = aiExplanation
        )

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
                    positionSide = event.positionSide.name,
                    entryPrice = openEvent?.price ?: event.price,
                    entryTime = openEvent?.processedAt?.toString() ?: openEvent?.createdAt?.toString(),
                    closePrice = event.price,
                    quantity = event.quantity,
                    lotSize = event.lotSize,
                    realizedPnl = event.pnl ?: BigDecimal.ZERO,
                    closedAt = (event.processedAt ?: event.createdAt).toString(),
                    closeExplanation = event.explanation
                )
            },
            metrics = DashboardMetricsResponse(
                realizedPnl = realizedPnl,
                dailyPnl = dailyPnl,
                winRate = winRate,
                closedTradesCount = closedEvents.size,
                availableCash = portfolioSnapshotService.getLatestAvailableCash()
            )
        )
    }

    data class ManualCloseResult(val status: String, val closed: Boolean)

    suspend fun closePosition(positionId: String): ManualCloseResult {
        val position = _openPositions.value.values.firstOrNull { it.positionId == positionId }
            ?: return ManualCloseResult("already_closed", false)
        val currentAccountId = accountId ?: return ManualCloseResult("account_not_selected", false)
        val result = positionLifecycleService.closePositionWithRetry(
            accountId = currentAccountId,
            position = position,
            reason = "MANUAL_CLOSE"
        )
        if (result.removeFromState) {
            _openPositions.value = _openPositions.value - position.instrumentId
            registerReentryCooldown(position.instrumentId)
            refreshPortfolioAfterTrade(currentAccountId)
            eventPublisherService.publishPositionsChanged()
            brokerProtectionReconciliationService.requestReconciliation("ручное закрытие позиции")
        }
        return if (result.closed) ManualCloseResult("closed", true) else ManualCloseResult("close_failed", false)
    }

    suspend fun emergencyStopAndCloseAllPositions() {
        stop()
        closeAllPositions()
    }

    private suspend fun closeAllPositions() {
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
            synchronizePositionsBeforeEmergencyClose(currentAccountId)
            val positions = _openPositions.value.values.toList()
            logger.info { "Начинаем закрытие ${positions.size} позиций" }
            val results = positionLifecycleService.closePositionsInChunks(
                accountId = currentAccountId,
                positions = positions,
                chunkSize = emergencyCloseChunkSize,
                chunkDelayMs = emergencyCloseChunkDelayMs
            )
            val closedInstrumentIds = results.mapNotNull { result ->
                result.position.instrumentId.takeIf { result.removeFromState }
            }
            removeClosedPositions(closedInstrumentIds)
            closedInstrumentIds.forEach(::registerReentryCooldown)
            if (closedInstrumentIds.isNotEmpty()) {
                refreshPortfolioAfterTrade(currentAccountId)
                eventPublisherService.publishPositionsChanged()
            }
            logger.info { "Закрытие всех позиций завершено" }
        } finally {
            isClosingPositions = false
        }
    }

    private suspend fun synchronizePositionsBeforeEmergencyClose(accountId: String) {
        val brokerPositions = brokerPortfolioSyncService.restorePositions(accountId).positions
        if (brokerPositions.isNotEmpty()) {
            _openPositions.value += brokerPositions
        }
    }

    fun getActiveInstruments(): List<String> = _activeInstruments.value
    suspend fun getActiveInstrumentDetails(): List<Map<String, Any>> {
        val tradingAvailability = getTradingAvailability(_activeInstruments.value)
        return _activeInstruments.value.map { uid ->
            val info = marketDataProvider.getInstrumentInfo(uid)
            mapOf(
                "id" to uid,
                "ticker" to (info?.ticker ?: uid),
                "name" to (info?.name ?: "Инструмент"),
                "tradingAvailable" to (tradingAvailability[uid] ?: false)
            )
        }
    }

    fun areAllActiveInstrumentTradingsUnavailable(): Boolean {
        val activeInstruments = _activeInstruments.value
        return activeInstruments.isNotEmpty() && getTradingAvailability(activeInstruments).values.none { it }
    }
    fun getStatus(): Boolean = _isRunning.value
    fun getCurrentStrategy(): TradingStrategy = strategyManager.getCurrentStrategy()

    fun replaceActiveBrokerProtection(
        stopLossPercent: Double,
        takeProfitPercent: Double
    ): ProtectionReplacementResult {
        val selectedAccountId = accountId
            ?: return ProtectionReplacementResult.Failed("Брокерский счёт не выбран")
        return positionProtectionService.replaceProtectionForOpenPositions(
            accountId = selectedAccountId,
            positions = _openPositions.value.values,
            stopLossPercent = stopLossPercent,
            takeProfitPercent = takeProfitPercent
        )
    }

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

    private fun getTradingAvailability(instrumentIds: List<String>): Map<String, Boolean> {
        if (instrumentIds.isEmpty()) return emptyMap()

        return runCatching {
            marketDataService.getTradingStatusesSync(instrumentIds)
                .associate { status ->
                    status.instrumentUid to (
                        status.apiTradeAvailableFlag && status.marketOrderAvailableFlag
                        )
                }
        }.onFailure { error ->
            logger.warn(error) { "Не удалось получить актуальные статусы торговли инструментов" }
        }.getOrElse {
            instrumentIds.associateWith { false }
        }
    }

    private fun startTradingAvailabilityUpdates() {
        tradingAvailabilityJob = scope.launch {
            publishTradingAvailabilityIfChanged(force = true)
            while (_isRunning.value) {
                delay(tradingAvailabilityRefreshIntervalMs)
                publishTradingAvailabilityIfChanged()
            }
        }
    }

    private fun publishTradingAvailabilityIfChanged(force: Boolean = false) {
        val availability = getTradingAvailability(_activeInstruments.value)
        if (!force && availability == lastTradingAvailability) return

        lastTradingAvailability = availability
        eventPublisherService.publishTradingAvailabilityChanged(
            tradingAvailability = availability,
            allTradingUnavailable = availability.isNotEmpty() && availability.values.none { it }
        )
    }

    private suspend fun restorePositionsFromBroker() {
        if (isPortfolioRestored) {
            logger.debug { "Портфель уже был восстановлен, пропускаем повторную синхронизацию" }
            return
        }

        val result = brokerPortfolioSyncService.restorePositions(accountId)
        val persistedOpenPositions = tradeEventService.findUnclosedPositions()
            .associateBy(OpenPosition::instrumentId)
        _openPositions.value = persistedOpenPositions + result.positions
        _activeInstruments.value = (
            _activeInstruments.value +
                result.instrumentIds +
                persistedOpenPositions.keys
            ).distinct()
        accountId?.let { selectedAccountId ->
            brokerProtectionReconciliationService.reconcileAtStartup(
                accountId = selectedAccountId,
                positions = _openPositions.value.values,
                onPositionClosed = { position ->
                    _openPositions.value = _openPositions.value - position.instrumentId
                    registerReentryCooldown(position.instrumentId)
                }
            )
            positionProtectionService.reconcileProtection(selectedAccountId, _openPositions.value.values)
        }
        isPortfolioRestored = true
    }

    private fun startBrokerProtectionReconciliation() {
        val selectedAccountId = accountId ?: return
        brokerProtectionReconciliationService.start(
            accountId = selectedAccountId,
            positionsProvider = { _openPositions.value.values },
            onPositionClosed = { position ->
                _openPositions.value = _openPositions.value - position.instrumentId
                registerReentryCooldown(position.instrumentId)
            }
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startPriceStream() {
        priceStreamJob = scope.launch {
            collectPriceStreamWithReconnect()
        }
    }

    private suspend fun collectPriceStreamWithReconnect() {
        while (_isRunning.value) {
            val instruments = _activeInstruments.value
            if (instruments.isEmpty()) {
                logger.warn { "Нет активных инструментов для подписки" }
                return
            }

            try {
                collectPriceStream(instruments)
                if (!shouldReconnectPriceStream()) return
                logger.warn { "Стрим цен завершился; выполняется переподключение из-за открытых позиций" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!shouldReconnectPriceStream()) {
                    logger.error(error) { "Стрим цен завершился; открытых позиций нет, переподключение не требуется" }
                    return
                }
                logger.error(error) { "Ошибка стрима цен; будет выполнено переподключение" }
            }

            logger.info { "Переподключение к стриму цен через ${streamReconnectDelayMs / 1000} сек." }
            delay(streamReconnectDelayMs)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectPriceStream(instruments: List<String>) {
        logger.info { "Подключение к стриму цен для ${instruments.size} инструментов" }
        subscribeToLastPrices(instruments)
            .mapNotNull { lastPrice ->
                logger.info { "Получена цена для ${lastPrice.instrumentUid}" }
                enrichMarketData(lastPrice)
            }
            .onEach(::publishPositionPriceUpdate)
            .flatMapLatest { marketData -> buildSignalFlow(marketData) }
            .collect { signal ->
                when (signal) {
                    is BotSignal.Close -> closePositionImmediately(
                        position = signal.position,
                        reason = signal.reason,
                        aiResult = signal.aiResult,
                        sourceCandleKey = signal.sourceCandleKey
                    )
                    is BotSignal.Trade -> executeTradeSignal(signal)
                }
            }
    }

    private fun shouldReconnectPriceStream(): Boolean =
        _isRunning.value && _openPositions.value.isNotEmpty()

    private fun subscribeToLastPrices(instrumentUids: List<String>): Flow<LastPrice> = callbackFlow {
        val request = MarketDataServerSideStreamRequest.newBuilder()
            .setSubscribeLastPriceRequest(
                SubscribeLastPriceRequest.newBuilder()
                    .setSubscriptionAction(SubscriptionAction.SUBSCRIPTION_ACTION_SUBSCRIBE)
                    .addAllInstruments(instrumentUids.map(::lastPriceInstrument))
                    .build()
            )
            .build()
        val streamJob = launch {
            runCatching {
                val stream = investApi.marketDataStreamServiceAsync.marketDataServerSideStream(
                    request,
                    { response -> if (response.hasLastPrice()) trySend(response.lastPrice) }
                )
                stream.join()
            }.onFailure { error ->
                if (error !is CancellationException) {
                    logger.error(error) { "Ошибка стрима цен" }
                    close(error)
                }
            }
        }
        logger.info { "Стрим цен запущен, подписаны ${instrumentUids.size} инструментов" }

        awaitClose {
            streamJob.cancel()
            logger.info { "Стрим цен закрыт" }
        }
    }

    private fun lastPriceInstrument(instrumentId: String): LastPriceInstrument =
        LastPriceInstrument.newBuilder().setInstrumentId(instrumentId).build()

    private suspend fun enrichMarketData(lastPrice: LastPrice): MarketData? {
        return try {
            val marketData = marketDataProvider.fetchMarketData(lastPrice.instrumentUid)
            if (marketData == null) {
                null
            } else {
                addCandlestickContext(marketData)
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка обогащения рыночных данных для ${lastPrice.instrumentUid}" }
            null
        }
    }

    private suspend fun addCandlestickContext(marketData: MarketData): MarketData {
        if (!strategyManager.isCandlestickStrategyActive()) {
            logger.debug { "Свечной анализ пропущен для ${marketData.instrumentName}: активна другая стратегия" }
            return marketData
        }

        val patternResult = candlestickPatternStrategy.analyzePatternWithCandles(
            instrumentUid = marketData.instrumentId,
            confirmationPrice = marketData.currentPrice
        )
        logger.info {
            "Свечной анализ для ${marketData.instrumentName}: " +
                "паттерн=${patternResult.pattern}, сигнал=${patternResult.direction}, " +
                "уверенность=${patternResult.confidence}"
        }
        return marketData.copy(candlestickPattern = patternResult)
    }

    private fun buildSignalFlow(marketData: MarketData) = flow {
        val position = _openPositions.value[marketData.instrumentId]
        val riskCloseReason = position?.let {
            checkStopLossOrTakeProfit(it, marketData.currentPrice)
        }
        if (position != null && riskCloseReason != null) {
            emit(BotSignal.Close(position, riskCloseReason))
            return@flow
        }

        if (!shouldProcessStrategyCandle(marketData)) return@flow

        val strategy = strategyManager.getCurrentStrategy()
        val signal = strategy.analyze(marketData)
        logStrategyAnalysis(strategy, marketData, signal)

        if (signal.direction == OrderDirection.SELL) {
            val currentPosition = _openPositions.value[marketData.instrumentId]
            if (currentPosition?.side == PositionSide.SHORT) return@flow

            val longPosition = currentPosition ?: synchronizePositionForSell(marketData)
            if (longPosition != null) {
                emitCloseSignalIfApproved(longPosition, marketData, signal, strategy)
                return@flow
            }

            emitOpenSignalIfApproved(marketData, signal, strategy, null)
            return@flow
        }

        position?.let { pendingLossExitConfirmations.remove(it.instrumentId) }

        if (signal.direction == OrderDirection.HOLD || signal.confidence <= 0.5) {
            logger.debug {
                "Сигнал отклонён: ${marketData.instrumentName}, причина=${if (signal.direction == OrderDirection.HOLD) "HOLD" else "низкая уверенность=${signal.confidence}"}"
            }
            return@flow
        }

        val currentPosition = _openPositions.value[marketData.instrumentId]
        if (currentPosition?.side == PositionSide.SHORT) {
            emitCloseSignalIfApproved(currentPosition, marketData, signal, strategy)
            return@flow
        }
        if (currentPosition == null) emitOpenSignalIfApproved(marketData, signal, strategy, null)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<BotSignal>.emitCloseSignalIfApproved(
        position: OpenPosition,
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal,
        strategy: TradingStrategy
    ) {
        if (!canCloseByStrategy(position, marketData, signal)) return

        val aiResult = aiTradeSignalFilter.evaluate(marketData, signal, strategy, position)
        if (!aiResult.approved) {
            logger.info { "Закрытие отклонено AI-фильтром: ${marketData.instrumentName}" }
            return
        }
        pendingLossExitConfirmations.remove(position.instrumentId)
        emit(
            BotSignal.Close(
                position = position,
                reason = CloseReason.STRATEGY_SIGNAL,
                aiResult = aiResult,
                sourceCandleKey = marketData.signalCandleKey
            )
        )
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<BotSignal>.emitOpenSignalIfApproved(
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal,
        strategy: TradingStrategy,
        currentPosition: OpenPosition?
    ) {
        if (!canExecuteOpenSignal(marketData, signal)) return

        val aiResult = aiTradeSignalFilter.evaluate(marketData, signal, strategy, currentPosition)
        if (!aiResult.approved) {
            logger.info { "Открытие отклонено AI-фильтром: ${marketData.instrumentName}" }
            return
        }
        logger.info { "Сигнал на открытие принят: ${marketData.instrumentName} -> ${signal.direction}" }
        emit(
            BotSignal.Trade(
                marketData,
                signal,
                strategy.name,
                strategy.getExplanation(marketData),
                aiResult
            )
        )
    }

    private fun canExecuteOpenSignal(
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal
    ): Boolean {
        if (signal.direction == OrderDirection.SELL && !positionSizingConfig.shortTradingEnabled) {
            logger.debug { "Шорт ${marketData.instrumentName} пропущен: выключен в настройках риска" }
            return false
        }
        val cooldownEndsAt = reentryCooldownUntil[marketData.instrumentId] ?: return true
        if (Instant.now().isBefore(cooldownEndsAt)) {
            logger.debug {
                "Повторный вход для ${marketData.instrumentName} пропущен до $cooldownEndsAt"
            }
            return false
        }
        reentryCooldownUntil.remove(marketData.instrumentId, cooldownEndsAt)
        return true
    }

    private fun shouldProcessStrategyCandle(marketData: MarketData): Boolean {
        val candleKey = marketData.signalCandleKey ?: return true
        return processedStrategyCandles.put(marketData.instrumentId, candleKey) != candleKey
    }

    private fun logStrategyAnalysis(
        strategy: TradingStrategy,
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal
    ) {
        val candleContext = marketData.candlestickPattern
            ?.let { ", свеча=${it.candleKey}, паттерн=${it.pattern}" }
            ?: ", свеча=${marketData.strategyCandleKey}"
        logger.info {
            "Анализ ${strategy.name}: инструмент=${marketData.instrumentName}$candleContext, " +
                "сигнал=${signal.direction}, уверенность=${signal.confidence}"
        }
    }

    private fun canCloseByStrategy(
        position: OpenPosition,
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal
    ): Boolean {
        val isProfitable = hasProfitAfterEstimatedCommissions(position, marketData.currentPrice)
        val requiredConfidence = if (isProfitable) minProfitExitConfidence else minLossExitConfidence
        if (signal.confidence < requiredConfidence) {
            logger.info {
                "Закрытие ${marketData.instrumentName} пропущено: уверенность ${signal.confidence} " +
                    "ниже порога $requiredConfidence"
            }
            return false
        }

        if (isProfitable) return true

        return confirmLossExitOnNextCandle(position, marketData)
    }

    private fun hasProfitAfterEstimatedCommissions(
        position: OpenPosition,
        currentPrice: BigDecimal
    ): Boolean {
        val priceDifference = when (position.side) {
            PositionSide.LONG -> currentPrice - position.entryPrice
            PositionSide.SHORT -> position.entryPrice - currentPrice
        }
        val grossPnl = priceDifference *
            position.quantity.toBigDecimal() * position.lotSize.toBigDecimal()
        val estimatedTotalCommission = position.entryCommission * BigDecimal(2)
        return grossPnl > estimatedTotalCommission
    }

    private fun confirmLossExitOnNextCandle(
        position: OpenPosition,
        marketData: MarketData
    ): Boolean {
        val candleKey = marketData.signalCandleKey ?: return false
        val previousCandleKey = pendingLossExitConfirmations.put(position.instrumentId, candleKey)
        if (previousCandleKey == null) {
            logger.info {
                "Закрытие ${marketData.instrumentName} в убытке ожидает подтверждения на следующей закрытой свече"
            }
            return false
        }

        logger.info {
            "Закрытие ${marketData.instrumentName} в убытке подтверждено двумя закрытыми свечами"
        }
        return true
    }

    private val MarketData.signalCandleKey: String?
        get() = candlestickPattern?.candleKey ?: strategyCandleKey

    private fun synchronizePositionForSell(marketData: MarketData): OpenPosition? {
        val restoredPosition = brokerPortfolioSyncService.synchronizeLongPositionForSell(
            accountId = accountId,
            marketData = marketData,
            existingPosition = _openPositions.value[marketData.instrumentId]
        ) ?: return null
        _openPositions.value += (restoredPosition.instrumentId to restoredPosition)
        return restoredPosition
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
            strategyExplanation = signal.strategyExplanation.withAiExplanation(signal.aiResult),
            currentPositions = _openPositions.value
        )

        result.openedPosition?.let { position ->
            _openPositions.value += (position.instrumentId to position)
        }
        result.closedInstrumentId?.let { instrumentId ->
            _openPositions.value -= instrumentId
        }
        if (result.openedPosition != null || result.closedInstrumentId != null) {
            refreshPortfolioAfterTrade(currentAccountId)
            eventPublisherService.publishPositionsChanged()
        }
    }

    private fun publishPositionPriceUpdate(marketData: MarketData) {
        _openPositions.value[marketData.instrumentId]?.let { position ->
            eventPublisherService.publishPositionPriceUpdated(position, marketData.currentPrice)
        }
    }

    private suspend fun closePositionImmediately(
        position: OpenPosition,
        reason: CloseReason,
        aiResult: AiFilterResult? = null,
        sourceCandleKey: String? = null
    ) {
        val currentAccountId = accountId
        if (currentAccountId == null) {
            logger.warn { "Закрытие позиции пропущено: брокерский счёт не выбран" }
            return
        }

        val result = positionLifecycleService.closePositionWithRetry(
            accountId = currentAccountId,
            position = position,
            reason = reason.eventReason,
            explanation = aiResult?.toEventExplanation()
        )
        if (result.removeFromState) {
            _openPositions.value = _openPositions.value - result.position.instrumentId
            registerReentryCooldown(result.position.instrumentId, sourceCandleKey)
            refreshPortfolioAfterTrade(currentAccountId)
            eventPublisherService.publishPositionsChanged()
        }
    }

    private fun registerReentryCooldown(
        instrumentId: String,
        sourceCandleKey: String? = null
    ) {
        val candleDurationSeconds = candlestickPatternStrategy.currentTimeframe.minutes * 60L
        val cooldownSeconds = candleDurationSeconds * reentryCooldownCandles.coerceAtLeast(1)
        val cooldownEndsAt = Instant.now().plusSeconds(cooldownSeconds)
        reentryCooldownUntil[instrumentId] = cooldownEndsAt
        pendingLossExitConfirmations.remove(instrumentId)
        sourceCandleKey?.let { processedStrategyCandles[instrumentId] = it }
        logger.info { "Повторный вход для $instrumentId заблокирован до $cooldownEndsAt" }
    }

    private suspend fun refreshPortfolioAfterTrade(accountId: String) {
        portfolioSnapshotService.takeSnapshot(accountId)
    }

    private fun checkStopLossOrTakeProfit(
        position: OpenPosition,
        currentPrice: BigDecimal
    ): CloseReason? {
        val pnlPercent = when (position.side) {
            PositionSide.LONG -> (currentPrice - position.entryPrice) / position.entryPrice
            PositionSide.SHORT -> (position.entryPrice - currentPrice) / position.entryPrice
        }.toDouble()

        return when {
            pnlPercent <= -positionSizingConfig.stopLossPercent -> {
                logger.warn { "Стоп-лосс для ${position.instrumentName}: ${"%.2f".format(pnlPercent * 100)}%" }
                CloseReason.STOP_LOSS
            }

            pnlPercent >= positionSizingConfig.takeProfitPercent -> {
                logger.info { "Тейк-профит для ${position.instrumentName}: ${"%.2f".format(pnlPercent * 100)}%" }
                CloseReason.TAKE_PROFIT
            }

            else -> null
        }
    }

    private fun startScheduler() {
        schedulerJob = scope.launch {
            while (_isRunning.value) {
                try {
                    portfolioSnapshotService.takeSnapshot(accountId)
                    rescanInstrumentsWhenDue()
                    delay(nextSchedulerDelayMs())
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

    private suspend fun rescanInstrumentsWhenDue() {
        if (Instant.now().isBefore(nextInstrumentRescanAt)) return

        try {
            val instruments = rescanInstruments()
            logger.info { "Выполнен плановый рескан: выбрано ${instruments.size} инструментов" }
        } catch (error: Exception) {
            logger.error(error) { "Не удалось выполнить плановый рескан инструментов" }
        } finally {
            nextInstrumentRescanAt = Instant.now().plusMillis(instrumentRescanIntervalMs)
        }
    }

    private fun nextSchedulerDelayMs(): Long {
        val untilRescan = Duration.between(Instant.now(), nextInstrumentRescanAt)
            .toMillis()
            .coerceAtLeast(MIN_SCHEDULER_DELAY_MS)
        return minOf(loopDelayMs, untilRescan)
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

    private companion object {
        const val MIN_SCHEDULER_DELAY_MS = 1_000L
    }
}

private sealed class BotSignal {
    data class Trade(
        val marketData: MarketData,
        val signal: ru.bolotov.tradebot.strategy.Signal,
        val strategyName: String,
        val strategyExplanation: String,
        val aiResult: AiFilterResult
    ) : BotSignal()

    data class Close(
        val position: OpenPosition,
        val reason: CloseReason,
        val aiResult: AiFilterResult? = null,
        val sourceCandleKey: String? = null
    ) : BotSignal()
}

private fun String.withAiExplanation(aiResult: AiFilterResult): String =
    aiResult.toEventExplanation()?.let { "$this\n$it" } ?: this

private fun AiFilterResult.toEventExplanation(): String? = explanation?.let { reason ->
    "AI: $reason${confidence?.let { "; уверенность: ${(it * 100).toInt()}%" }.orEmpty()}"
}

private fun String.aiExplanation(): String? =
    lineSequence().firstOrNull { it.startsWith("AI: ") }?.removePrefix("AI: ")

private enum class CloseReason(val eventReason: String) {
    STOP_LOSS("STOP_LOSS"),
    TAKE_PROFIT("TAKE_PROFIT"),
    STRATEGY_SIGNAL("SIGNAL_CLOSE")
}
