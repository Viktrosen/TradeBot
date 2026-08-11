package ru.bolotov.tradebot.service

sealed interface PositionSizingResult {
    data class Allowed(
        val positionSize: PositionSize
    ) : PositionSizingResult

    data class Rejected(
        val reason: PositionSizingRejection
    ) : PositionSizingResult
}

enum class PositionSizingRejection(
    val description: String
) {
    INSUFFICIENT_CAPITAL_FOR_MIN_POSITION(
        "остатка лимита капитала недостаточно для минимальной позиции"
    ),
    MAX_POSITIONS_REACHED("достигнуто максимальное количество позиций"),
    INVALID_MARKET_DATA("некорректная цена или размер лота"),
    RISK_LIMIT_EXCEEDED("минимальная позиция превышает риск на сделку"),
    CAPITAL_USAGE_LIMIT_EXCEEDED("исчерпан лимит загрузки капитала"),
    MAX_POSITION_SIZE_EXCEEDED("минимальная позиция превышает максимум позиции"),
    BROKER_LIMIT_EXCEEDED("лимиты брокера не позволяют достичь минимума позиции")
}
