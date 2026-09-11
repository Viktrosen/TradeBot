package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.BotOperationEventType
import ru.bolotov.tradebot.domain.model.BotOperationLogEntity
import ru.bolotov.tradebot.domain.model.BotOperationLogLevel
import ru.bolotov.tradebot.domain.repository.BotOperationLogRepository
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

private val operationJournalLogger = KotlinLogging.logger {}

/**
 * Writes selected operational events both to the standard application log and
 * to a bounded, asynchronously persisted audit journal. Database failures must
 * never delay a trading decision or become a reason to stop the bot.
 */
@Service
class BotOperationJournal(
    private val repository: BotOperationLogRepository,
    private val objectMapper: ObjectMapper
) {
    private val pendingRecords = ArrayBlockingQueue<BotOperationLogEntity>(MAX_PENDING_RECORDS)
    private val droppedRecords = AtomicLong()
    private val lastPersistenceFailureWarningAt = AtomicLong()

    fun info(
        eventType: BotOperationEventType,
        message: String,
        instrumentId: String? = null,
        instrumentName: String? = null,
        positionId: String? = null,
        brokerOrderId: String? = null,
        correlationId: String? = null,
        context: Map<String, Any?> = emptyMap()
    ) = record(
        level = BotOperationLogLevel.INFO,
        eventType = eventType,
        message = message,
        instrumentId = instrumentId,
        instrumentName = instrumentName,
        positionId = positionId,
        brokerOrderId = brokerOrderId,
        correlationId = correlationId,
        context = context
    )

    fun warn(
        eventType: BotOperationEventType,
        message: String,
        consoleMessage: String = message,
        instrumentId: String? = null,
        instrumentName: String? = null,
        positionId: String? = null,
        context: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    ) = record(
        level = BotOperationLogLevel.WARN,
        eventType = eventType,
        message = message,
        consoleMessage = consoleMessage,
        instrumentId = instrumentId,
        instrumentName = instrumentName,
        positionId = positionId,
        context = context,
        error = error
    )

    fun error(
        eventType: BotOperationEventType,
        message: String,
        consoleMessage: String = message,
        instrumentId: String? = null,
        instrumentName: String? = null,
        positionId: String? = null,
        context: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    ) = record(
        level = BotOperationLogLevel.ERROR,
        eventType = eventType,
        message = message,
        consoleMessage = consoleMessage,
        instrumentId = instrumentId,
        instrumentName = instrumentName,
        positionId = positionId,
        context = context,
        error = error
    )

    @Scheduled(fixedDelayString = "\${bot.operation-journal.flush-delay-ms:1000}")
    fun flushPendingRecords() {
        val batch = buildList {
            repeat(MAX_BATCH_SIZE) {
                pendingRecords.poll()?.let(::add) ?: return@repeat
            }
        }
        if (batch.isEmpty()) return

        runCatching { repository.saveAll(batch) }
            .onFailure { error ->
                batch.forEach { record ->
                    if (!pendingRecords.offer(record)) droppedRecords.incrementAndGet()
                }
                logPersistenceFailure(error, batch.size)
            }
    }

    private fun record(
        level: BotOperationLogLevel,
        eventType: BotOperationEventType,
        message: String,
        consoleMessage: String = message,
        instrumentId: String? = null,
        instrumentName: String? = null,
        positionId: String? = null,
        brokerOrderId: String? = null,
        correlationId: String? = null,
        context: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    ) {
        when (level) {
            BotOperationLogLevel.INFO -> operationJournalLogger.info { consoleMessage }
            BotOperationLogLevel.WARN -> operationJournalLogger.warn(error) { consoleMessage }
            BotOperationLogLevel.ERROR -> operationJournalLogger.error(error) { consoleMessage }
        }

        val entity = BotOperationLogEntity(
            createdAt = Instant.now(),
            level = level,
            eventType = eventType,
            message = message.take(MESSAGE_MAX_LENGTH),
            instrumentId = instrumentId,
            instrumentName = instrumentName,
            positionId = positionId,
            brokerOrderId = brokerOrderId,
            correlationId = correlationId,
            contextJson = serializeContext(context),
            errorClass = error?.javaClass?.simpleName,
            errorMessage = error?.message?.take(ERROR_MESSAGE_MAX_LENGTH)
        )
        if (!pendingRecords.offer(entity)) {
            val dropped = droppedRecords.incrementAndGet()
            if (dropped == 1L || dropped % DROPPED_LOG_WARNING_INTERVAL == 0L) {
                operationJournalLogger.warn {
                    "Очередь операционного журнала заполнена; пропущено записей=$dropped"
                }
            }
        }
    }

    private fun serializeContext(context: Map<String, Any?>): String? =
        context.takeIf(Map<String, Any?>::isNotEmpty)
            ?.let { values -> runCatching { objectMapper.writeValueAsString(values) }.getOrNull() }
            ?.take(CONTEXT_MAX_LENGTH)

    private fun logPersistenceFailure(error: Throwable, batchSize: Int) {
        val now = System.currentTimeMillis()
        val previousWarningAt = lastPersistenceFailureWarningAt.get()
        if (now - previousWarningAt < PERSISTENCE_FAILURE_WARNING_INTERVAL_MS ||
            !lastPersistenceFailureWarningAt.compareAndSet(previousWarningAt, now)
        ) return

        operationJournalLogger.warn(error) {
            "Не удалось сохранить $batchSize записей операционного журнала; торговый цикл продолжен"
        }
    }

    private companion object {
        const val MAX_PENDING_RECORDS = 1_000
        const val MAX_BATCH_SIZE = 100
        const val MESSAGE_MAX_LENGTH = 2_000
        const val ERROR_MESSAGE_MAX_LENGTH = 1_000
        const val CONTEXT_MAX_LENGTH = 8_000
        const val DROPPED_LOG_WARNING_INTERVAL = 100L
        const val PERSISTENCE_FAILURE_WARNING_INTERVAL_MS = 60_000L
    }
}
