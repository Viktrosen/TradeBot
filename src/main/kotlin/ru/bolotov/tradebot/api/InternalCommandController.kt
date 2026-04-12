package ru.bolotov.tradebot.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.bolotov.tradebot.service.TradingBotService

@RestController
@RequestMapping("/internal/command")
class InternalCommandController(
    private val tradingBotService: TradingBotService
) {

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

    @PostMapping("/strategy/composite")
    fun switchToCompositeStrategy(@RequestBody request: Map<String, Any>): ResponseEntity<Map<String, Any>> {
        @Suppress("UNCHECKED_CAST")
        val weights = request["weights"] as? Map<String, Int> ?: emptyMap()
        tradingBotService.switchToCompositeStrategy(weights)
        return ResponseEntity.ok(
            mapOf(
                "status" to "switched",
                "strategy" to tradingBotService.getCurrentStrategy(),
                "weights" to weights
            )
        )
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

    //Аварийное закрытие всех позиций
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

    // Дополнительный эндпоинт для просмотра открытых позиций
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
}

data class InstrumentFiltersRequest(
    val minDailyVolume: Long,
    val minVolatility: Double,
    val maxVolatility: Double,
    val maxCount: Int
)