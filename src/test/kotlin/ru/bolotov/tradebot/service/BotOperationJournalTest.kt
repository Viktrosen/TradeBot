package ru.bolotov.tradebot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import ru.bolotov.tradebot.domain.model.BotOperationEventType
import ru.bolotov.tradebot.domain.model.BotOperationLogEntity
import ru.bolotov.tradebot.domain.repository.BotOperationLogRepository

class BotOperationJournalTest {

    @Test
    fun `persists a structured record asynchronously after writing the application log`() {
        val repository = mock<BotOperationLogRepository>()
        var persistedRecords: Iterable<BotOperationLogEntity>? = null
        `when`(repository.saveAll(anyList<BotOperationLogEntity>())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[0] as Iterable<BotOperationLogEntity>).also {
                persistedRecords = it
            }
        }
        val journal = BotOperationJournal(repository, jacksonObjectMapper())

        journal.info(
            eventType = BotOperationEventType.PROTECTION_RESTORED,
            message = "Защита восстановлена",
            instrumentId = "instrument",
            positionId = "position",
            context = mapOf("source" to "reconciliation")
        )
        journal.flushPendingRecords()

        verify(repository).saveAll(anyList<BotOperationLogEntity>())
        val entry = requireNotNull(persistedRecords).single()
        assertEquals(BotOperationEventType.PROTECTION_RESTORED, entry.eventType)
        assertEquals("instrument", entry.instrumentId)
        assertEquals("position", entry.positionId)
        assertEquals("{\"source\":\"reconciliation\"}", entry.contextJson)
    }
}
