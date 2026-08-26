package ru.bolotov.tradebot.service.data

data class AiFilterResult(
    val approved: Boolean,
    val explanation: String? = null,
    val confidence: Double? = null
)
