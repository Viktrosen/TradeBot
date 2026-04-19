package ru.bolotov.tradebot.domain.repository

import org.springframework.data.jpa.repository.JpaRepository
import ru.bolotov.tradebot.domain.model.RiskConfigEntity

interface RiskConfigRepository : JpaRepository<RiskConfigEntity, String>