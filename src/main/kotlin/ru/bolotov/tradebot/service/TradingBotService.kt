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
    private val aiTradeSignalFilter: AiTradeSignalFilter,
    private val positionLifecycleService: PositionLifecycleService,
    private val portfolioSnapshotService: PortfolioSnapshotService,
    private val tradeEventService: TradeEventService,
    private val positionSizingConfig: PositionSizingConfig,
    @Value("\${trading.loop.delay-ms:7200000}") private val loopDelayMs: Long,
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

    private val emergencyCloseChunkSize = 2
    private val emergencyCloseChunkDelayMs = 1500L
    private val streamReconnectDelayMs = 5000L
    private val processedStrategyCandles = ConcurrentHashMap<String, String>()
    private val pendingLossExitConfirmations = ConcurrentHashMap<String, String>()
    private val reentryCooldownUntil = ConcurrentHashMap<String, Instant>()
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

    private fun OpenPosition.calculateUnrealizedPnl(currentPrice: BigDecimal): BigDecimal =
        currentPrice
            .subtract(entryPrice)
            .multiply(BigDecimal.valueOf(quantity))
            .multiply(BigDecimal.valueOf(lotSize.toLong()))

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
                    closedAt = (event.processedAt ?: event.createdAt).toString(),
                    aiExplanation = event.explanation.aiExplanation() ?: openEvent?.explanation?.aiExplanation()
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
            val patternResult = if (strategyManager.isCandlestickStrategyActive() && marketData != null) {
                candlestickPatternStrategy.analyzePatternWithCandles(
                    instrumentUid = lastPrice.instrumentUid,
                    confirmationPrice = marketData.currentPrice
                )
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
            emit(BotSignal.Close(position, CloseReason.RISK_LIMIT))
            return@flow
        }

        if (!shouldProcessStrategyCandle(marketData)) return@flow

        val strategy = strategyManager.getCurrentStrategy()
        val signal = strategy.analyze(marketData)
        logger.info {
            "Анализ: инструмент=${marketData.instrumentName}, паттерн=${marketData.candlestickPattern?.direction}, " +
                    "сигнал=${signal.direction}, уверенность=${signal.confidence}"
        }

        if (signal.direction == OrderDirection.SELL) {
            val positionToClose = synchronizePositionForSell(marketData) ?: return@flow
            if (!canCloseByStrategy(positionToClose, marketData, signal)) return@flow

            val aiResult = aiTradeSignalFilter.evaluate(marketData, signal, strategy, positionToClose)
            if (!aiResult.approved) {
                logger.info {
                    "Продажа отклонена AI-фильтром: ${marketData.instrumentName} -> ${signal.direction}"
                }
                return@flow
            }
            pendingLossExitConfirmations.remove(positionToClose.instrumentId)
            emit(
                BotSignal.Close(
                    position = positionToClose,
                    reason = CloseReason.STRATEGY_SIGNAL,
                    aiResult = aiResult,
                    sourceCandleKey = marketData.candlestickPattern?.candleKey
                )
            )
            return@flow
        }

        position?.let { pendingLossExitConfirmations.remove(it.instrumentId) }

        if (signal.direction == OrderDirection.HOLD || signal.confidence <= 0.5) {
            logger.debug {
                "Сигнал отклонён: ${marketData.instrumentName}, причина=${if (signal.direction == OrderDirection.HOLD) "HOLD" else "низкая уверенность=${signal.confidence}"}"
            }
            return@flow
        }

        if (canExecuteBuySignal(marketData)) {
            val currentPosition = _openPositions.value[marketData.instrumentId]
            if (currentPosition != null) return@flow

            val aiResult = aiTradeSignalFilter.evaluate(marketData, signal, strategy, currentPosition)
            if (!aiResult.approved) {
                logger.info {
                    "Сигнал отклонён AI-фильтром: ${marketData.instrumentName} -> ${signal.direction}"
                }
                return@flow
            }

            logger.info { "Сигнал принят: ${marketData.instrumentName} -> ${signal.direction}" }
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
    }

    private fun canExecuteBuySignal(marketData: MarketData): Boolean {
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
        val candleKey = marketData.candlestickPattern?.candleKey ?: return true
        return processedStrategyCandles.put(marketData.instrumentId, candleKey) != candleKey
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
                "Продажа ${marketData.instrumentName} пропущена: уверенность ${signal.confidence} " +
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
        val grossPnl = (currentPrice - position.entryPrice) *
            position.quantity.toBigDecimal() * position.lotSize.toBigDecimal()
        val estimatedTotalCommission = position.entryCommission * BigDecimal(2)
        return grossPnl > estimatedTotalCommission
    }

    private fun confirmLossExitOnNextCandle(
        position: OpenPosition,
        marketData: MarketData
    ): Boolean {
        val candleKey = marketData.candlestickPattern?.candleKey ?: return false
        val previousCandleKey = pendingLossExitConfirmations.put(position.instrumentId, candleKey)
        if (previousCandleKey == null) {
            logger.info {
                "Продажа ${marketData.instrumentName} в убытке ожидает подтверждения на следующей закрытой свече"
            }
            return false
        }

        logger.info {
            "Продажа ${marketData.instrumentName} в убытке подтверждена двумя закрытыми свечами"
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

    private fun checkStopLossOrTakeProfit(position: OpenPosition, currentPrice: BigDecimal): Boolean {
        val pnlPercent = if (position.direction == DomainOrderDirection.BUY) {
            (currentPrice - position.entryPrice) / position.entryPrice
        } else {
            (position.entryPrice - currentPrice) / position.entryPrice
        }.toDouble()

        return when {
            pnlPercent <= -positionSizingConfig.stopLossPercent -> {
                logger.warn { "Стоп-лосс для ${position.instrumentName}: ${"%.2f".format(pnlPercent * 100)}%" }
                true
            }

            pnlPercent >= positionSizingConfig.takeProfitPercent -> {
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
    RISK_LIMIT("RISK_CLOSE"),
    STRATEGY_SIGNAL("SIGNAL_CLOSE")
}
