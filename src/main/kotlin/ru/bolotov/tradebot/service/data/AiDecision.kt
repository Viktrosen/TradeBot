package ru.bolotov.tradebot.service.data

/** Структурированный ответ AI-модели после проверки торгового сигнала. */
data class AiDecision(
    val action: AiAction,
    val confidence: Double,
    val reason: String
)
