package ru.bolotov.tradebot.service.data

import java.math.BigDecimal

/** Рассчитанные уровни защитных заявок позиции. */
data class ProtectionPrices(
    val stopLossPrice: BigDecimal,
    val takeProfitPrice: BigDecimal
)
