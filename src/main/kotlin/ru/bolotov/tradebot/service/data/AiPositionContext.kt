package ru.bolotov.tradebot.service.data

import ru.bolotov.tradebot.service.OpenPosition
import ru.bolotov.tradebot.service.currentPnlPercent
import java.math.BigDecimal

/** Контекст уже открытой позиции для AI-проверки сигнала её закрытия. */
data class AiPositionContext(
    val positionSide: String,
    val entryPrice: BigDecimal,
    val currentPnlPercent: Double,
    val stopLossPrice: BigDecimal?,
    val openedAt: String
) {
    companion object {
        fun from(position: OpenPosition, currentPrice: BigDecimal) = AiPositionContext(
            positionSide = position.side.name,
            entryPrice = position.entryPrice,
            currentPnlPercent = position.currentPnlPercent(currentPrice),
            stopLossPrice = position.stopLossPrice,
            openedAt = position.entryTime.toString()
        )
    }
}
