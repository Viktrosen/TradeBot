package ru.bolotov.tradebot.service.data

/** Результат ручного закрытия одной позиции через внутренний API. */
data class ManualCloseResult(
    val status: String,
    val closed: Boolean
)
