package ru.bolotov.tradebot.domain.repository

import org.springframework.data.jpa.repository.JpaRepository
import ru.bolotov.tradebot.domain.model.EventStatus
import ru.bolotov.tradebot.domain.model.TradeEvent
import java.time.Instant

interface TradeEventRepository : JpaRepository<TradeEvent, String> {

    fun findByInstrumentId(instrumentId: String): List<TradeEvent>

    fun findByStatus(status: ru.bolotov.tradebot.domain.model.EventStatus): List<TradeEvent>

    fun findByCreatedAtBetween(start: Instant, end: Instant): List<TradeEvent>  // ← ИСПРАВЛЕНО

    fun findByEventType(eventType: ru.bolotov.tradebot.domain.model.EventType): List<TradeEvent>
}