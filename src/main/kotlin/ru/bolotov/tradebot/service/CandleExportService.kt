package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.broker.CandleHistoryReader
import ru.bolotov.tradebot.broker.HistoricalCandle
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore

class CandleExportBusyException : RuntimeException()
class CandleExportUnavailableException(cause: Exception) : RuntimeException(cause)

/** Bounded read-only export. A failed chunk never produces a successful partial CSV. */
@Service
class CandleExportService(private val reader: CandleHistoryReader) {
    private val logger = KotlinLogging.logger {}
    private val exportPermit = Semaphore(1)

    /** Exports candle start times in [from, to), including incomplete candles explicitly marked as such. */
    fun export(instrumentUid: UUID, from: Instant, to: Instant): String {
        return export(listOf(instrumentUid), from, to)
    }

    /**
     * Exports M5 history for several instruments using one bounded, read-only broker operation.
     *
     * The same interval is used for every instrument. The result is all-or-nothing: a broker failure
     * for one instrument discards the entire CSV so analysis never silently uses a partial sample.
     */
    fun export(instrumentUids: Collection<UUID>, from: Instant, to: Instant): String {
        val uniqueInstrumentUids = instrumentUids.distinct().sortedBy(UUID::toString)
        require(uniqueInstrumentUids.isNotEmpty()) { "At least one instrumentUid is required" }
        require(uniqueInstrumentUids.size <= MAX_INSTRUMENTS_PER_EXPORT) {
            "Maximum instruments per export is $MAX_INSTRUMENTS_PER_EXPORT"
        }
        require(from < to) { "from must precede to" }
        require(Duration.between(from, to) <= Duration.ofDays(7)) { "Maximum range is 7 days" }
        require(to <= Instant.now()) { "to must not be in the future" }
        if (!exportPermit.tryAcquire()) throw CandleExportBusyException()
        try {
            val candlesByInstrument = uniqueInstrumentUids.associateWith { sortedMapOf<Instant, HistoricalCandle>() }
            uniqueInstrumentUids.forEach { instrumentUid ->
                var chunkFrom = from
                while (chunkFrom < to) {
                    val chunkTo = minOf(chunkFrom.plus(Duration.ofDays(1)), to)
                    reader.read(instrumentUid, chunkFrom, chunkTo)
                        .filter { it.time >= chunkFrom && it.time < chunkTo }
                        .forEach { candlesByInstrument.getValue(instrumentUid)[it.time] = it }
                    chunkFrom = chunkTo
                }
            }
            val csv = buildString {
                append("instrument_uid,interval,time_utc,open,high,low,close,volume,is_complete\n")
                candlesByInstrument.forEach { (instrumentUid, candles) ->
                    candles.values.forEach { candle ->
                        append(listOf(
                            instrumentUid, "M5", candle.time,
                            candle.open.toPlainString(), candle.high.toPlainString(),
                            candle.low.toPlainString(), candle.close.toPlainString(),
                            candle.volume, candle.complete
                        ).joinToString(","))
                        append('\n')
                    }
                }
            }
            logger.info {
                "Экспорт свечей M5 завершён: инструментов=${uniqueInstrumentUids.size}, from=$from, to=$to, " +
                    "свечей=${candlesByInstrument.values.sumOf { it.size }}"
            }
            return csv
        } catch (error: Exception) {
            // Provider exception text can contain transport details; keep it out of the HTTP response and logs.
            logger.warn {
                "Экспорт свечей M5 не выполнен: инструментов=${uniqueInstrumentUids.size}, " +
                    "тип ошибки=${error.javaClass.simpleName}"
            }
            throw CandleExportUnavailableException(error)
        } finally {
            exportPermit.release()
        }
    }

    private companion object {
        const val MAX_INSTRUMENTS_PER_EXPORT = 50
    }
}
