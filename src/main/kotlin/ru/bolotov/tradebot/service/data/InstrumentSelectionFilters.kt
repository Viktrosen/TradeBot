package ru.bolotov.tradebot.service.data

/** Сохранённые ограничения отбора инструментов перед очередным ресканом. */
data class InstrumentSelectionFilters(
    val minDailyVolume: Long,
    val minVolatility: Double,
    val maxVolatility: Double,
    val maxCount: Int
)
