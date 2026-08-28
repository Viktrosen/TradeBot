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
import ru.bolotov.tradebot.broker.toBigDecimal
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
import ru.bolotov.tradebot.strategy.regime.MarketRegimeService
import ru.bolotov.tradebot.strategy.regime.MarketRegimeStrategySelector
import ru.bolotov.tradebot.strategy.regime.StrategySelection
import ru.bolotov.tradebot.service.data.AiFilterResult
import ru.bolotov.tradebot.service.data.BotSignal
import ru.bolotov.tradebot.service.data.CloseReason
import ru.bolotov.tradebot.service.data.ManualCloseResult
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

/** Центральный оркестратор состояния бота, рыночного стрима и адаптивных торговых решений. */
@Service
class TradingBotService(
    private val marketDataProvider: MarketDataProvider,
    private val investApi: InvestApi,
    private val marketDataService: MarketDataServiceSync,
    private val strategyManager: StrategyManager,
    private val candlestickPatternStrategy: CandlestickPatternStrategy,
    private val marketRegimeService: MarketRegimeService,
    private val marketRegimeStrategySelector: MarketRegimeStrategySelector,
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
    private val processedMarketCandles = ConcurrentHashMap<String, String>()
    private val processedStrategyCandles = ConcurrentHashMap<String, String>()
    private val pendingLossExitConfirmations = ConcurrentHashMap<String, String>()
    private val reentryCooldownUntil = ConcurrentHashMap<String, Instant>()
    private val loggedStrategySelections = ConcurrentHashMap<String, String>()
    private val isRescanningInstruments = AtomicBoolean(false)
    private var isPortfolioRestored = false
    private var isClosingPositions = false
    private var nextInstrumentRescanAt: Instant = Instant.MAX
    private var lastTradingAvailability: Map<String, Boolean> = emptyMap()
    private var streamReconnectAttempt = 0

    init {
        runBlocking {
            accountId = brokerAccountService.initializeAccount()
            instrumentSelectionService.loadFilterConfiguration()
            selectInitialInstruments()
            strategyConfigurationService.loadConfigurations()
            restorePositionsFromBroker()
        }
    }

    /**
     * Запускает поток котировок, периодические проверки брокерской защиты и
     * переотбор инструментов. Повторный запуск не создаёт дублирующие потоки.
     */
    /** Инициализирует счёт, состояние и стрим цен, после чего разрешает торговлю. */
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

    /** Останавливает все фоновые потоки бота, не закрывая позиции. */
    /** Останавливает торговые потоки, но не закрывает существующие позиции. */
    fun stop() {
        _isRunning.value = false
        priceStreamJob?.cancel()
        schedulerJob?.cancel()
        tradingAvailabilityJob?.cancel()
        brokerProtectionReconciliationService.stop()
        logger.info { "Бот остановлен" }
        eventPublisherService.publishBotStatusChanged("STOPPED")
    }

    /**
     * Повторно отбирает торговые инструменты по сохранённым фильтрам и безопасно
     * перезапускает поток цен. Уже открытые позиции остаются в списке наблюдения.
     */
    /** Повторно отбирает инструменты и сохраняет открытые позиции в активном списке. */
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

    /** Заменяет список активных инструментов и переподписывает поток цен. */
    fun updateInstruments(instruments: List<String>) {
        _activeInstruments.value = instruments
        publishTradingAvailabilityIfChanged()
        logger.info { "Обновлён список инструментов: $instruments" }
        restartPriceStreamIfRunning()
    }

    /** Сохраняет фильтры отбора без неявного запуска рескана. */
    fun updateInstrumentFilters(
        minDailyVolume: Long,
        minVolatility: Double,
        maxVolatility: Double,
        maxCount: Int
    ) {
        instrumentSelectionService.updateFilters(minDailyVolume, minVolatility, maxVolatility, maxCount)
        logger.info { "Для применения новых фильтров вызовите /internal/command/instruments/rescan" }
    }

    /** Сохраняет настройки простой стратегии. */
    fun switchToSimpleStrategy(strategyName: String) {
        strategyConfigurationService.switchToSimpleStrategy(strategyName)
        restartPriceStreamIfRunning()
    }

    /** Сохраняет настройки голосующей стратегии. */
    fun switchToVotingStrategy(weights: Map<String, Int>) {
        strategyConfigurationService.switchToVotingStrategy(weights)
        restartPriceStreamIfRunning()
    }

    /** Сохраняет настройки стратегии подтверждения. */
    fun switchToConfirmationStrategy(requiredIndicators: List<String>) {
        strategyConfigurationService.switchToConfirmationStrategy(requiredIndicators)
        restartPriceStreamIfRunning()
    }

    /** Сохраняет настройки свечного анализа для адаптивного выбора режима. */
    fun switchToCandlestickStrategy(timeframe: CandlestickPatternStrategy.CandleTimeframe, minConfidence: Double) {
        strategyConfigurationService.switchToCandlestickStrategy(timeframe, minConfidence)
        restartPriceStreamIfRunning()
    }

    /** Преобразует локальные открытые позиции в ответ внутреннего API. */
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
            entryStrategyName = entryStrategyId
                ?.let(strategyManager::getStrategyById)
                ?.name,
            aiExplanation = aiExplanation
        )

    /** Формирует единый снимок dashboard из позиций, журнала сделок и портфельных метрик. */
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

    /** Закрывает указанную позицию вручную и синхронизирует клиентское состояние. */
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

    /** Останавливает бота и инициирует аварийное закрытие всех известных позиций. */
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

    /** Возвращает текущий список инструментов, на который подписан бот. */
    fun getActiveInstruments(): List<String> = _activeInstruments.value
    /** Загружает отображаемые клиенту тикеры и названия активных инструментов. */
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

    /** Показывает, закрыты ли торги одновременно по всем активным инструментам. */
    fun areAllActiveInstrumentTradingsUnavailable(): Boolean {
        val activeInstruments = _activeInstruments.value
        return activeInstruments.isNotEmpty() && getTradingAvailability(activeInstruments).values.none { it }
    }
    /** Возвращает фактический статус запущенного торгового цикла. */
    fun getStatus(): Boolean = _isRunning.value
    /** Возвращает отображаемую базовую стратегию; сделки выбираются адаптивно по режиму рынка. */
    fun getCurrentStrategy(): TradingStrategy = strategyManager.getCurrentStrategy()

    /** Заменяет SL/TP всех открытых позиций после успешного изменения риск-настроек. */
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
                logger.warn { "Стрим цен завершился без ошибки; будет выполнено переподключение" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!shouldReconnectPriceStream()) {
                    logger.info {
                        "Стрим цен завершился; бот остановлен или активные инструменты отсутствуют, " +
                            "переподключение не требуется"
                    }
                    return
                }
                logger.warn {
                    "Стрим цен завершился с ошибкой ${error.streamErrorDescription()}; " +
                        "будет выполнено переподключение"
                }
            }

            val delayMs = nextStreamReconnectDelayMs()
            logger.info {
                "Переподключение к стриму цен: попытка $streamReconnectAttempt, " +
                    "инструментов=${_activeInstruments.value.size}, ожидание=${delayMs / 1000} сек."
            }
            delay(delayMs)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectPriceStream(instruments: List<String>) {
        logger.info { "Подключение к стриму цен для ${instruments.size} инструментов" }
        subscribeToLastPrices(instruments)
            .onEach { resetStreamReconnectBackoff() }
            .mapNotNull { lastPrice ->
                enrichMarketData(lastPrice)?.also { marketData ->
                    logger.info {
                        "Получена цена для ${marketData.instrumentId} (${marketData.instrumentName})"
                    }
                }
            }
            .onEach(::publishPositionPriceUpdate)
            .flatMapLatest { marketData -> buildSignalFlow(marketData) }
            .collect { signal ->
                when (signal) {
                    is BotSignal.Close -> closePositionImmediately(
                        position = signal.position,
                        reason = signal.reason,
                        aiResult = signal.aiResult,
                        sourceCandleKey = signal.sourceCandleKey,
                        strategyId = signal.strategyId
                    )
                    is BotSignal.Trade -> executeTradeSignal(signal)
                }
            }
    }

    private fun shouldReconnectPriceStream(): Boolean =
        _isRunning.value && _activeInstruments.value.isNotEmpty()

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

    /**
     * Дополняет потоковую цену индикаторами. Свечной контекст добавляется позднее,
     * только если выбранная для конкретного инструмента стратегия действительно
     * работает со свечными паттернами.
     */
    private suspend fun enrichMarketData(lastPrice: LastPrice): MarketData? {
        return try {
            marketDataProvider.fetchMarketData(
                instrumentUid = lastPrice.instrumentUid,
                currentPrice = lastPrice.price.toBigDecimal()
            )
        } catch (e: Exception) {
            logger.error(e) { "Ошибка обогащения рыночных данных для ${lastPrice.instrumentUid}" }
            null
        }
    }

    /** Загружает паттерн только для свечной стратегии, выбранной режимом рынка. */
    private suspend fun addCandlestickContext(
        marketData: MarketData,
        strategy: TradingStrategy
    ): MarketData {
        if (!strategyManager.isCandlestickStrategy(strategy)) {
            logger.debug { "Свечной анализ пропущен для ${marketData.instrumentName}: выбрана ${strategy.name}" }
            return marketData
        }

        val patternResult = candlestickPatternStrategy.analyzePatternWithCandles(
            instrumentUid = marketData.instrumentId,
            confirmationPrice = marketData.currentPrice
        )
        logger.info {
            "Свечной анализ для ${marketData.instrumentName}: " +
                "${patternResult.pattern?.name ?: "паттерн не обнаружен"}, сигнал=${patternResult.direction}, " +
                "уверенность=${patternResult.confidence}"
        }
        return marketData.copy(candlestickPattern = patternResult)
    }

    /**
     * Формирует торговое действие для новой закрытой свечи.
     *
     * Стоп-лосс и тейк-профит проверяются на каждом обновлении цены и всегда
     * имеют приоритет над режимом рынка, стратегией и AI-фильтром.
     */
    private fun buildSignalFlow(marketData: MarketData) = flow {
        val position = _openPositions.value[marketData.instrumentId]
        val riskCloseReason = position?.let {
            checkStopLossOrTakeProfit(it, marketData.currentPrice)
        }
        if (position != null && riskCloseReason != null) {
            emit(BotSignal.Close(position, riskCloseReason))
            return@flow
        }

        if (!shouldProcessMarketCandle(marketData)) return@flow

        val regimeDecision = marketRegimeService.evaluate(marketData)
        val currentPosition = _openPositions.value[marketData.instrumentId]
        val selection = selectStrategy(currentPosition, regimeDecision.regime)
        logStrategySelectionIfChanged(marketData, currentPosition, regimeDecision, selection)
        selection ?: return@flow
        val strategyMarketData = addCandlestickContext(marketData, selection.strategy)
        if (!shouldProcessStrategyCandle(strategyMarketData, selection)) return@flow

        val strategy = selection.strategy
        val signal = strategy.analyze(strategyMarketData)
        logStrategyAnalysis(strategy, strategyMarketData, signal, selection)

        if (signal.direction == OrderDirection.SELL) {
            val currentPosition = _openPositions.value[strategyMarketData.instrumentId]
            if (currentPosition?.side == PositionSide.SHORT) return@flow

            val longPosition = currentPosition ?: synchronizePositionForSell(strategyMarketData)
            if (longPosition != null) {
                emitCloseSignalIfApproved(longPosition, strategyMarketData, signal, strategy)
                return@flow
            }

            emitOpenSignalIfApproved(strategyMarketData, signal, selection, null)
            return@flow
        }

        currentPosition?.let { pendingLossExitConfirmations.remove(it.instrumentId) }

        if (signal.direction == OrderDirection.HOLD || signal.confidence <= 0.5) {
            logger.debug {
                "Сигнал отклонён: ${strategyMarketData.instrumentName}, причина=${if (signal.direction == OrderDirection.HOLD) "HOLD" else "низкая уверенность=${signal.confidence}"}"
            }
            return@flow
        }

        if (currentPosition?.side == PositionSide.SHORT) {
            emitCloseSignalIfApproved(currentPosition, strategyMarketData, signal, strategy)
            return@flow
        }
        if (currentPosition == null) emitOpenSignalIfApproved(strategyMarketData, signal, selection, null)
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
                sourceCandleKey = marketData.candlestickPattern?.candleKey ?: marketData.signalCandleKey,
                strategyId = strategyManager.getCurrentStrategyIdFor(strategy)
            )
        )
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<BotSignal>.emitOpenSignalIfApproved(
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal,
        selection: StrategySelection,
        currentPosition: OpenPosition?
    ) {
        if (!canExecuteOpenSignal(marketData, signal)) return

        val aiResult = aiTradeSignalFilter.evaluate(marketData, signal, selection.strategy, currentPosition)
        if (!aiResult.approved) {
            logger.info { "Открытие отклонено AI-фильтром: ${marketData.instrumentName}" }
            return
        }
        logger.info { "Сигнал на открытие принят: ${marketData.instrumentName} -> ${signal.direction}" }
        emit(
            BotSignal.Trade(
                marketData,
                signal,
                selection.id,
                selection.strategy.name,
                selection.strategy.getExplanation(marketData),
                selection.regime,
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

    private fun shouldProcessMarketCandle(marketData: MarketData): Boolean {
        val candleKey = marketData.signalCandleKey ?: return true
        return processedMarketCandles.put(marketData.instrumentId, candleKey) != candleKey
    }

    private fun shouldProcessStrategyCandle(
        marketData: MarketData,
        selection: StrategySelection
    ): Boolean {
        val candleKey = marketData.candlestickPattern?.candleKey ?: marketData.signalCandleKey ?: return true
        val processingKey = "${marketData.instrumentId}:${selection.id}"
        return processedStrategyCandles.put(processingKey, candleKey) != candleKey
    }

    private fun selectStrategy(
        position: OpenPosition?,
        regime: ru.bolotov.tradebot.strategy.regime.MarketRegime
    ): StrategySelection? = if (position == null) {
        marketRegimeStrategySelector.selectForNewPosition(regime)
    } else {
        marketRegimeStrategySelector.selectForOpenPosition(position.entryStrategyId, regime)
    }

    /** Логирует изменение стратегии ровно один раз на инструмент и новое состояние выбора. */
    private fun logStrategySelectionIfChanged(
        marketData: MarketData,
        position: OpenPosition?,
        regimeDecision: ru.bolotov.tradebot.strategy.regime.MarketRegimeDecision,
        selection: StrategySelection?
    ) {
        val selectionKey = listOf(
            position?.positionId.orEmpty(),
            position?.entryStrategyId.orEmpty(),
            regimeDecision.regime.name,
            selection?.id.orEmpty()
        ).joinToString(":")
        if (loggedStrategySelections.put(marketData.instrumentId, selectionKey) == selectionKey) return

        if (selection == null) {
            logger.info {
                "Стратегия инструмента ${marketData.instrumentName}: режим=${regimeDecision.regime}, " +
                    "новые входы временно приостановлены; наблюдение и переоценка продолжаются"
            }
            return
        }

        val scenario = if (position == null) "новый вход" else "сопровождение позиции"
        logger.info {
            "Стратегия инструмента ${marketData.instrumentName}: режим=${regimeDecision.regime}, " +
                "стратегия=${selection.strategy.name} (${selection.id}), сценарий=$scenario"
        }
    }

    /** Возвращает очередную задержку переподключения, увеличивая её при сериях обрывов. */
    private fun nextStreamReconnectDelayMs(): Long {
        val delayMs = STREAM_RECONNECT_DELAYS_MS[
            streamReconnectAttempt.coerceAtMost(STREAM_RECONNECT_DELAYS_MS.lastIndex)
        ]
        streamReconnectAttempt = (streamReconnectAttempt + 1)
            .coerceAtMost(STREAM_RECONNECT_DELAYS_MS.size)
        return delayMs
    }

    /** Сбрасывает backoff только после фактически полученной цены из нового стрима. */
    private fun resetStreamReconnectBackoff() {
        if (streamReconnectAttempt == 0) return

        streamReconnectAttempt = 0
        logger.info { "Стрим цен восстановлен: получена первая цена, backoff переподключения сброшен" }
    }

    private fun logStrategyAnalysis(
        strategy: TradingStrategy,
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal,
        selection: StrategySelection
    ) {
        val candleContext = marketData.candlestickPattern
            ?.let { ", свеча=${it.candleKey}, паттерн=${it.pattern}" }
            ?: ", свеча=${marketData.strategyCandleKey}"
        logger.info {
            "Анализ ${strategy.name} (${selection.regime}): инструмент=${marketData.instrumentName}$candleContext, " +
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

    private fun synchronizePositionForSell(marketData: MarketData): OpenPosition? {
        val restoredPosition = brokerPortfolioSyncService.synchronizeLongPositionForSell(
            accountId = accountId,
            marketData = marketData,
            existingPosition = _openPositions.value[marketData.instrumentId]
        ) ?: return null
        _openPositions.value += (restoredPosition.instrumentId to restoredPosition)
        return restoredPosition
    }

    /** Передаёт одобренный сигнал в сервис риска и исполнения, затем обновляет портфель. */
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
            strategyId = signal.strategyId,
            strategyName = signal.strategyName,
            strategyExplanation = signal.strategyExplanation.withAiExplanation(signal.aiResult),
            marketRegime = signal.marketRegime,
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
        sourceCandleKey: String? = null,
        strategyId: String? = null
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
            registerReentryCooldown(result.position.instrumentId, sourceCandleKey, strategyId)
            refreshPortfolioAfterTrade(currentAccountId)
            eventPublisherService.publishPositionsChanged()
        }
    }

    private fun registerReentryCooldown(
        instrumentId: String,
        sourceCandleKey: String? = null,
        strategyId: String? = null
    ) {
        val candleDurationSeconds = if (strategyId == "candlestick") {
            candlestickPatternStrategy.currentTimeframe.minutes * 60L
        } else {
            MARKET_DATA_TIMEFRAME_SECONDS
        }
        val cooldownSeconds = candleDurationSeconds * reentryCooldownCandles.coerceAtLeast(1)
        val cooldownEndsAt = Instant.now().plusSeconds(cooldownSeconds)
        reentryCooldownUntil[instrumentId] = cooldownEndsAt
        pendingLossExitConfirmations.remove(instrumentId)
        if (sourceCandleKey != null && strategyId != null) {
            processedStrategyCandles["$instrumentId:$strategyId"] = sourceCandleKey
        }
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
        const val MARKET_DATA_TIMEFRAME_SECONDS = 5L * 60
        val STREAM_RECONNECT_DELAYS_MS = longArrayOf(5_000, 10_000, 30_000, 60_000, 120_000)
    }
}
