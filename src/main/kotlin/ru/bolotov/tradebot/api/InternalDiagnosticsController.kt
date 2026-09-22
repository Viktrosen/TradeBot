package ru.bolotov.tradebot.api

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import ru.bolotov.tradebot.service.CandleExportBusyException
import ru.bolotov.tradebot.service.CandleExportService
import ru.bolotov.tradebot.service.CandleExportUnavailableException
import java.time.Instant
import java.util.UUID

/** Operator diagnostics protected by the existing internal API Basic Auth security chain. */
@RestController
@RequestMapping("/internal/diagnostics")
class InternalDiagnosticsController(private val candleExport: CandleExportService) {
    /** Downloads M5 OHLCV in UTC without starting trading or changing orders or persisted positions. */
    @GetMapping("/candles.csv", produces = ["text/csv"])
    fun candles(
        @RequestParam instrumentUid: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant
    ): ResponseEntity<String> {
        val csv = export { candleExport.export(instrumentUid, from, to) }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"candles-$instrumentUid-m5.csv\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(csv)
    }

    /** Downloads one analysis-ready CSV for several M5 instruments without touching trading state. */
    @PostMapping("/candles/batch.csv", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = ["text/csv"])
    fun candlesBatch(@RequestBody request: CandleBatchExportRequest): ResponseEntity<String> {
        val csv = export { candleExport.export(request.instrumentUids, request.from, request.to) }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"candles-m5-batch.csv\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(csv)
    }

    private fun export(action: () -> String): String = try {
        action()
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    } catch (_: CandleExportBusyException) {
        throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Another candle export is running")
    } catch (_: CandleExportUnavailableException) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Candle history could not be retrieved")
    }
}

/** Request body for a bounded diagnostic export; all times are ISO-8601 instants, preferably UTC. */
data class CandleBatchExportRequest(
    val instrumentUids: List<UUID>,
    val from: Instant,
    val to: Instant
)
