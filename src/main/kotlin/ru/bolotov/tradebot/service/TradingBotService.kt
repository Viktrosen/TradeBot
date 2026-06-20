package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.config.InstrumentFilterProperties
import ru.bolotov.tradebot.domain.model.EventStatus
import ru.bolotov.tradebot.domain.model.EventType
import ru.bolotov.tradebot.domain.model.PortfolioSnapshot
import ru.bolotov.tradebot.domain.model.TradeEvent
import ru.bolotov.tradebot.domain.repository.PortfolioSnapshotRepository
import ru.bolotov.tradebot.domain.repository.TradeEventRepository
import ru.bolotov.tradebot.strategy.CandlestickPatternStrategy
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.MarketDataProvider
import ru.bolotov.tradebot.strategy.OrderDirection
import ru.bolotov.tradebot.strategy.StrategyManager
import ru.bolotov.tradebot.strategy.TradingStrategy
import ru.tinkoff.piapi.contract.v1.LastPrice
import ru.tinkoff.piapi.contract.v1.MarketDataResponse
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.core.InstrumentsService
import ru.tinkoff.piapi.core.InvestApi
import ru.tinkoff.piapi.core.OperationsService
import ru.tinkoff.piapi.core.SandboxService
import ru.tinkoff.piapi.core.UsersService
import ru.tinkoff.piapi.core.stream.StreamProcessor
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import ru.bolotov.tradebot.domain.model.OrderDirection as DomainOrderDirection

private val logger = KotlinLogging.logger {}

data class OpenPosition(
    val positionId: String = java.util.UUID.randomUUID().toString(),
    val instrumentId: String,
    val instrumentName: String,
    val direction: DomainOrderDirection,
    val entryPrice: BigDecimal,
    val quantity: Long,
    val lotSize: Int = 1,
    val entryCommission: BigDecimal = BigDecimal.ZERO,
    val entryTime: Instant,
    val stopLossPrice: BigDecimal? = null,  // 🆕
    val atr: BigDecimal? = null             // 🆕
)

