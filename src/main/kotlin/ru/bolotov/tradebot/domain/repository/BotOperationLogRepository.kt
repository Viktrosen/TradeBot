package ru.bolotov.tradebot.domain.repository

import org.springframework.data.jpa.repository.JpaRepository
import ru.bolotov.tradebot.domain.model.BotOperationLogEntity

interface BotOperationLogRepository : JpaRepository<BotOperationLogEntity, String>
