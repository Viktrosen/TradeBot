package ru.bolotov.tradebot.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.bolotov.tradebot.config.PositionSizingConfig
import ru.bolotov.tradebot.service.TradingBotService
import ru.bolotov.tradebot.strategy.CandlestickPatternStrategy
import ru.bolotov.tradebot.strategy.StrategyManager

@RestController
@RequestMapping("/internal/command")
class InternalCommandController(
    private val tradingBotService: TradingBotService,
    private val strategyManager: StrategyManager,
    private val config: PositionSizingConfig,
    private val candlestickPatternStrategy: CandlestickPatternStrategy
) {

    // ==================== БОТ ====================

    @PostMapping("/start")
    fun start(): ResponseEntity<Map<String, Any>> {
        CoroutineScope(Dispatchers.IO).launch {
            tradingBotService.start()
        }
        return ResponseEntity.ok(
            mapOf(
                "status" to "started",
                "running" to tradingBotService.getStatus()
            )
        )
    }

    @PostMapping("/stop")
    fun stop(): ResponseEntity<Map<String, Any>> {
        tradingBotService.stop()
        return ResponseEntity.ok(
            mapOf(
                "status" to "stopped",
                "running" to tradingBotService.getStatus()
            )
        )
    }

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(
            mapOf(
                "running" to tradingBotService.getStatus(),
                "currentStrategy" to tradingBotService.getCurrentStrategy(),
                "activeInstruments" to tradingBotService.getActiveInstruments()
            )
        )
    }

    // ==================== СТРАТЕГИИ ====================

    @PostMapping("/strategy/simple")
    fun switchToSimpleStrategy(@RequestBody request: Map<String, String>): ResponseEntity<Map<String, Any>> {
        val strategyName = request["strategyName"] ?: "ema"
        tradingBotService.switchToSimpleStrategy(strategyName)
        return ResponseEntity.ok(
            mapOf(
                "status" to "switched",
                "strategy" to tradingBotService.getCurrentStrategy()
            )
        )
    }

    @PostMapping("/strategy/voting")
    fun switchToVotingStrategy(@RequestBody request: Map<String, Int>): ResponseEntity<Map<String, Any>> {
        try {
            tradingBotService.switchToVotingStrategy(request)
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().body(mapOf("status" to "error", "error" to (e.message ?: "Некорректная конфигурация")))
        }
        return ResponseEntity.ok(
            mapOf(
                "status" to "switched",
                "strategy" to tradingBotService.getCurrentStrategy().name,
                "weights" to request
            )
        )
    }

    @PostMapping("/strategy/candlestick")
    fun switchToCandlestickStrategy(@RequestBody request: Map<String, Any> = emptyMap()): ResponseEntity<Map<String, Any>> {
        // Получаем параметры из запроса (опционально)
        val timeframeName = request["timeframe"] as? String
        val minConfidence = (request["minConfidence"] as? Double) ?: candlestickPatternStrategy.minConfidence

        if (minConfidence !in 0.75..1.0) {
            return ResponseEntity.badRequest().body(
                mapOf("success" to false, "error" to "Минимальная уверенность свечной стратегии должна быть от 0.75 до 1")
            )
        }

        val timeframe = if (timeframeName != null) {
            try {
                CandlestickPatternStrategy.CandleTimeframe.valueOf(timeframeName.uppercase())
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest().body(
                    mapOf(
                        "success" to false,
                        "error" to "Неизвестный таймфрейм. Доступны: ${CandlestickPatternStrategy.CandleTimeframe.entries.joinToString { it.name }}"
                    )
                )
            }
        } else {
            candlestickPatternStrategy.currentTimeframe
        }

        tradingBotService.switchToCandlestickStrategy(
            timeframe = timeframe,
            minConfidence = minConfidence
        )

        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "strategy" to "CandlestickPatterns",
                "timeframe" to timeframe.name,
                "minConfidence" to minConfidence
            )
        )
    }

    @PostMapping("/strategy/confirmation")
    fun switchToConfirmationStrategy(@RequestBody request: Map<String, List<String>>): ResponseEntity<Map<String, Any>> {
        val indicators = request["indicators"] ?: listOf("EMA", "RSI")
        try {
            tradingBotService.switchToConfirmationStrategy(indicators)
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().body(mapOf("status" to "error", "error" to (e.message ?: "Некорректная конфигурация")))
        }
        return ResponseEntity.ok(
            mapOf(
                "status" to "switched",
                "strategy" to tradingBotService.getCurrentStrategy().name,
                "indicators" to indicators
            )
        )
    }

    @GetMapping("/strategy/available")
    fun getAvailableStrategies(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(
            mapOf(
                "strategies" to listOf(
                    mapOf("name" to "ema", "type" to "simple", "description" to "Cross EMA (5/21)"),
                    mapOf("name" to "rsi", "type" to "simple", "description" to "RSI oversold/overbought"),
                    mapOf("name" to "macd", "type" to "simple", "description" to "MACD crossover"),
                    mapOf("name" to "confirmation", "type" to "confirmation", "description" to "Подтверждение сигналов: EMA, RSI, MACD, BB"),
                    mapOf("name" to "voting", "type" to "voting", "description" to "Взвешенное голосование EMA, RSI, MACD, BB"),
                    mapOf("name" to "candlestick", "type" to "patterns", "description" to "Свечные паттерны (Engulfing, Hammer, Doji и др.)")
                ),
                "current" to mapOf(
                    "name" to tradingBotService.getCurrentStrategy().name,
                    "description" to tradingBotService.getCurrentStrategy().description
                )
            )
        )
    }

    // ==================== ИНСТРУМЕНТЫ ====================

    @GetMapping("/instruments")
    fun getInstruments(): ResponseEntity<Map<String, Any>> {
        val instruments = tradingBotService.getActiveInstruments()
        return ResponseEntity.ok(mapOf("instruments" to instruments, "count" to instruments.size))
    }

    @PostMapping("/instruments")
    fun updateInstruments(@RequestBody request: Map<String, List<String>>): ResponseEntity<Map<String, Any>> {
        val instruments = request["instruments"] ?: emptyList()
        tradingBotService.updateInstruments(instruments)
        return ResponseEntity.ok(
            mapOf(
                "status" to "updated",
                "instruments" to instruments
            )
        )
    }

    @PostMapping("/instruments/filters")
    fun updateInstrumentFilters(@RequestBody request: InstrumentFiltersRequest): ResponseEntity<Map<String, Any>> {
        tradingBotService.updateInstrumentFilters(
            minDailyVolume = request.minDailyVolume,
            minVolatility = request.minVolatility,
            maxVolatility = request.maxVolatility,
            maxCount = request.maxCount
        )
        return ResponseEntity.ok(mapOf("status" to "updated", "filters" to request))
    }

    @PostMapping("/instruments/rescan")
    fun rescanInstruments(): ResponseEntity<Map<String, Any>> {
        val instruments = runBlocking {
            tradingBotService.rescanInstruments()
        }

        return ResponseEntity.ok(
            mapOf(
                "status" to "rescanned",
                "count" to instruments.size,
                "instruments" to instruments,
                "running" to tradingBotService.getStatus()
            )
        )
    }

    // ==================== ПОЗИЦИИ ====================

    @GetMapping("/positions")
    fun getOpenPositions(): ResponseEntity<Map<String, Any>> {
        val positions = tradingBotService.getOpenPositions()
        return ResponseEntity.ok(
            mapOf(
                "openPositions" to positions,
                "count" to positions.size
            )
        )
    }

    @GetMapping("/dashboard")
    fun getDashboard(): ResponseEntity<DashboardResponse> =
        ResponseEntity.ok(tradingBotService.getDashboard())

    @PostMapping("/positions/{positionId}/close")
    fun closePosition(@PathVariable positionId: String): ResponseEntity<Map<String, Any>> {
        val closed = runBlocking { tradingBotService.closePosition(positionId) }
        return if (closed) {
            ResponseEntity.ok(mapOf("status" to "closed", "positionId" to positionId))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/close-all")
    fun closeAllPositions(): ResponseEntity<Map<String, Any>> {
        // Запускаем закрытие в фоне, не блокируя ответ
        CoroutineScope(Dispatchers.IO).launch {
            tradingBotService.closeAllPositionsAsync()
        }

        return ResponseEntity.ok(
            mapOf(
                "status" to "closing",
                "message" to "Запущен процесс закрытия всех позиций. Статус можно проверить через GET /positions"
            )
        )
    }

    // ==================== УПРАВЛЕНИЕ РИСКАМИ ====================

    @GetMapping("/risk")
    fun getRiskConfig(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "config" to config.toMap()
            )
        )
    }

    @PostMapping("/risk/update")
    fun updateRiskConfig(@RequestBody request: RiskConfigRequest): ResponseEntity<Map<String, Any?>> {
        return try {
            val updates = mutableListOf<String>()

            request.riskPerTrade?.let {
                config.riskPerTrade = it
                updates.add("riskPerTrade = ${"%.1f".format(it * 100)}%")
            }

            request.maxCapitalUsage?.let {
                config.maxCapitalUsage = it
                updates.add("maxCapitalUsage = ${"%.0f".format(it * 100)}%")
            }

            request.maxPositionSize?.let {
                config.maxPositionSize = it
                updates.add("maxPositionSize = $it ₽")
            }

            request.minPositionSize?.let {
                config.minPositionSize = it
                updates.add("minPositionSize = $it ₽")
            }

            request.maxPositions?.let {
                config.maxPositions = it
                updates.add("maxPositions = $it")
            }

            request.brokerLimitUsage?.let {
                config.brokerLimitUsage = it
                updates.add("brokerLimitUsage = ${"%.0f".format(it * 100)}%")
            }

            request.minOrderCashBuffer?.let {
                config.minOrderCashBuffer = it
                updates.add("minOrderCashBuffer = $it")
            }

            request.allowMinPositionSizeUpscale?.let {
                config.allowMinPositionSizeUpscale = it
                updates.add("allowMinPositionSizeUpscale = $it")
            }

            // Принудительно сохраняем в БД после массового обновления
            config.persist()

            ResponseEntity.ok(
                mapOf(
                    "success" to true,
                    "message" to "Параметры риска обновлены",
                    "updates" to updates,
                    "config" to config.toMap()
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf(
                    "success" to false,
                    "error" to e.message
                )
            )
        }
    }

    @PostMapping("/risk/reset")
    fun resetRiskConfig(): ResponseEntity<Map<String, Any>> {
        config.riskPerTrade = 0.02
        config.maxCapitalUsage = 0.80
        config.maxPositionSize = 100_000L
        config.minPositionSize = 5_000L
        config.maxPositions = 10
        config.brokerLimitUsage = 0.95
        config.minOrderCashBuffer = 100L
        config.allowMinPositionSizeUpscale = false

        config.persist()

        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "message" to "Параметры риска сброшены к значениям по умолчанию",
                "config" to config.toMap()
            )
        )
    }
}

data class InstrumentFiltersRequest(
    val minDailyVolume: Long,
    val minVolatility: Double,
    val maxVolatility: Double,
    val maxCount: Int,
    val allowedCategories: List<String> = listOf("STOCK", "BOND")  // 🆕
)

data class RiskConfigRequest(
    val riskPerTrade: Double? = null,
    val maxCapitalUsage: Double? = null,
    val maxPositionSize: Long? = null,
    val minPositionSize: Long? = null,
    val maxPositions: Int? = null,
    val brokerLimitUsage: Double? = null,
    val minOrderCashBuffer: Long? = null,
    val allowMinPositionSizeUpscale: Boolean? = null
)
