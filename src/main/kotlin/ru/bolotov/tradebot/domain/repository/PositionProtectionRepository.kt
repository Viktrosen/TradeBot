package ru.bolotov.tradebot.domain.repository

import org.springframework.data.jpa.repository.JpaRepository
import ru.bolotov.tradebot.domain.model.PositionProtectionEntity

interface PositionProtectionRepository : JpaRepository<PositionProtectionEntity, String> {
    fun findByPositionId(positionId: String): PositionProtectionEntity?
}
