package ru.bolotov.tradebot.domain.repository

import org.springframework.data.jpa.repository.JpaRepository
import ru.bolotov.tradebot.domain.model.InstrumentFilterConfigEntity

interface InstrumentFilterConfigRepository : JpaRepository<InstrumentFilterConfigEntity, String>
