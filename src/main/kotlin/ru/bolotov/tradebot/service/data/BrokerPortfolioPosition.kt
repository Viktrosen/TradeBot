package ru.bolotov.tradebot.service.data

import java.math.BigDecimal

/** Позиция брокерского портфеля в единицах инструмента, а не лотах. */
data class BrokerPortfolioPosition(
    val instrumentId: String,
    val quantity: BigDecimal,
    val averagePositionPrice: BigDecimal
)
