package ru.bolotov.tradebot.domain.repository

import org.springframework.data.jpa.repository.JpaRepository
import ru.bolotov.tradebot.domain.model.StrategyConfig
import ru.bolotov.tradebot.domain.model.StrategyType

interface StrategyConfigRepository : JpaRepository<StrategyConfig, String> {
    fun findByType(type: StrategyType): StrategyConfig?
}