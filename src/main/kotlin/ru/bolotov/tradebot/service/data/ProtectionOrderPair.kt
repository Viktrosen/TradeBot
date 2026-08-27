package ru.bolotov.tradebot.service.data

/** Идентификаторы стоп-лосса и тейк-профита, созданных для одной позиции. */
data class ProtectionOrderPair(
    val stopLossOrderId: String,
    val takeProfitOrderId: String
)
