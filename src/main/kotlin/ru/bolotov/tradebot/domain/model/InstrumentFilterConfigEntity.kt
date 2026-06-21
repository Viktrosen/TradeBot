package ru.bolotov.tradebot.domain.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "instrument_filter_config")
class InstrumentFilterConfigEntity(
    @Id
    var id: String = "current",

    @Column(name = "min_daily_volume")
    var minDailyVolume: Long = 100_000L,

    @Column(name = "min_volatility")
    var minVolatility: Double = 3.0,

    @Column(name = "max_volatility")
    var maxVolatility: Double = 15.0,

    @Column(name = "max_count")
    var maxCount: Int = 10,

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
)
