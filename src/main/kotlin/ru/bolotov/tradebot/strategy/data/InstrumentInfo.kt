package ru.bolotov.tradebot.strategy.data

/** Минимальные метаданные инструмента, необходимые для расчёта рыночных данных. */
data class InstrumentInfo(
    val ticker: String,
    val name: String,
    val lotSize: Int
)
