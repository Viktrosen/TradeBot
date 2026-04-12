package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.config.InstrumentFilterProperties
import ru.bolotov.tradebot.domain.model.*
import ru.bolotov.tradebot.domain.model.OrderDirection as DomainOrderDirection
import ru.bolotov.tradebot.domain.repository.PortfolioSnapshotRepository
import ru.bolotov.tradebot.domain.repository.TradeEventRepository
import ru.bolotov.tradebot.strategy.*
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.core.OperationsService
import ru.tinkoff.piapi.core.SandboxService
import ru.tinkoff.piapi.core.UsersService
import java.math.BigDecimal
import java.time.Instant

private val logger = KotlinLogging.logger {}

data class OpenPosition(
    val instrumentId: String,
    val instrumentName: String,
    val direction: DomainOrderDirection,
    val entryPrice: BigDecimal,
    val quantity: Long,
    val entryTime: Instant
)

@Service
class TradingBotService(
    private val marketDataProvider: MarketDataProvider,
    private val strategyManager: StrategyManager,
    private val orderExecutionService: OrderExecutionService,
    private val tradeEventRepository: TradeEventRepository,
    private val portfolioSnapshotRepository: PortfolioSnapshotRepository,
    private val eventPublisherService: EventPublisherService,
    private val operationsService: OperationsService,
    private val usersService: UsersService,
    private val sandboxService: SandboxService,
    private val instrumentSelector: InstrumentSelector,
    private val filterProperties: InstrumentFilterProperties,
    @Value("\${trading.loop.delay-ms:60000}") private val loopDelayMs: Long,
    @Value("\${trading.quantity.default:10}") private val defaultQuantity: Long,
    @Value("\${trading.quantity.sber:10}") private val sberQuantity: Long,
    @Value("\${trading.quantity.t:10}") private val tQuantity: Long,
    @Value("\${trading.quantity.ydex:10}") private val ydexQuantity: Long,
    @Qualifier("sandboxEnabled") private val sandboxEnabled: Boolean
) {
    private var isRunning = false
    private var activeInstruments = listOf<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var accountId: String? = null

    // Хранилище открытых позиций
    private val openPositions = mutableMapOf<String, OpenPosition>()

    // Параметры
    private val stopLossPercent = 0.02   // 2% стоп-лосс
    private val takeProfitPercent = 0.03 // 3% тейк-профит

    init {
        runBlocking {
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

                    // Пополнение счёта
                    if (accountId != null) {
                        try {
                            // Получаем текущий баланс
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
                                logger.info { "Sandbox-счёт пополнен на $neededAmount RUB (текущий баланс был $currentBalance)" }
                            } else {
                                logger.info { "Sandbox-счёт уже имеет достаточный баланс: $currentBalance RUB (пополнение не требуется)" }
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

            val selectedInstruments = instrumentSelector.selectTradableInstruments(
            minDailyVolume = filterProperties.minDailyVolume,
            minVolatility = filterProperties.minVolatility,
            maxVolatility = filterProperties.maxVolatility,
            maxCount = filterProperties.maxCount
        )

            activeInstruments = selectedInstruments.map { it.uid }
            logger.info { "Отобрано ${activeInstruments.size} инструментов для торговли" }
            selectedInstruments.forEach { instrument ->
                logger.info { "  - ${instrument.ticker}: цена=${instrument.price}, объём=${instrument.dailyVolume}" }
            }
            logger.info { "Загружены UID инструментов: $activeInstruments" }
        }
    }

    suspend fun start() {
        if (isRunning) return
        if (accountId == null) {
            logger.error { "Не указан accountId. Невозможно запустить бота." }
            return
        }
        isRunning = true
        logger.info { "Запуск торгового бота" }
        //eventPublisherService.publishBotStatusChanged("RUNNING")
        launchTradingLoop()
    }

    fun stop() {
        isRunning = false
        scope.coroutineContext.cancelChildren()
        logger.info { "Бот остановлен" }
        //eventPublisherService.publishBotStatusChanged("STOPPED")
    }

    fun updateInstruments(instruments: List<String>) {
        activeInstruments = instruments
        logger.info { "Обновлён список инструментов: $activeInstruments" }
    }

    private suspend fun launchTradingLoop() {
        while (isRunning) {
            try {
                // Сначала проверяем стоп-лосс и тейк-профит для открытых позиций
                for ((instrumentId, position) in openPositions.toMap()) {
                    val marketData = marketDataProvider.fetchMarketData(instrumentId) ?: continue
                    val currentPrice = marketData.currentPrice
                    val pnlPercent = if (position.direction == DomainOrderDirection.BUY) {
                        (currentPrice - position.entryPrice) / position.entryPrice
                    } else {
                        (position.entryPrice - currentPrice) / position.entryPrice
                    }.toDouble()

                    when {
                        pnlPercent <= -stopLossPercent -> {
                            logger.warn { "Стоп-лосс для ${position.instrumentName}: ${pnlPercent * 100}%" }
                            val closeDirection = if (position.direction == DomainOrderDirection.BUY) "SELL" else "BUY"
                            orderExecutionService.placeOrder(
                                accountId = accountId!!,
                                instrumentId = instrumentId,
                                quantity = position.quantity,
                                price = currentPrice,
                                direction = closeDirection
                            )
                            openPositions.remove(instrumentId)
                        }
                        pnlPercent >= takeProfitPercent -> {
                            logger.info { "Тейк-профит для ${position.instrumentName}: ${pnlPercent * 100}%" }
                            val closeDirection = if (position.direction == DomainOrderDirection.BUY) "SELL" else "BUY"
                            orderExecutionService.placeOrder(
                                accountId = accountId!!,
                                instrumentId = instrumentId,
                                quantity = position.quantity,
                                price = currentPrice,
                                direction = closeDirection
                            )
                            openPositions.remove(instrumentId)
                        }
                    }
                }

                // Затем проверяем новые сигналы
                for (instrument in activeInstruments) {
                    val marketData = marketDataProvider.fetchMarketData(instrument) ?: continue
                    val signal = strategyManager.analyze(marketData)

                    logger.info { "Сигнал для ${marketData.instrumentName}: ${signal.direction} (confidence=${signal.confidence})" }

                    if (signal.direction != OrderDirection.HOLD && signal.confidence > 0.5) {
                        executeTrade(marketData, signal)
                    }
                }
                takePortfolioSnapshot()
                delay(loopDelayMs) // 60 секунд между циклами
            } catch (e: Exception) {
                logger.error(e) { "Ошибка в торговом цикле" }
                delay(10000)
            }
        }
    }

    fun switchToSimpleStrategy(strategyName: String) {
        strategyManager.switchToSimpleStrategy(strategyName)
        logger.info { "Переключено на стратегию: $strategyName" }
    }

    fun switchToCompositeStrategy(weights: Map<String, Int>) {
        strategyManager.switchToCompositeStrategy(weights)
        logger.info { "Переключено на комбинированную стратегию с весами: $weights" }
    }

    private suspend fun executeTrade(data: MarketData, signal: Signal) {
        val quantity = calculateDynamicQuantity(data.currentPrice)
        val totalValue = data.currentPrice * BigDecimal.valueOf(quantity)

        if (!hasEnoughFunds(totalValue)) {
            logger.warn { "❌ Недостаточно средств для ${signal.direction} ${data.instrumentName} на сумму $totalValue RUB" }
            return
        }

        val currentPosition = openPositions[data.instrumentId]

        // Проверяем, нужно ли закрыть позицию
        val shouldClose = when {
            currentPosition == null -> false
            currentPosition.direction == DomainOrderDirection.BUY && signal.direction == OrderDirection.SELL -> true
            currentPosition.direction == DomainOrderDirection.SELL && signal.direction == OrderDirection.BUY -> true
            else -> false
        }

        if (shouldClose && currentPosition != null) {
            // Закрываем позицию
            val closeDirection = if (currentPosition.direction == DomainOrderDirection.BUY) "SELL" else "BUY"
            val orderResult = orderExecutionService.placeOrder(
                accountId = accountId ?: throw IllegalStateException("accountId не задан"),
                instrumentId = data.instrumentId,
                quantity = currentPosition.quantity,
                price = data.currentPrice,
                direction = closeDirection
            )

            if (orderResult.success) {
                // Считаем P&L
                val pnl = if (currentPosition.direction == DomainOrderDirection.BUY) {
                    (data.currentPrice - currentPosition.entryPrice) * BigDecimal.valueOf(currentPosition.quantity)
                } else {
                    (currentPosition.entryPrice - data.currentPrice) * BigDecimal.valueOf(currentPosition.quantity)
                }
                logger.info { "Позиция закрыта: ${currentPosition.direction} ${data.instrumentName}, P&L: $pnl ₽" }
                openPositions.remove(data.instrumentId)

                // RabbitMQ (закомментировано)
                // eventPublisherService.publishPortfolioChanged()
            }
        } else if (currentPosition == null) {
            // Открываем новую позицию
            val tradeEvent = TradeEvent(
                instrumentId = data.instrumentId,
                instrumentName = data.instrumentName,
                direction = if (signal.direction == OrderDirection.BUY) DomainOrderDirection.BUY else DomainOrderDirection.SELL,
                price = data.currentPrice,
                quantity = quantity,
                totalValue = totalValue,
                reason = strategyManager.getCurrentStrategy().name,
                explanation = strategyManager.getExplanation(data),
                status = EventStatus.PENDING
            )

            val savedEvent = tradeEventRepository.save(tradeEvent)

            val orderResult = orderExecutionService.placeOrder(
                accountId = accountId ?: throw IllegalStateException("accountId не задан"),
                instrumentId = data.instrumentId,
                quantity = quantity,
                price = data.currentPrice,
                direction = if (signal.direction == OrderDirection.BUY) "BUY" else "SELL"
            )

            if (orderResult.success) {
                savedEvent.status = EventStatus.PROCESSED
                savedEvent.processedAt = Instant.now()
                tradeEventRepository.save(savedEvent)

                // Сохраняем открытую позицию
                openPositions[data.instrumentId] = OpenPosition(
                    instrumentId = data.instrumentId,
                    instrumentName = data.instrumentName,
                    direction = if (signal.direction == OrderDirection.BUY) DomainOrderDirection.BUY else DomainOrderDirection.SELL,
                    entryPrice = data.currentPrice,
                    quantity = quantity,
                    entryTime = Instant.now()
                )

                logger.info { "Открыта позиция: ${savedEvent.direction} ${data.instrumentName} по ${data.currentPrice}" }

                // RabbitMQ (закомментировано)
                // eventPublisherService.publishTradeExecuted(savedEvent)
                // eventPublisherService.publishPortfolioChanged()
            } else {
                savedEvent.status = EventStatus.FAILED
                tradeEventRepository.save(savedEvent)
                logger.error { "Ошибка открытия позиции: ${orderResult.error}" }
            }
        } else {
            logger.info { "Уже есть открытая позиция по ${data.instrumentName}, ждём сигнала на закрытие" }
        }
    }

    private fun calculateDynamicQuantity(price: BigDecimal): Long {
        // Максимальная сумма на одну сделку (например, 50 000 ₽)
        val maxInvestment = 50_000L

        // Рассчитываем количество лотов (минимум 1, максимум 100)
        val quantity = (maxInvestment / price.toLong()).coerceIn(1L, 100L)

        logger.debug { "Расчёт количества: цена=$price, макс.сумма=$maxInvestment, лотов=$quantity" }

        return quantity
    }

    private suspend fun hasEnoughFunds(requiredAmount: BigDecimal): Boolean {
        return try {
            val portfolio = operationsService.getPortfolioSync(accountId!!)
            val cash = portfolio.totalAmountCurrencies?.value ?: BigDecimal.ZERO
            val hasFunds = cash >= requiredAmount

            if (!hasFunds) {
                logger.warn { "⚠️ Недостаточно средств! Доступно: $cash RUB, требуется: $requiredAmount RUB" }
            } else {
                logger.debug { "✅ Достаточно средств: $cash RUB >= $requiredAmount RUB" }
            }
            hasFunds
        } catch (e: Exception) {
            logger.error(e) { "Ошибка проверки баланса" }
            false
        }
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
            } else {
                val snapshot = PortfolioSnapshot(
                    totalValue = BigDecimal.valueOf(1_000_000),
                    cashBalance = BigDecimal.valueOf(500_000),
                    positions = "[]"
                )
                portfolioSnapshotRepository.save(snapshot)
            }
            // RabbitMQ (закомментировано)
            // eventPublisherService.publishPortfolioChanged()
        } catch (e: Exception) {
            logger.error(e) { "Ошибка сохранения снимка портфеля" }
        }
    }

    // Получить открытые позиции
    fun getOpenPositions(): List<Map<String, Any>> {
        return openPositions.values.map { position ->
            mapOf(
                "instrumentId" to position.instrumentId,
                "instrumentName" to position.instrumentName,
                "direction" to position.direction.name,
                "entryPrice" to position.entryPrice.toPlainString(),
                "quantity" to position.quantity,
                "entryTime" to position.entryTime.toString()
            )
        }
    }

    // Аварийное закрытие всех позиций
    suspend fun closeAllPositions(): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()

        for ((instrumentId, position) in openPositions.toMap()) {
            try {
                val marketData = marketDataProvider.fetchMarketData(instrumentId)
                if (marketData == null) {
                    results.add(
                        mapOf(
                            "instrumentId" to instrumentId,
                            "status" to "failed",
                            "error" to "Не удалось получить текущую цену"
                        )
                    )
                    continue
                }

                val closeDirection = if (position.direction == DomainOrderDirection.BUY) "SELL" else "BUY"
                val orderResult = orderExecutionService.placeOrder(
                    accountId = accountId ?: throw IllegalStateException("accountId не задан"),
                    instrumentId = instrumentId,
                    quantity = position.quantity,
                    price = marketData.currentPrice,
                    direction = closeDirection
                )

                if (orderResult.success) {
                    val pnl = if (position.direction == DomainOrderDirection.BUY) {
                        (marketData.currentPrice - position.entryPrice) * BigDecimal.valueOf(position.quantity)
                    } else {
                        (position.entryPrice - marketData.currentPrice) * BigDecimal.valueOf(position.quantity)
                    }

                    results.add(
                        mapOf(
                            "instrumentId" to instrumentId,
                            "instrumentName" to position.instrumentName,
                            "status" to "closed",
                            "entryPrice" to position.entryPrice.toPlainString(),
                            "closePrice" to marketData.currentPrice.toPlainString(),
                            "pnl" to pnl.toPlainString(),
                            "direction" to position.direction.name
                        )
                    )

                    logger.info { "Аварийное закрытие: ${position.direction} ${position.instrumentName}, P&L: $pnl ₽" }
                    openPositions.remove(instrumentId)
                } else {
                    results.add(
                        mapOf(
                            "instrumentId" to instrumentId,
                            "instrumentName" to position.instrumentName,
                            "status" to "failed",
                            "error" to orderResult.error.toString()
                        )
                    )
                    logger.error { "Ошибка аварийного закрытия ${position.instrumentName}: ${orderResult.error}" }
                }
            } catch (e: Exception) {
                results.add(
                    mapOf(
                        "instrumentId" to instrumentId,
                        "status" to "failed",
                        "error" to e.message.toString()
                    )
                )
                logger.error(e) { "Ошибка при аварийном закрытии $instrumentId" }
            }
        }

        return results
    }

    fun updateInstrumentFilters(
        minDailyVolume: Long,
        minVolatility: Double,
        maxVolatility: Double,
        maxCount: Int
    ) {
        // Обновляем свойства в bean'е
        filterProperties.minDailyVolume = minDailyVolume
        filterProperties.minVolatility = minVolatility
        filterProperties.maxVolatility = maxVolatility
        filterProperties.maxCount = maxCount

        // Перезапускаем отбор инструментов
        CoroutineScope(Dispatchers.IO).launch {
            val selected = instrumentSelector.selectTradableInstruments(
                minDailyVolume = minDailyVolume,
                minVolatility = minVolatility,
                maxVolatility = maxVolatility,
                maxCount = maxCount
            )
            activeInstruments = selected.map { it.uid }
            logger.info { "Фильтры обновлены. Отобрано ${activeInstruments.size} инструментов" }
        }
    }

    fun getStatus() = isRunning
    fun getActiveInstruments() = activeInstruments
    fun getCurrentStrategy() = strategyManager.getCurrentStrategy().name
}