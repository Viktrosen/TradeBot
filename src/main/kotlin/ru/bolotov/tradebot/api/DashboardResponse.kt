package ru.bolotov.tradebot.api

import java.math.BigDecimal

data class DashboardResponse(
    val openPositions: List<OpenPositionResponse>,
    val closedTrades: List<ClosedTradeResponse>,
    val metrics: DashboardMetricsResponse
)

data class OpenPositionResponse(
    val positionId: String,
    val instrumentId: String,
    val instrumentName: String,
    val direction: String,
    val positionSide: String,
    val entryPrice: BigDecimal,
    val currentPrice: BigDecimal?,
    val unrealizedPnl: BigDecimal?,
    val quantity: Long,
    val lotSize: Int,
    val entryCommission: BigDecimal,
    val entryTime: String,
    val aiExplanation: String? = null
)

data class ClosedTradeResponse(
    val positionId: String,
    val instrumentId: String,
    val instrumentName: String,
    val direction: String,
    val positionSide: String,
    val entryPrice: BigDecimal,
    val entryTime: String?,
    val closePrice: BigDecimal,
    val quantity: Long,
    val lotSize: Int,
    val realizedPnl: BigDecimal,
    val closedAt: String,
    val closeExplanation: String? = null
)

data class DashboardMetricsResponse(
    val realizedPnl: BigDecimal,
    val dailyPnl: BigDecimal,
    val winRate: Double,
    val closedTradesCount: Int,
    val availableCash: BigDecimal
)
