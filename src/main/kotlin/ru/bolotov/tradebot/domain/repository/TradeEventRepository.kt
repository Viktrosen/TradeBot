package ru.bolotov.tradebot.domain.repository

import org.springframework.data.jpa.repository.JpaRepository
import ru.bolotov.tradebot.domain.model.EventStatus
import ru.bolotov.tradebot.domain.model.TradeEvent
import java.time.Instant

interface TradeEventRepository : JpaRepository<TradeEvent, String> {
    fun findByStatus(status: EventStatus): List<TradeEvent>
    fun findByTimestampBetween(start: Instant, end: Instant): List<TradeEvent>
}