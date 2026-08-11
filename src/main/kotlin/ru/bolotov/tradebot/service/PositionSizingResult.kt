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
    POSITION_SIZE_TOO_SMALL_FOR_LOT("размера позиции недостаточно для покупки одного лота"),
    MAX_POSITIONS_REACHED("достигнуто максимальное количество позиций"),
    INVALID_MARKET_DATA("некорректная цена или размер лота"),
    CAPITAL_USAGE_LIMIT_EXCEEDED("исчерпан лимит загрузки капитала"),
    BROKER_LIMIT_EXCEEDED("лимиты брокера не позволяют купить один лот")
}
