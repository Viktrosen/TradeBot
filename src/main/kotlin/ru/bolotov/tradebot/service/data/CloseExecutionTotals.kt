package ru.bolotov.tradebot.service.data

import java.math.BigDecimal

/** Суммарная стоимость и комиссия исполненных частей заявки закрытия позиции. */
data class CloseExecutionTotals(
    val totalValue: BigDecimal,
    val totalCommission: BigDecimal
)
