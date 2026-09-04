package ru.bolotov.tradebot.service.data

/**
 * Брокерская защита позиции.
 *
 * Тейк-профит намеренно не размещается у брокера: две независимые встречные
 * стоп-заявки могут исполниться до отмены второй и перевернуть позицию.
 */
data class ProtectionOrderPair(
    val stopLossOrderId: String,
    val takeProfitOrderId: String? = null
)