@Service
class TradingBotService(
    private val marketDataProvider: MarketDataProvider,
    private val investApi: InvestApi,
    private val strategyManager: StrategyManager,
    private val orderExecutionService: OrderExecutionService,
    private val tradeEventRepository: TradeEventRepository,
    private val portfolioSnapshotRepository: PortfolioSnapshotRepository,
    private val eventPublisherService: EventPublisherService,
    private val candlestickPatternStrategy: CandlestickPatternStrategy,
    private val strategyConfigPersistenceService: StrategyConfigPersistenceService,
    private val positionSizingService: PositionSizingService,
    private val operationsService: OperationsService,
    private val usersService: UsersService,
    private val sandboxService: SandboxService,
    private val instrumentsService: InstrumentsService,
    private val instrumentSelector: InstrumentSelector,
    private val objectMapper: ObjectMapper,
    private val filterProperties: InstrumentFilterProperties,
    @Value("\${trading.loop.delay-ms:7200000}") private val loopDelayMs: Long,
    @Qualifier("sandboxEnabled") private val sandboxEnabled: Boolean
) {
    // Состояние бота
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

    private val lastSignalTime = ConcurrentHashMap<String, Instant>()
    private val closingPositionIds = ConcurrentHashMap.newKeySet<String>()
    private val signalDebounceMs = 5000L
    private var isPortfolioRestored = false
    private var isClosingPositions = false

    private val ignoredBrokerPositionInstrumentTypes = setOf("currency")
    private val ignoredBrokerPositionUids = setOf(
        "a92e2e25-a698-45cc-a781-167cf465257c" // RUB
    )

    private data class RestorableInstrumentInfo(
        val ticker: String,
        val name: String,
        val instrumentType: String
    )

    init {
        runBlocking {
            initializeAccount()
            selectInitialInstruments()
            loadLastStrategyConfiguration()
            restorePositionsFromBroker()
        }
    }

    private suspend fun loadLastStrategyConfiguration() {
        try {
            when (val config = strategyConfigPersistenceService.loadLastConfiguration()) {
                is LoadedConfig.Simple -> {
                    strategyManager.switchToSimpleStrategy(config.name)
                    logger.info { "📂 Загружена сохранённая простая стратегия: ${config.name}" }
                }

                is LoadedConfig.Voting -> {
                    strategyManager.switchToVotingStrategy(config.weights)
                    logger.info { "📂 Загружена сохранённая стратегия голосования: ${config.weights}" }
                }

                is LoadedConfig.Confirmation -> {
                    strategyManager.switchToConfirmationStrategy(config.indicators)
                    logger.info { "📂 Загружена сохранённая стратегия подтверждения: ${config.indicators}" }
                }

                is LoadedConfig.Candlestick -> {
                    // 🆕 Исправлено: передаём таймфрейм и уверенность
                    val timeframe = try {
                        CandlestickPatternStrategy.CandleTimeframe.valueOf(config.timeframe)
                    } catch (e: IllegalArgumentException) {
                        CandlestickPatternStrategy.CandleTimeframe.M5
                    }

                    candlestickPatternStrategy.setTimeframe(timeframe)
                    candlestickPatternStrategy.minConfidence = config.minConfidence

                    strategyManager.switchToCandlestickStrategy()  // ← без параметров

                    logger.info { "📂 Загружена сохранённая свечная стратегия: ${config.timeframe}, уверенность=${config.minConfidence}" }
                }

                null -> {
                    logger.info { "📂 Нет сохранённой конфигурации, используется стратегия по умолчанию (Cross EMA)" }
                    strategyManager.switchToSimpleStrategy("ema")
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Ошибка загрузки конфигурации стратегии, используется по умолчанию" }
            strategyManager.switchToSimpleStrategy("ema")
        }
    }

    private suspend fun initializeAccount() {
        try {
            if (sandboxEnabled) {
                val existingAccounts = sandboxService.getAccountsSync()
                if (existingAccounts.isNotEmpty()) {
                    accountId = existingAccounts.firstOrNull()?.id
                    logger.info { "Используем существующий Sandbox-счёт: $accountId" }
                } else {
                    closeAllSandboxAccounts()
                    val newAccount = sandboxService.openAccountSync()
                    accountId = newAccount
                    logger.info { "Создан новый Sandbox-счёт: $accountId" }
                }

                if (accountId != null) {
                    try {
                        val portfolio = operationsService.getPortfolioSync(accountId!!)
                        val currentBalance = portfolio.totalAmountCurrencies?.value ?: BigDecimal.ZERO

                        if (currentBalance < BigDecimal.valueOf(50_000)) {
                            val neededAmount = BigDecimal.valueOf(50_000)
                            sandboxService.payInSync(
                                accountId!!,
                                MoneyValue.newBuilder()
                                    .setCurrency("rub")
                                    .setUnits(neededAmount.toLong())
                                    .setNano(0)
                                    .build()
                            )
                            logger.info { "Sandbox-счёт пополнен на $neededAmount RUB" }
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Ошибка проверки/пополнения sandbox-счёта" }
                    }
                }
            } else {
                val accounts = usersService.getAccountsSync()
                accountId = accounts.firstOrNull()?.id
                logger.info { "Используется реальный счёт: $accountId" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения/создания accountId" }
        }
    }

    private suspend fun closeAllSandboxAccounts() {
        try {
            val accounts = sandboxService.getAccountsSync()
            if (accounts.isEmpty()) {
                logger.info { "Нет активных sandbox-счетов для закрытия" }
                return
            }

            logger.info { "Найдено ${accounts.size} sandbox-счет(ов). Начинаем закрытие..." }

            for (account in accounts) {
                try {
                    sandboxService.closeAccountSync(account.id)
                    logger.info { "✅ Закрыт sandbox-счёт: ${account.id}" }
                } catch (e: Exception) {
                    logger.warn(e) { "⚠️ Не удалось закрыть счёт ${account.id}: ${e.message}" }
                }
            }

            logger.info { "Завершено закрытие sandbox-счетов" }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при закрытии sandbox-счетов" }
        }
    }

    private suspend fun selectInitialInstruments() {
        val selectedInstruments = instrumentSelector.selectTradableInstruments(
            minDailyVolume = filterProperties.minDailyVolume,
            minVolatility = filterProperties.minVolatility,
            maxVolatility = filterProperties.maxVolatility,
            maxCount = filterProperties.maxCount
        )

        _activeInstruments.value = selectedInstruments.map { it.uid }
        logger.info { "Отобрано ${_activeInstruments.value.size} инструментов для торговли" }
        selectedInstruments.forEach { instrument ->
            logger.info { "  - ${instrument.ticker} (${instrument.instrumentType}): цена=${instrument.price}" }
        }
    }

    suspend fun start() {
        if (_isRunning.value) {
            logger.warn { "Бот уже запущен" }
            return
        }
        if (accountId == null) {
            logger.error { "Не указан accountId. Невозможно запустить бота." }
            return
        }

        _isRunning.value = true
        logger.info { "🚀 Запуск торгового бота в реактивном режиме" }

        // RabbitMQ (закомментировано)
        eventPublisherService.publishBotStatusChanged("RUNNING")

        startPriceStream()
        startScheduler()
    }

    fun stop() {
        _isRunning.value = false
        priceStreamJob?.cancel()
        schedulerJob?.cancel()
        logger.info { "Бот остановлен" }

        // RabbitMQ (закомментировано)
        eventPublisherService.publishBotStatusChanged("STOPPED")
    }

    private fun startPriceStream() {
        priceStreamJob = scope.launch {
            val instruments = _activeInstruments.value
            if (instruments.isEmpty()) {
                logger.warn { "Нет активных инструментов для подписки" }
                return@launch
            }

            logger.info { "📡 Подключение к стриму цен для ${instruments.size} инструментов" }

            subscribeToLastPrices(instruments)
                .mapNotNull { lastPrice ->
                    logger.info { "📩 Получена цена для ${lastPrice.instrumentUid}" }
                    enrichMarketData(lastPrice)
                }
                .flatMapLatest { marketData ->
                    flow {
                        logger.info { "📊 marketData для ${marketData.instrumentName}: candlestickPattern=${marketData.candlestickPattern?.direction}" }
                        val position = _openPositions.value[marketData.instrumentId]

                        if (position != null && checkStopLossOrTakeProfit(position, marketData.currentPrice)) {
                            emit(Signal.Close(position))
                            return@flow
                        }

                        val now = Instant.now()
                        val lastTime = lastSignalTime[marketData.instrumentId]
                        if (lastTime == null || now.toEpochMilli() - lastTime.toEpochMilli() > signalDebounceMs) {
                            val strategy = strategyManager.getCurrentStrategy()
                            val signal = strategy.analyze(marketData)
                            val strategyExplanation = strategy.getExplanation(marketData)
                            logger.info { "🎯 АНАЛИЗ: инструмент=${marketData.instrumentName}, " +
                                    "паттерн=${marketData.candlestickPattern?.direction}, " +
                                    "сигнал=${signal.direction}, уверенность=${signal.confidence}" }
                            if (signal.direction != OrderDirection.HOLD && signal.confidence > 0.5) {
                                logger.info { "✅ СИГНАЛ ПРИНЯТ: ${marketData.instrumentName} → ${signal.direction}" }
                                lastSignalTime[marketData.instrumentId] = now
                                emit(Signal.Trade(marketData, signal, strategy.name, strategyExplanation))
                            } else {
                                logger.debug { "⏸️ СИГНАЛ ОТКЛОНЁН: ${marketData.instrumentName}, причина: ${if (signal.direction == OrderDirection.HOLD) "HOLD" else "низкая уверенность=${signal.confidence}"}" }
                            }
                        }
                    }
                }
                .catch { error -> logger.error(error) { "Ошибка в стриме цен" } }
                .collect { signal ->
                    when (signal) {
                        is Signal.Close -> closePosition(signal.position)
                        is Signal.Trade -> executeTrade(
                            signal.marketData,
                            signal.signal,
                            signal.strategyName,
                            signal.strategyExplanation
                        )
                    }
                }
        }
    }

    private fun subscribeToLastPrices(instrumentUids: List<String>): Flow<LastPrice> = callbackFlow {
        val streamId = "trade_bot_stream_${System.currentTimeMillis()}"
        val streamService = investApi.marketDataStreamService

        val processor = StreamProcessor<MarketDataResponse> { response ->
            if (response.hasLastPrice()) {
                trySend(response.lastPrice)
            }
        }

        val onErrorCallback = Consumer<Throwable> { error ->
            if (error is io.grpc.StatusRuntimeException && error.status.code == io.grpc.Status.Code.CANCELLED) {
                logger.debug { "Стрим $streamId отменён (ожидаемо)" }
            } else {
                logger.error(error) { "Ошибка стрима $streamId" }
            }
            close(error)
        }

        val subscription = streamService.newStream(streamId, processor, onErrorCallback)
        subscription.subscribeLastPrices(instrumentUids)

        logger.info { "Стрим $streamId запущен, подписаны на ${instrumentUids.size} инструментов" }

        awaitClose {
            logger.info { "Закрытие стрима $streamId" }
            try {
                subscription.unsubscribeLastPrices(instrumentUids)
                subscription.cancel()
            } catch (e: Exception) {
                logger.debug(e) { "Ошибка при закрытии стрима $streamId (ожидаемо)" }
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
                "🕯️ Candlestick анализ для ${lastPrice.instrumentUid}: " +
                        "pattern=${patternResult?.pattern}, direction=${patternResult?.direction}, confidence=${patternResult?.confidence}"
            }

            marketData?.copy(candlestickPattern = patternResult)
        } catch (e: Exception) {
            logger.error(e) { "Ошибка обогащения данных для ${lastPrice.instrumentUid}" }
            null
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
                logger.warn { "🛑 Стоп-лосс для ${position.instrumentName}: ${"%.2f".format(pnlPercent * 100)}%" }
                true
            }

            pnlPercent >= takeProfitPercent -> {
                logger.info { "🎯 Тейк-профит для ${position.instrumentName}: ${"%.2f".format(pnlPercent * 100)}%" }
                true
            }

            else -> false
        }
    }

    private suspend fun closePosition(position: OpenPosition) {
        val marketData = marketDataProvider.fetchMarketData(position.instrumentId) ?: return

        val directionStr = if (position.direction == DomainOrderDirection.BUY) "SELL" else "BUY"
        if (!tryMarkPositionClosing(position, directionStr)) return

        try {
        val orderResult = orderExecutionService.placeOrder(
            accountId = accountId!!,
            instrumentId = position.instrumentId,
            quantity = position.quantity,
            price = marketData.currentPrice,
            direction = directionStr
        )

        if (orderResult.success) {
            val fill = waitForCloseFill(position, orderResult) ?: return
            val closePrice = fill.executedPrice ?: marketData.currentPrice
            val closeCommission = fill.executedCommission ?: BigDecimal.ZERO
            val pnl = calculatePnl(position, closePrice, closeCommission)

            logger.info { "✅ Позиция закрыта: ${position.direction} ${position.instrumentName}, P&L: $pnl ₽" }

            _openPositions.value -= position.instrumentId
            saveCloseEventOnce(marketData.copy(currentPrice = closePrice), position, pnl, "CLOSE")
            eventPublisherService.publishPortfolioChanged()
        } else {
            logger.error { "❌ Ошибка закрытия позиции: ${orderResult.error}" }
            closingPositionIds.remove(position.positionId)
        }
        } catch (e: Exception) {
            closingPositionIds.remove(position.positionId)
            throw e
        }
    }

    private suspend fun closePositionBySignal(position: OpenPosition, signal: ru.bolotov.tradebot.strategy.Signal) {
        val marketData = marketDataProvider.fetchMarketData(position.instrumentId) ?: return

        val directionStr = if (position.direction == DomainOrderDirection.BUY) "SELL" else "BUY"
        if (!tryMarkPositionClosing(position, directionStr)) return

        try {
        val orderResult = orderExecutionService.placeOrder(
            accountId = accountId!!,
            instrumentId = position.instrumentId,
            quantity = position.quantity,
            price = marketData.currentPrice,
            direction = directionStr
        )

        if (orderResult.success) {
            val fill = waitForCloseFill(position, orderResult) ?: return
            val closePrice = fill.executedPrice ?: marketData.currentPrice
            val closeCommission = fill.executedCommission ?: BigDecimal.ZERO
            val pnl = calculatePnl(position, closePrice, closeCommission)

            logger.info { "✅ Позиция закрыта по сигналу: ${position.direction} ${position.instrumentName}, P&L: $pnl ₽" }

            _openPositions.value -= position.instrumentId
            saveCloseEventOnce(marketData.copy(currentPrice = closePrice), position, pnl, "SIGNAL_CLOSE")

            // RabbitMQ (закомментировано)
            eventPublisherService.publishPortfolioChanged()
        } else {
            logger.error { "❌ Ошибка закрытия позиции: ${orderResult.error}" }
            closingPositionIds.remove(position.positionId)
        }
        } catch (e: Exception) {
            closingPositionIds.remove(position.positionId)
            throw e
        }
    }

    private fun calculatePnl(
        position: OpenPosition,
        closePrice: BigDecimal,
        closeCommission: BigDecimal = BigDecimal.ZERO
    ): BigDecimal {
        val grossPnl = if (position.direction == DomainOrderDirection.BUY) {
            (closePrice - position.entryPrice) *
                    BigDecimal.valueOf(position.quantity) *
                    BigDecimal.valueOf(position.lotSize.toLong())
        } else {
            (position.entryPrice - closePrice) *
                    BigDecimal.valueOf(position.quantity) *
                    BigDecimal.valueOf(position.lotSize.toLong())
        }

        return grossPnl - position.entryCommission - closeCommission
    }

    private fun tryMarkPositionClosing(position: OpenPosition, closeDirection: String): Boolean {
        if (tradeEventRepository.existsByPositionIdAndEventType(position.positionId, EventType.CLOSE)) {
            logger.warn { "⏸️ Позиция уже имеет CLOSE-событие, пропускаем повторное закрытие: ${position.positionId}" }
            _openPositions.value -= position.instrumentId
            return false
        }

        val activeCloseOrder = orderExecutionService.findActiveOrder(
            accountId = accountId!!,
            instrumentId = position.instrumentId,
            direction = closeDirection
        )
        if (activeCloseOrder != null) {
            logger.warn {
                "⏸️ У брокера уже есть активная заявка на закрытие ${position.instrumentName}: " +
                        "${activeCloseOrder.orderId}, status=${activeCloseOrder.executionStatus}"
            }
            closingPositionIds.add(position.positionId)
            return false
        }

        if (!closingPositionIds.add(position.positionId)) {
            logger.warn { "⏸️ Позиция уже закрывается, пропускаем дубль: ${position.positionId}" }
            return false
        }

        return true
    }

    private suspend fun waitForCloseFill(
        position: OpenPosition,
        orderResult: OrderResult
    ): OrderFillResult? {
        val orderId = orderResult.orderId
        if (orderId.isNullOrBlank()) {
            logger.warn { "⚠️ Нет orderId для закрытия ${position.instrumentName}; CLOSE не сохраняем" }
            closingPositionIds.remove(position.positionId)
            return null
        }

        val fill = orderExecutionService.waitForOrderFill(accountId!!, orderId)
        if (!fill.filled) {
            logger.warn {
                "⏳ Заявка на закрытие ${position.instrumentName} пока не исполнена: " +
                        "orderId=$orderId, status=${fill.executionStatus}. CLOSE не сохраняем."
            }
            if (fill.executionStatus != "TIMEOUT_WAITING_FILL") {
                closingPositionIds.remove(position.positionId)
            }
            return null
        }

        closingPositionIds.remove(position.positionId)
        return fill
    }

    private suspend fun waitForOpenFill(
        marketData: MarketData,
        orderResult: OrderResult
    ): OrderFillResult? {
        val orderId = orderResult.orderId
        if (orderId.isNullOrBlank()) {
            logger.warn { "No orderId for opening ${marketData.instrumentName}; keeping OPEN event as FAILED" }
            return null
        }

        val fill = orderExecutionService.waitForOrderFill(accountId!!, orderId)
        if (!fill.filled) {
            logger.warn {
                "Open order for ${marketData.instrumentName} is not filled: " +
                        "orderId=$orderId, status=${fill.executionStatus}. Keeping OPEN event as FAILED."
            }
            return null
        }

        return fill
    }

    private fun saveCloseEventOnce(data: MarketData, position: OpenPosition, pnl: BigDecimal, reason: String) {
        if (tradeEventRepository.existsByPositionIdAndEventType(position.positionId, EventType.CLOSE)) {
            logger.warn { "⏸️ CLOSE-событие уже существует, не пишем дубль: ${position.positionId}" }
            return
        }

        saveCloseEvent(data, position, pnl, reason)
    }

    private fun saveCloseEventOnce(position: OpenPosition, closePrice: BigDecimal, pnl: BigDecimal, reason: String) {
        if (tradeEventRepository.existsByPositionIdAndEventType(position.positionId, EventType.CLOSE)) {
            logger.warn { "⏸️ CLOSE-событие уже существует, не пишем дубль: ${position.positionId}" }
            return
        }

        saveCloseEvent(position, closePrice, pnl, reason)
    }

    private fun startScheduler() {
        schedulerJob = scope.launch {
            while (_isRunning.value) {
                try {
                    takePortfolioSnapshot()
                    delay(loopDelayMs)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        logger.debug { "Scheduler stopped" }
                        return@launch
                    }
                    logger.error(e) { "Ошибка в планировщике" }
                    delay(10000)
                }
            }
        }
    }

    fun updateInstruments(instruments: List<String>) {
        _activeInstruments.value = instruments
        logger.info { "Обновлён список инструментов: $instruments" }

        if (_isRunning.value) {
            priceStreamJob?.cancel()
            startPriceStream()
        }
    }

    fun switchToSimpleStrategy(strategyName: String) {
        strategyManager.switchToSimpleStrategy(strategyName)
        strategyConfigPersistenceService.saveSimpleStrategy(strategyName)

        if (_isRunning.value) {
            priceStreamJob?.cancel()
            startPriceStream()
        }

        logger.info { "🔄 Стратегия переключена на: $strategyName, стрим перезапущен" }
    }

    fun switchToVotingStrategy(weights: Map<String, Int>) {
        strategyManager.switchToVotingStrategy(weights)
        strategyConfigPersistenceService.saveVotingStrategy(weights)

        if (_isRunning.value) {
            priceStreamJob?.cancel()
            startPriceStream()
        }

        logger.info { "🔄 Стратегия переключена на голосование: $weights, стрим перезапущен" }
    }

    fun switchToConfirmationStrategy(requiredIndicators: List<String>) {
        strategyManager.switchToConfirmationStrategy(requiredIndicators)
        strategyConfigPersistenceService.saveConfirmationStrategy(requiredIndicators)

        if (_isRunning.value) {
            priceStreamJob?.cancel()
            startPriceStream()
        }

        logger.info { "🔄 Стратегия переключена на подтверждение: $requiredIndicators, стрим перезапущен" }
    }

    private suspend fun executeTrade(
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal,
        strategyName: String,
        strategyExplanation: String
    ) {
        logger.info { "🚀 EXECUTE TRADE: ${marketData.instrumentName}, сигнал=${signal.direction}, уверенность=${signal.confidence}" }
        val currentPosition = _openPositions.value[marketData.instrumentId]
        val signalDirection = when (signal.direction) {
            OrderDirection.BUY -> DomainOrderDirection.BUY
            OrderDirection.SELL -> DomainOrderDirection.SELL
            OrderDirection.HOLD -> return
        }

        when {
            // Нет позиции — открываем новую, но ТОЛЬКО BUY!
            currentPosition == null -> {
                // 🆕 Игнорируем сигналы SELL (шорт)
                if (signalDirection == DomainOrderDirection.SELL) {
                    logger.info { "⏸️ Игнорируем сигнал SELL для ${marketData.instrumentName} (короткие позиции отключены)" }
                    return
                }

                val availableCapital = getAvailableCapital()
                if (!positionSizingService.canOpenNewPosition(availableCapital, _openPositions.value)) {
                    logger.warn { "❌ Нельзя открыть новую позицию (лимит капитала или количества)" }
                    return
                }

                val calculatedPositionSize = positionSizingService.calculatePositionSize(
                    marketData = marketData,
                    availableCapital = availableCapital,
                    currentPositions = _openPositions.value
                )

                val brokerLimits = orderExecutionService.getBrokerLotLimits(
                    accountId = accountId!!,
                    instrumentId = marketData.instrumentId,
                    price = marketData.currentPrice
                ) ?: run {
                    logger.warn { "Broker limits are unavailable for ${marketData.instrumentName}; skip opening" }
                    return
                }

                val positionSize = positionSizingService.applyBrokerLimits(
                    positionSize = calculatedPositionSize,
                    marketData = marketData,
                    availableCapital = availableCapital,
                    brokerLimits = brokerLimits
                )

                if (positionSize.quantity <= 0) {
                    logger.warn { "Broker/user risk limits do not allow opening ${marketData.instrumentName}" }
                    return
                }

                val estimatedOrderAmount = orderExecutionService.estimateOrderAmount(
                    accountId = accountId!!,
                    instrumentId = marketData.instrumentId,
                    quantity = positionSize.quantity,
                    price = marketData.currentPrice,
                    direction = "BUY"
                ) ?: positionSize.value

                if (!hasEnoughFunds(estimatedOrderAmount)) {
                    logger.warn { "❌ Недостаточно средств: нужно $estimatedOrderAmount, доступно $availableCapital" }
                    return
                }

                openNewPosition(marketData, signal, positionSize, strategyName, strategyExplanation)
            }

            // Сигнал ПРОТИВОПОЛОЖНЫЙ позиции — закрываем
            currentPosition.direction != signalDirection -> {
                logger.info { "🔄 Закрытие позиции по сигналу: ${currentPosition.direction} → ${signalDirection}" }
                closePositionBySignal(currentPosition, signal)
            }

            // Сигнал совпадает с позицией — удерживаем
            else -> {
                logger.debug { "⏳ Сигнал ${signal.direction} совпадает с позицией — удерживаем" }
            }
        }
    }

    private suspend fun getAvailableCapital(): BigDecimal {
        return try {
            val portfolio = operationsService.getPortfolioSync(accountId!!)
            portfolio.totalAmountCurrencies?.value ?: BigDecimal.ZERO
        } catch (e: Exception) {
            logger.error(e) { "Ошибка получения баланса" }
            BigDecimal.ZERO
        }
    }


    private suspend fun openNewPosition(
        marketData: MarketData,
        signal: ru.bolotov.tradebot.strategy.Signal,
        positionSize: PositionSize,
        strategyName: String,
        strategyExplanation: String
    ) {
        val direction = if (signal.direction == OrderDirection.BUY) {
            DomainOrderDirection.BUY
        } else {
            DomainOrderDirection.SELL
        }

        val positionId = UUID.randomUUID().toString()

        val tradeEvent = TradeEvent(
            instrumentId = marketData.instrumentId,
            instrumentName = marketData.instrumentName,
            direction = direction,
            price = marketData.currentPrice,
            pnl = null,
            eventType = EventType.OPEN,
            positionId = positionId,
            quantity = positionSize.quantity,
            totalValue = positionSize.value,
            reason = buildOpenTradeReason(strategyName, signal),
            explanation = strategyExplanation,
            status = EventStatus.PENDING
        )

        val savedEvent = tradeEventRepository.save(tradeEvent)

        val orderResult = orderExecutionService.placeOrder(
            accountId = accountId!!,
            instrumentId = marketData.instrumentId,
            quantity = positionSize.quantity,
            price = marketData.currentPrice,
            direction = if (signal.direction == OrderDirection.BUY) "BUY" else "SELL"
        )

        if (orderResult.success) {
            val fill = waitForOpenFill(marketData, orderResult)
            if (fill == null) {
                savedEvent.status = EventStatus.FAILED
                tradeEventRepository.save(savedEvent)
                return
            }

            val entryPrice = fill.executedPrice ?: orderResult.executedPrice ?: marketData.currentPrice
            val entryCommission = fill.executedCommission ?: orderResult.executedCommission ?: BigDecimal.ZERO

            savedEvent.price = entryPrice
            savedEvent.totalValue = entryPrice *
                    BigDecimal.valueOf(positionSize.quantity) *
                    BigDecimal.valueOf(marketData.lotSize.toLong())
            savedEvent.status = EventStatus.PROCESSED
            savedEvent.processedAt = Instant.now()
            tradeEventRepository.save(savedEvent)

            val newPosition = OpenPosition(
                positionId = positionId,
                instrumentId = marketData.instrumentId,
                instrumentName = marketData.instrumentName,
                direction = direction,
                entryPrice = entryPrice,
                quantity = positionSize.quantity,
                lotSize = marketData.lotSize,
                entryCommission = entryCommission,
                entryTime = Instant.now(),
                stopLossPrice = positionSize.stopLossPrice,
                atr = positionSize.atr
            )

            _openPositions.value += (marketData.instrumentId to newPosition)

            logger.info {
                "📈 Открыта позиция: $direction ${marketData.instrumentName} (${positionSize.quantity} лотов, ${
                    "%.0f".format(
                        positionSize.value
                    )
                } ₽, ${"%.1f".format(positionSize.capitalUsagePercent)}% депозита)"
            }

            // RabbitMQ (закомментировано)
            eventPublisherService.publishTradeExecuted(savedEvent)
            eventPublisherService.publishPortfolioChanged()
        } else {
            savedEvent.status = EventStatus.FAILED
            tradeEventRepository.save(savedEvent)
            logger.error { "Ошибка открытия позиции: ${orderResult.error}" }
        }
    }

    private fun buildOpenTradeReason(
        strategyName: String,
        signal: ru.bolotov.tradebot.strategy.Signal
    ): String {
        return if (strategyName == candlestickPatternStrategy.name && !signal.reason.isNullOrBlank()) {
            "$strategyName: ${signal.reason}"
        } else {
            strategyName
        }
    }

    private fun saveCloseEvent(data: MarketData, position: OpenPosition, pnl: BigDecimal, reason: String) {
        val closeEvent = TradeEvent(
            instrumentId = data.instrumentId,
            instrumentName = data.instrumentName,
            direction = position.direction,
            price = data.currentPrice,
            quantity = position.quantity,
            totalValue = data.currentPrice *
                    BigDecimal.valueOf(position.quantity) *
                    BigDecimal.valueOf(position.lotSize.toLong()),
            reason = reason,
            pnl = pnl,
            eventType = EventType.CLOSE,
            positionId = position.positionId,
            explanation = "Закрытие позиции, P&L: $pnl ₽",
            status = EventStatus.PROCESSED,
            processedAt = Instant.now()
        )
        tradeEventRepository.save(closeEvent)

        // RabbitMQ (закомментировано)
        eventPublisherService.publishPortfolioChanged()
    }

    private fun saveCloseEvent(position: OpenPosition, closePrice: BigDecimal, pnl: BigDecimal, reason: String) {
        val closeEvent = TradeEvent(
            instrumentId = position.instrumentId,
            instrumentName = position.instrumentName,
            direction = position.direction,
            price = closePrice,
            quantity = position.quantity,
            totalValue = closePrice *
                    BigDecimal.valueOf(position.quantity) *
                    BigDecimal.valueOf(position.lotSize.toLong()),
            reason = reason,
            pnl = pnl,
            eventType = EventType.CLOSE,
            positionId = position.positionId,
            explanation = "Экстренное закрытие позиции рыночной заявкой, P&L: $pnl ₽",
            status = EventStatus.PROCESSED,
            processedAt = Instant.now()
        )
        tradeEventRepository.save(closeEvent)

        eventPublisherService.publishPortfolioChanged()
    }

    private suspend fun hasEnoughFunds(requiredAmount: BigDecimal): Boolean {
        return try {
            val portfolio = operationsService.getPortfolioSync(accountId!!)
            val cash = portfolio.totalAmountCurrencies?.value ?: BigDecimal.ZERO
            cash >= requiredAmount
        } catch (e: Exception) {
            logger.error(e) { "Ошибка проверки баланса" }
            false
        }
    }

    private suspend fun takePortfolioSnapshot() {
        try {
            if (accountId != null) {
                val portfolio = operationsService.getPortfolioSync(accountId!!)
                val positions = operationsService.getPositionsSync(accountId!!)
                val total = portfolio.totalAmountPortfolio?.value ?: BigDecimal.ZERO
                val moneyRub = positions.money.firstOrNull { it.currency.equals("rub", ignoreCase = true) }
                val blockedRub = positions.blocked.firstOrNull { it.currency.equals("rub", ignoreCase = true) }
                val availableCash = moneyRub?.value ?: BigDecimal.ZERO
                val blockedCash = blockedRub?.value ?: BigDecimal.ZERO
                val cash = if (moneyRub != null || blockedRub != null) {
                    availableCash + blockedCash
                } else {
                    portfolio.totalAmountCurrencies?.value ?: BigDecimal.ZERO
                }
                val positionsJson = objectMapper.writeValueAsString(
                    portfolio.positions.map { pos ->
                        val currentPrice = pos.currentPrice?.value ?: BigDecimal.ZERO
                        mapOf(
                            "instrumentId" to pos.instrumentUid,
                            "figi" to pos.figi,
                            "instrumentType" to pos.instrumentType,
                            "quantity" to pos.quantity,
                            "averagePrice" to (pos.averagePositionPrice?.value ?: BigDecimal.ZERO),
                            "currentPrice" to currentPrice,
                            "positionValue" to (currentPrice * pos.quantity),
                            "expectedYield" to pos.expectedYield,
                            "blocked" to pos.isBlocked,
                            "blockedLots" to pos.blockedLots
                        )
                    }
                )
                val snapshot = PortfolioSnapshot(
                    totalValue = total,
                    cashBalance = cash,
                    blockedCash = blockedCash,
                    availableCash = availableCash,
                    currency = "rub",
                    positions = positionsJson
                )
                portfolioSnapshotRepository.save(snapshot)
            }

            // RabbitMQ (закомментировано)
            eventPublisherService.publishPortfolioChanged()
        } catch (e: Exception) {
            logger.error(e) { "Ошибка сохранения снимка портфеля" }
        }
    }

    fun getOpenPositions(): List<Map<String, Any>> {
        return _openPositions.value.values.map { position ->
            mapOf(
                "positionId" to position.positionId,
                "instrumentId" to position.instrumentId,
                "instrumentName" to position.instrumentName,
                "direction" to position.direction.name,
                "entryPrice" to position.entryPrice.toPlainString(),
                "quantity" to position.quantity,
                "lotSize" to position.lotSize,
                "entryCommission" to position.entryCommission.toPlainString(),
                "entryTime" to position.entryTime.toString()
            )
        }
    }

    suspend fun closeAllPositionsAsync() {
        if (isClosingPositions) {
            logger.warn { "Закрытие позиций уже запущено" }
            return
        }

        isClosingPositions = true

        try {
            val positions = _openPositions.value.values.toList()  // ← .values.toList()
            logger.info { "🚀 Начинаем закрытие ${positions.size} позиций" }

            // У OrdersService нет batch-заявок, поэтому ограничиваем параллелизм и убираем лишний fetchMarketData.
            positions.chunked(emergencyCloseChunkSize).forEach { chunk ->
                coroutineScope {
                    val deferreds = chunk.map { position ->  // ← теперь position: OpenPosition
                        async(Dispatchers.IO) {
                            closePositionWithRetry(position)
                        }
                    }
                    deferreds.awaitAll()
                }
                delay(emergencyCloseChunkDelayMs)
            }

            logger.info { "✅ Закрытие всех позиций завершено" }
        } finally {
            isClosingPositions = false
        }
    }

    fun switchToCandlestickStrategy(timeframe: CandlestickPatternStrategy.CandleTimeframe, minConfidence: Double) {
        candlestickPatternStrategy.setTimeframe(timeframe)
        candlestickPatternStrategy.minConfidence = minConfidence

        strategyManager.switchToCandlestickStrategy()  // ← без параметров

        strategyConfigPersistenceService.saveCandlestickStrategy(
            timeframe = timeframe.name,
            minConfidence = minConfidence
        )

        if (_isRunning.value) {
            priceStreamJob?.cancel()
            startPriceStream()
        }

        logger.info { "🕯️ Стратегия переключена на свечные паттерны: таймфрейм=${timeframe.name}, уверенность=${minConfidence}" }
    }

    private suspend fun closePositionWithRetry(position: OpenPosition, maxRetries: Int = 3): Boolean {
        val closeDirection = if (position.direction == DomainOrderDirection.BUY) "SELL" else "BUY"
        if (!tryMarkPositionClosing(position, closeDirection)) return false

        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                logger.info {
                    "💰 Экстренное закрытие ${position.instrumentName}: " +
                            "$closeDirection ${position.quantity} лотов рыночной заявкой"
                }

                val orderResult = orderExecutionService.placeMarketOrder(
                    accountId = accountId!!,
                    instrumentId = position.instrumentId,
                    quantity = position.quantity,
                    direction = closeDirection
                )

                if (orderResult.success) {
                    val fill = waitForCloseFill(position, orderResult) ?: return false
                    val closePrice = fill.executedPrice ?: orderResult.executedPrice ?: position.entryPrice
                    val closeCommission = fill.executedCommission ?: orderResult.executedCommission ?: BigDecimal.ZERO
                    val pnl = calculatePnl(position, closePrice, closeCommission)
                    logger.info { "✅ Закрыта позиция: ${position.instrumentName}, P&L: $pnl ₽" }

                    _openPositions.value = _openPositions.value - position.instrumentId
                    saveCloseEventOnce(position, closePrice, pnl, "EMERGENCY_CLOSE")
                    return true
                } else {
                    lastError = Exception(orderResult.error)
                    if (attempt < maxRetries) {
                        delay(2000L * attempt)
                    }
                }
            } catch (e: Exception) {
                lastError = e
                logger.warn { "Ошибка при закрытии ${position.instrumentName} (попытка $attempt): ${e.message}" }
                if (attempt < maxRetries) {
                    delay(2000L * attempt)
                }
            }
        }

        logger.error { "❌ Не удалось закрыть позицию ${position.instrumentName} после $maxRetries попыток: ${lastError?.message}" }
        closingPositionIds.remove(position.positionId)
        return false
    }

    private suspend fun restorePositionsFromBroker() {
        if (accountId == null) {
            logger.warn { "⚠️ Нет accountId, пропускаем восстановление позиций" }
            return
        }

        if (isPortfolioRestored) {
            logger.debug { "Портфель уже был восстановлен, пропускаем" }
            return
        }

        try {
            logger.info { "🔄 Синхронизация портфеля с брокером..." }

            // Получаем портфель через OperationsService
            val portfolio = operationsService.getPortfolioSync(accountId!!)

            // portfolio.positions имеет тип List<ru.tinkoff.piapi.core.models.Position>
            val brokerPositions = portfolio.positions

            if (brokerPositions.isEmpty()) {
                logger.info { "✅ Нет открытых позиций на брокерском счёте" }
                _openPositions.value = emptyMap()
                isPortfolioRestored = true
                return
            }

            val restoredPositions = mutableMapOf<String, OpenPosition>()
            var skippedNonTradablePositions = 0

            for (pos in brokerPositions) {
                try {
                    val instrumentUid = pos.instrumentUid

                    val instrumentInfo = getRestorableInstrumentInfo(instrumentUid)
                    if (instrumentInfo == null) {
                        skippedNonTradablePositions++
                        continue
                    }

                    // 🔧 Money → BigDecimal (у Money есть методы getValue() и getCurrency())
                    val avgPrice = moneyToBigDecimal(pos.averagePositionPrice)
                    if (avgPrice <= BigDecimal.ZERO) {
                        logger.warn { "⚠️ Пропускаем позицию с нулевой ценой: ${pos.instrumentUid}" }
                        continue
                    }

                    // 🔧 quantity это BigDecimal
                    val currentQuantity = pos.quantity
                    val isLong = currentQuantity > BigDecimal.ZERO
                    val direction = if (isLong) DomainOrderDirection.BUY else DomainOrderDirection.SELL
                    val absQuantity = currentQuantity.abs().toLong()

                    // Получаем текущий ATR (опционально)
                    val marketData = try {
                        marketDataProvider.fetchMarketData(instrumentUid)
                    } catch (e: Exception) {
                        logger.warn(e) { "Не удалось получить ATR для $instrumentUid" }
                        null
                    }

                    val atr = marketData?.atr
                    val stopLossPrice = if (direction == DomainOrderDirection.BUY && atr != null) {
                        avgPrice - atr * BigDecimal("1.5")
                    } else if (direction == DomainOrderDirection.SELL && atr != null) {
                        avgPrice + atr * BigDecimal("1.5")
                    } else {
                        null
                    }

                    // У Money нет timestamp, используем текущее время как fallback
                    val entryTime = Instant.now().minusSeconds(3600)

                    // Ищем существующий TradeEvent с OPEN по этой позиции
                    val existingOpenEvent =
                        tradeEventRepository.findFirstByInstrumentIdAndDirectionAndEventTypeOrderByCreatedAtDesc(
                            instrumentUid,
                            direction,
                            EventType.OPEN
                    )

                    val positionId = existingOpenEvent?.positionId ?: UUID.randomUUID().toString()
                    if (tradeEventRepository.existsByPositionIdAndEventType(positionId, EventType.CLOSE)) {
                        logger.info {
                            "⏸️ Не восстанавливаем позицию ${instrumentInfo.name}: " +
                                    "по positionId=$positionId уже есть CLOSE-событие"
                        }
                        continue
                    }

                    val restoredPosition = OpenPosition(
                        positionId = positionId,
                        instrumentId = instrumentUid,
                        instrumentName = instrumentInfo.name,
                        direction = direction,
                        entryPrice = avgPrice,
                        quantity = absQuantity,
                        lotSize = marketData?.lotSize ?: 1,
                        entryCommission = BigDecimal.ZERO,
                        entryTime = entryTime,
                        stopLossPrice = stopLossPrice,
                        atr = atr
                    )

                    restoredPositions[instrumentUid] = restoredPosition
                    logger.info {
                        "📦 Восстановлена позиция: ${restoredPosition.direction} ${restoredPosition.instrumentName} " +
                                "(${restoredPosition.quantity} лотов по ${restoredPosition.entryPrice} ₽)"
                    }

                } catch (e: Exception) {
                    logger.error(e) { "❌ Ошибка восстановления позиции ${pos.instrumentUid}" }
                }
            }

            _openPositions.value = restoredPositions

            // Синхронизируем активные инструменты с позициями
            val instrumentsWithPositions = restoredPositions.keys.toList()
            if (instrumentsWithPositions.isNotEmpty()) {
                val currentInstruments = _activeInstruments.value.toMutableSet()
                currentInstruments.addAll(instrumentsWithPositions)
                _activeInstruments.value = currentInstruments.toList()
                logger.info { "🔄 Добавлены инструменты с позициями в activeInstruments: $instrumentsWithPositions" }
            }

            isPortfolioRestored = true
            logger.info {
                "✅ Синхронизация завершена. Восстановлено ${restoredPositions.size} позиций, " +
                        "пропущено неторговых/нераспознанных: $skippedNonTradablePositions"
            }

        } catch (e: Exception) {
            logger.error(e) { "❌ Критическая ошибка при синхронизации портфеля" }
        }
    }

    private fun getRestorableInstrumentInfo(instrumentUid: String): RestorableInstrumentInfo? {
        if (instrumentUid in ignoredBrokerPositionUids) {
            logger.info { "💱 Пропускаем валютную позицию при восстановлении: $instrumentUid" }
            return null
        }

        return try {
            val instrument = instrumentsService.getInstrumentByUIDSync(instrumentUid).instrument
            val instrumentType = instrument.instrumentType.lowercase()

            if (instrumentType in ignoredBrokerPositionInstrumentTypes) {
                logger.info {
                    "💱 Пропускаем валютную позицию при восстановлении: " +
                            "${instrument.ticker.ifBlank { instrumentUid }} ($instrumentUid)"
                }
                null
            } else {
                RestorableInstrumentInfo(
                    ticker = instrument.ticker,
                    name = instrument.name.ifBlank { instrument.ticker.ifBlank { instrumentUid } },
                    instrumentType = instrumentType
                )
            }
        } catch (instrumentError: Exception) {
            logger.warn(instrumentError) {
                "⚠️ Не удалось распознать инструмент $instrumentUid через getInstrumentByUIDSync, пробуем как акцию"
            }

            try {
                val share = instrumentsService.getShareByUidSync(instrumentUid)
                RestorableInstrumentInfo(
                    ticker = share.ticker,
                    name = share.name.ifBlank { share.ticker.ifBlank { instrumentUid } },
                    instrumentType = "share"
                )
            } catch (shareError: Exception) {
                logger.warn(shareError) {
                    "⚠️ Пропускаем нераспознанную брокерскую позицию $instrumentUid: нет данных об инструменте"
                }
                null
            }
        }
    }

    // 🆕 Конвертация Money → BigDecimal (класс из T-Invest API)
    private fun moneyToBigDecimal(money: ru.tinkoff.piapi.core.models.Money?): BigDecimal {
        if (money == null) return BigDecimal.ZERO
        return money.value  // Money имеет поле value типа BigDecimal
    }

    fun updateInstrumentFilters(
        minDailyVolume: Long,
        minVolatility: Double,
        maxVolatility: Double,
        maxCount: Int
    ) {
        filterProperties.minDailyVolume = minDailyVolume
        filterProperties.minVolatility = minVolatility
        filterProperties.maxVolatility = maxVolatility
        filterProperties.maxCount = maxCount

        scope.launch {
            val selected = instrumentSelector.selectTradableInstruments(
                minDailyVolume = minDailyVolume,
                minVolatility = minVolatility,
                maxVolatility = maxVolatility,
                maxCount = maxCount
            )
            _activeInstruments.value = selected.map { it.uid }

            if (_isRunning.value) {
                priceStreamJob?.cancel()
                startPriceStream()
            }
        }
    }

    fun getActiveInstruments(): List<String> = _activeInstruments.value
    fun getStatus(): Boolean = _isRunning.value
    fun getCurrentStrategy(): TradingStrategy = strategyManager.getCurrentStrategy()
}

// Внутренние сигналы
private sealed class Signal {
    data class Trade(
        val marketData: MarketData,
        val signal: ru.bolotov.tradebot.strategy.Signal,
        val strategyName: String,
        val strategyExplanation: String
    ) : Signal()
    data class Close(val position: OpenPosition) : Signal()
}
