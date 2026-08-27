package ru.bolotov.tradebot.service.data

/** Возможные решения AI-фильтра для переданного торгового сигнала. */
enum class AiAction {
    APPROVE,
    REJECT,
    HOLD;

    val description: String
        get() = when (this) {
            APPROVE -> "одобрено"
            REJECT -> "отклонено"
            HOLD -> "недостаточно данных"
        }
}
