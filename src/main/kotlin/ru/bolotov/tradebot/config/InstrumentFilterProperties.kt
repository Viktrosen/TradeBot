package ru.bolotov.tradebot.config

import org.springframework.stereotype.Component

@Component
class InstrumentFilterProperties {
    var minDailyVolume: Long = 100_000
    var minVolatility: Double = 3.0
    var maxVolatility: Double = 15.0
    var maxCount: Int = 10
}