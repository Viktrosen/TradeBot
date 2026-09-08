package ru.bolotov.tradebot.service.data

import java.math.BigDecimal

/**
 * Брокерская защита позиции.
 *
 * Тейк-профит намеренно не размещается у брокера: две независимые встречные
 * стоп-заявки могут исполниться до отмены второй и перевернуть позицию.
 */
data class ProtectionOrderPair(
    val stopLossOrderId: String,
    val stopLossPrice: BigDecimal,
    val takeProfitOrderId: String? = null
)
