package ru.bolotov.tradebot.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.bolotov.tradebot.broker.CandleHistoryReader
import ru.bolotov.tradebot.broker.HistoricalCandle
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class CandleExportServiceTest {
    private val uid = UUID.fromString("c7485564-ed92-45fd-a724-1214aa202904")
    private val from = Instant.parse("2020-01-01T00:00:00Z")

    @Test
    fun `splits days and exports sorted unique candles within half open range`() {
        val calls = mutableListOf<Pair<Instant, Instant>>()
        val service = CandleExportService(object : CandleHistoryReader {
            override fun read(instrumentUid: UUID, from: Instant, to: Instant): List<HistoricalCandle> {
                assertEquals(uid, instrumentUid)
                calls += from to to
                return listOf(candle(to), candle(from.plusSeconds(300), false), candle(from), candle(from))
            }
        })
        val csv = service.export(uid, from, from.plusSeconds(172800))
        assertEquals(listOf(from to from.plusSeconds(86400), from.plusSeconds(86400) to from.plusSeconds(172800)), calls)
        val lines = csv.trimEnd().lines()
        assertEquals(5, lines.size)
        assertTrue(lines[1].contains(",2020-01-01T00:00:00Z,1.25,1.25,1.25,1.25,10,true"))
        assertTrue(lines[2].endsWith(",false"))
        assertFalse(csv.contains("2020-01-03T00:00:00Z"))
    }

    @Test
    fun `invalid ranges do not call broker`() {
        val service = CandleExportService(object : CandleHistoryReader {
            override fun read(instrumentUid: UUID, from: Instant, to: Instant): List<HistoricalCandle> =
                error("Must not call broker")
        })
        assertThrows(IllegalArgumentException::class.java) { service.export(uid, from, from) }
        assertThrows(IllegalArgumentException::class.java) { service.export(uid, from, from.plusSeconds(8 * 86400)) }
        assertThrows(IllegalArgumentException::class.java) { service.export(uid, Instant.now(), Instant.now().plusSeconds(600)) }
    }

    @Test
    fun `batch export is deterministic, deduplicated and contains every requested instrument`() {
        val secondUid = UUID.fromString("b7485564-ed92-45fd-a724-1214aa202904")
        val calls = mutableListOf<UUID>()
        val service = CandleExportService(object : CandleHistoryReader {
            override fun read(instrumentUid: UUID, from: Instant, to: Instant): List<HistoricalCandle> {
                calls += instrumentUid
                return listOf(candle(from), candle(from))
            }
        })

        val csv = service.export(listOf(uid, secondUid, uid), from, from.plusSeconds(600))

        assertEquals(setOf(uid, secondUid), calls.toSet())
        assertEquals(3, csv.trimEnd().lines().size)
        assertTrue(csv.contains(uid.toString()))
        assertTrue(csv.contains(secondUid.toString()))
    }

    @Test
    fun `batch export rejects an empty or oversized instrument list`() {
        val service = CandleExportService(object : CandleHistoryReader {
            override fun read(instrumentUid: UUID, from: Instant, to: Instant): List<HistoricalCandle> = emptyList()
        })

        assertThrows(IllegalArgumentException::class.java) { service.export(emptyList(), from, from.plusSeconds(300)) }
        val tooMany = (1..51).map { UUID(0, it.toLong()) }
        assertThrows(IllegalArgumentException::class.java) { service.export(tooMany, from, from.plusSeconds(300)) }
    }

    @Test
    fun `failure discards partial result and releases export permit`() {
        var fail = true
        val service = CandleExportService(object : CandleHistoryReader {
            override fun read(instrumentUid: UUID, from: Instant, to: Instant): List<HistoricalCandle> {
                if (fail && from > this@CandleExportServiceTest.from) throw IllegalStateException("provider failure")
                return listOf(candle(from))
            }
        })
        assertThrows(CandleExportUnavailableException::class.java) { service.export(uid, from, from.plusSeconds(172800)) }
        fail = false
        assertEquals(3, service.export(uid, from, from.plusSeconds(172800)).trimEnd().lines().size)
    }

    private fun candle(time: Instant, complete: Boolean = true) = HistoricalCandle(
        time, BigDecimal("1.25"), BigDecimal("1.25"), BigDecimal("1.25"), BigDecimal("1.25"), 10, complete
    )
}
