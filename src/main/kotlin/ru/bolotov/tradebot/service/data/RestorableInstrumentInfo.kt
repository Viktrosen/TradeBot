package ru.bolotov.tradebot.service.data

/** Метаданные инструмента, достаточные для восстановления позиции из портфеля брокера. */
data class RestorableInstrumentInfo(
    val ticker: String,
    val name: String,
    val instrumentType: String,
    val lotSize: Int
)
