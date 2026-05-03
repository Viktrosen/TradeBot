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
        tradingBotService.switchToVotingStrategy(request)
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

        // Настраиваем стратегию
        if (timeframeName != null) {
            try {
                val timeframe = CandlestickPatternStrategy.CandleTimeframe.valueOf(timeframeName.uppercase())
                candlestickPatternStrategy.setTimeframe(timeframe)
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest().body(
                    mapOf(
                        "success" to false,
                        "error" to "Неизвестный таймфрейм. Доступны: ${CandlestickPatternStrategy.CandleTimeframe.entries.joinToString { it.name }}"
                    )
                )
            }
        }

        candlestickPatternStrategy.minConfidence = minConfidence

        // Переключаем стратегию через StrategyManager
        // Нужно добавить метод в StrategyManager для работы с CandlestickPatternStrategy
        tradingBotService.switchToCandlestickStrategy(
            timeframe = candlestickPatternStrategy.currentTimeframe,
            minConfidence = minConfidence
        )

        return ResponseEntity.ok(
            mapOf(
                "success" to true,
                "strategy" to "CandlestickPatterns",
                "timeframe" to candlestickPatternStrategy.currentTimeframe.name,
                "minConfidence" to minConfidence
            )
        )
    }

    @PostMapping("/strategy/confirmation")
    fun switchToConfirmationStrategy(@RequestBody request: Map<String, List<String>>): ResponseEntity<Map<String, Any>> {
        val indicators = request["indicators"] ?: listOf("EMA", "RSI")
        tradingBotService.switchToConfirmationStrategy(indicators)
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
                    mapOf("name" to "confirmation", "type" to "voting", "description" to "EMA+RSI+MACD+BB голосование"),
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

    @PostMapping("/close-all")
    fun closeAllPositions(): ResponseEntity<Map<String, Any>> {
        return try {
            var result: List<Map<String, Any>> = emptyList()
            runBlocking {
                result = tradingBotService.closeAllPositions()
            }
            ResponseEntity.ok(
                mapOf(
                    "status" to "closed",
                    "closedPositions" to result,
                    "message" to "Все позиции закрыты"
                )
            )
        } catch (e: Exception) {
            ResponseEntity.status(500).body(
                mapOf(
                    "status" to "error",
                    "error" to (e.message ?: "Unknown error")
                )
            )
        }
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
    val maxCount: Int
)

data class RiskConfigRequest(
    val riskPerTrade: Double? = null,
    val maxCapitalUsage: Double? = null,
    val maxPositionSize: Long? = null,
    val minPositionSize: Long? = null,
    val maxPositions: Int? = null
)