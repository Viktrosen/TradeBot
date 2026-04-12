package ru.bolotov.tradebot.domain.repository

import org.springframework.data.jpa.repository.JpaRepository
import ru.bolotov.tradebot.domain.model.PortfolioSnapshot
import java.time.Instant

interface PortfolioSnapshotRepository : JpaRepository<PortfolioSnapshot, String> {
    fun findTopByOrderByTimestampDesc(): PortfolioSnapshot?
    fun findByTimestampBetween(start: Instant, end: Instant): List<PortfolioSnapshot>
}