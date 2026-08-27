package ru.bolotov.tradebot.service.data

/** Нормализованный сигнал стратегии для запроса AI. */
data class AiSignalContext(
    val direction: String,
    val confidence: Double,
    val reason: String?
)
