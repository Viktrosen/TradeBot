package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
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

    private val lastSignalTime = ConcurrentHashMap<String, Instant>()
    private val signalDebounceMs = 5000L
    private var isPortfolioRestored = false

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
                    strategyManager.switchToCandlestickStrategy(candlestickPatternStrategy)
                    candlestickPatternStrategy.setTimeframe(
                        CandlestickPatternStrategy.CandleTimeframe.valueOf(config.timeframe)
                    )
                    candlestickPatternStrategy.minConfidence = config.minConfidence
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
                    val newAccount = sandboxService.openAccountSync()
                    accountId = newAccount
                    logger.info { "Создан новый Sandbox-счёт: $accountId" }
                }

                if (accountId != null) {
                    try {
                        val portfolio = operationsService.getPortfolioSync(accountId!!)
                        val currentBalance = portfolio.totalAmountCurrencies?.value ?: BigDecimal.ZERO

                        if (currentBalance < BigDecimal.valueOf(1_000_000)) {
                            val neededAmount = BigDecimal.valueOf(1_000_000) - currentBalance
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
            logger.info { "  - ${instrument.ticker}: цена=${instrument.price}" }
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
                .mapNotNull { lastPrice -> enrichMarketData(lastPrice) }
                .flatMapLatest { marketData ->
                    flow {
                        val position = _openPositions.value[marketData.instrumentId]

                        if (position != null && checkStopLossOrTakeProfit(position, marketData.currentPrice)) {
                            emit(Signal.Close(position))
                            return@flow
                        }

                        val now = Instant.now()
                        val lastTime = lastSignalTime[marketData.instrumentId]
                        if (lastTime == null || now.toEpochMilli() - lastTime.toEpochMilli() > signalDebounceMs) {
                            val signal = strategyManager.analyze(marketData)
                            if (signal.direction != OrderDirection.HOLD && signal.confidence > 0.5) {
                                lastSignalTime[marketData.instrumentId] = now
                                emit(Signal.Trade(marketData, signal))
                            }
                        }
                    }
                }
                .catch { error -> logger.error(error) { "Ошибка в стриме цен" } }
                .collect { signal ->
                    when (signal) {
                        is Signal.Close -> closePosition(signal.position)
                        is Signal.Trade -> executeTrade(signal.marketData, signal.signal)
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

            // Добавляем анализ свечных паттернов
            val patternSignal = candlestickPatternStrategy.analyzeWithCandles(lastPrice.instrumentUid)
            if (patternSignal.direction != OrderDirection.HOLD) {
                logger.info { "🕯️ Свечной паттерн: ${patternSignal.reason}" }
                // Можно использовать как дополнительный сигнал
            }

            marketData
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
        val orderResult = orderExecutionService.placeOrder(
            accountId = accountId!!,
            instrumentId = position.instrumentId,
            quantity = position.quantity,
            price = marketData.currentPrice,
            direction = directionStr
        )

        if (orderResult.success) {
            val pnl = calculatePnl(position, marketData.currentPrice)

            logger.info { "✅ Позиция закрыта: ${position.direction} ${position.instrumentName}, P&L: $pnl ₽" }

            _openPositions.value = _openPositions.value - position.instrumentId
            saveCloseEvent(marketData, position, pnl, "CLOSE")
            eventPublisherService.publishPortfolioChanged()
        } else {
            logger.error { "❌ Ошибка закрытия позиции: ${orderResult.error}" }
        }
    }

    private suspend fun closePositionBySignal(position: OpenPosition, signal: ru.bolotov.tradebot.strategy.Signal) {
        val marketData = marketDataProvider.fetchMarketData(position.instrumentId) ?: return

        val directionStr = if (position.direction == DomainOrderDirection.BUY) "SELL" else "BUY"
        val orderResult = orderExecutionService.placeOrder(
            accountId = accountId!!,
            instrumentId = position.instrumentId,
            quantity = position.quantity,
            price = marketData.currentPrice,
            direction = directionStr
        )

        if (orderResult.success) {
            val pnl = calculatePnl(position, marketData.currentPrice)

            logger.info { "✅ Позиция закрыта по сигналу: ${position.direction} ${position.instrumentName}, P&L: $pnl ₽" }

            _openPositions.value = _openPositions.value - position.instrumentId
            saveCloseEvent(marketData, position, pnl, "SIGNAL_CLOSE")

            // RabbitMQ (закомментировано)
            eventPublisherService.publishPortfolioChanged()
        } else {
            logger.error { "❌ Ошибка закрытия позиции: ${orderResult.error}" }
        }
    }

    private fun calculatePnl(position: OpenPosition, closePrice: BigDecimal): BigDecimal {
        return if (position.direction == DomainOrderDirection.BUY) {
            (closePrice - position.entryPrice) * BigDecimal.valueOf(position.quantity)
        } else {
            (position.entryPrice - closePrice) * BigDecimal.valueOf(position.quantity)
        }
    }

    private fun startScheduler() {
        schedulerJob = scope.launch {
            while (_isRunning.value) {
                try {
                    takePortfolioSnapshot()
                    delay(loopDelayMs)
                } catch (e: Exception) {
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

    private suspend fun executeTrade(marketData: MarketData, signal: ru.bolotov.tradebot.strategy.Signal) {
        val currentPosition = _openPositions.value[marketData.instrumentId]
        val signalDirection = when (signal.direction) {
            OrderDirection.BUY -> DomainOrderDirection.BUY
            OrderDirection.SELL -> DomainOrderDirection.SELL
            OrderDirection.HOLD -> return
        }

        when {
            // Нет позиции — открываем новую с динамическим размером
            currentPosition == null -> {
                val availableCapital = getAvailableCapital()

                // Проверяем, можно ли открыть новую позицию
                if (!positionSizingService.canOpenNewPosition(availableCapital, _openPositions.value)) {
                    logger.warn { "❌ Нельзя открыть новую позицию (лимит капитала или количества)" }
                    return
                }

                // Рассчитываем размер позиции
                val positionSize = positionSizingService.calculatePositionSize(
                    marketData = marketData,
                    availableCapital = availableCapital,
                    currentPositions = _openPositions.value
                )

                if (!hasEnoughFunds(positionSize.value)) {
                    logger.warn { "❌ Недостаточно средств: нужно ${positionSize.value}, доступно $availableCapital" }
                    return
                }

                openNewPosition(marketData, signal, positionSize)
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
        positionSize: PositionSize
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
            reason = strategyManager.getCurrentStrategy().name,
            explanation = strategyManager.getExplanation(marketData),
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
            savedEvent.status = EventStatus.PROCESSED
            savedEvent.processedAt = Instant.now()
            tradeEventRepository.save(savedEvent)

            val newPosition = OpenPosition(
                positionId = positionId,
                instrumentId = marketData.instrumentId,
                instrumentName = marketData.instrumentName,
                direction = direction,
                entryPrice = marketData.currentPrice,
                quantity = positionSize.quantity,
                entryTime = Instant.now(),
                stopLossPrice = positionSize.stopLossPrice,
                atr = positionSize.atr
            )

            _openPositions.value += (marketData.instrumentId to newPosition)

            logger.info { "📈 Открыта позиция: $direction ${marketData.instrumentName} (${positionSize.quantity} лотов, ${"%.0f".format(positionSize.value)} ₽, ${"%.1f".format(positionSize.capitalUsagePercent)}% депозита)" }

            // RabbitMQ (закомментировано)
            eventPublisherService.publishTradeExecuted(savedEvent)
            eventPublisherService.publishPortfolioChanged()
        } else {
            savedEvent.status = EventStatus.FAILED
            tradeEventRepository.save(savedEvent)
            logger.error { "Ошибка открытия позиции: ${orderResult.error}" }
        }
    }

    private fun saveCloseEvent(data: MarketData, position: OpenPosition, pnl: BigDecimal, reason: String) {
        val closeEvent = TradeEvent(
            instrumentId = data.instrumentId,
            instrumentName = data.instrumentName,
            direction = position.direction,
            price = data.currentPrice,
            quantity = position.quantity,
            totalValue = data.currentPrice * BigDecimal.valueOf(position.quantity),
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

    fun switchToCandlestickStrategy(timeframe: CandlestickPatternStrategy.CandleTimeframe, minConfidence: Double) {
        // Настраиваем стратегию
        candlestickPatternStrategy.setTimeframe(timeframe)
        candlestickPatternStrategy.minConfidence = minConfidence

        // Переключаем через StrategyManager
        strategyManager.switchToCandlestickStrategy(candlestickPatternStrategy)

        // Сохраняем конфигурацию
        strategyConfigPersistenceService.saveCandlestickStrategy(
            timeframe = timeframe.name,
            minConfidence = minConfidence
        )

        // Перезапускаем стрим если бот запущен
        if (_isRunning.value) {
            priceStreamJob?.cancel()
            startPriceStream()
        }

        logger.info { "🕯️ Стратегия переключена на свечные паттерны: таймфрейм=${timeframe.name}, уверенность=${minConfidence}" }
    }

    private suspend fun takePortfolioSnapshot() {
        try {
            if (accountId != null) {
                val portfolio = operationsService.getPortfolioSync(accountId!!)
                val total = portfolio.totalAmountPortfolio?.value ?: BigDecimal.ZERO
                val cash = portfolio.totalAmountCurrencies?.value ?: BigDecimal.ZERO
                val positionsJson = portfolio.positions.joinToString(prefix = "[", postfix = "]") { pos ->
                    val avgPrice = pos.averagePositionPrice?.value ?: BigDecimal.ZERO
                    """{"instrumentId":"${pos.instrumentUid}","quantity":${pos.quantity},"averagePrice":$avgPrice}"""
                }
                val snapshot = PortfolioSnapshot(
                    totalValue = total,
                    cashBalance = cash,
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
                "entryTime" to position.entryTime.toString()
            )
        }
    }

    suspend fun closeAllPositions(): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        val positions = _openPositions.value.toMap()

        for ((instrumentId, position) in positions) {
            try {
                val marketData = marketDataProvider.fetchMarketData(instrumentId)
                if (marketData == null) {
                    results.add(mapOf("instrumentId" to instrumentId, "status" to "failed"))
                    continue
                }

                val closeDirection = if (position.direction == DomainOrderDirection.BUY) "SELL" else "BUY"
                val orderResult = orderExecutionService.placeOrder(
                    accountId = accountId!!,
                    instrumentId = instrumentId,
                    quantity = position.quantity,
                    price = marketData.currentPrice,
                    direction = closeDirection
                )

                if (orderResult.success) {
                    val pnl = calculatePnl(position, marketData.currentPrice)

                    results.add(mapOf(
                        "instrumentId" to instrumentId,
                        "instrumentName" to position.instrumentName,
                        "status" to "closed",
                        "pnl" to pnl.toPlainString()
                    ))

                    _openPositions.value = _openPositions.value - instrumentId
                    saveCloseEvent(marketData, position, pnl, "EMERGENCY_CLOSE")
                } else {
                    results.add(mapOf("instrumentId" to instrumentId, "status" to "failed"))
                }
            } catch (e: Exception) {
                results.add(mapOf("instrumentId" to instrumentId, "status" to "failed"))
            }
        }

        return results
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

            for (pos in brokerPositions) {
                try {
                    val instrumentUid = pos.instrumentUid

                    // Получаем информацию об инструменте
                    val instrumentInfo = try {
                        val share = instrumentsService.getShareByUidSync(instrumentUid)
                        share.ticker to share.name
                    } catch (e: Exception) {
                        logger.warn(e) { "Не удалось получить информацию для $instrumentUid" }
                        instrumentUid to instrumentUid
                    }

                    // 🔧 Money → BigDecimal (у Money есть методы getValue() и getCurrency())
                    val avgPrice = moneyToBigDecimal(pos.averagePositionPrice)

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
                    val existingOpenEvent = tradeEventRepository.findFirstByInstrumentIdAndDirectionAndEventTypeOrderByCreatedAtDesc(
                        instrumentUid,
                        direction,
                        EventType.OPEN
                    )

                    val positionId = existingOpenEvent?.positionId ?: UUID.randomUUID().toString()

                    val restoredPosition = OpenPosition(
                        positionId = positionId,
                        instrumentId = instrumentUid,
                        instrumentName = instrumentInfo.second,
                        direction = direction,
                        entryPrice = avgPrice,
                        quantity = absQuantity,
                        entryTime = entryTime,
                        stopLossPrice = stopLossPrice,
                        atr = atr
                    )

                    restoredPositions[instrumentUid] = restoredPosition
                    logger.info { "📦 Восстановлена позиция: ${restoredPosition.direction} ${restoredPosition.instrumentName} " +
                            "(${restoredPosition.quantity} лотов по ${restoredPosition.entryPrice} ₽)" }

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
            logger.info { "✅ Синхронизация завершена. Восстановлено ${restoredPositions.size} позиций" }

        } catch (e: Exception) {
            logger.error(e) { "❌ Критическая ошибка при синхронизации портфеля" }
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
    data class Trade(val marketData: MarketData, val signal: ru.bolotov.tradebot.strategy.Signal) : Signal()
    data class Close(val position: OpenPosition) : Signal()
}