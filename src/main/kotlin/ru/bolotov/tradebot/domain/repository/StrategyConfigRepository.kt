package ru.bolotov.tradebot.domain.repository

import org.springframework.data.jpa.repository.JpaRepository
import ru.bolotov.tradebot.domain.model.StrategyConfig

interface StrategyConfigRepository : JpaRepository<StrategyConfig, String>