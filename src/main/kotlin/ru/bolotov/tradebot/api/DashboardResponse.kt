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
    val entryPrice: BigDecimal,
    val quantity: Long,
    val lotSize: Int,
    val entryCommission: BigDecimal,
    val entryTime: String
)

data class ClosedTradeResponse(
    val positionId: String,
    val instrumentId: String,
    val instrumentName: String,
    val direction: String,
    val closePrice: BigDecimal,
    val quantity: Long,
    val lotSize: Int,
    val realizedPnl: BigDecimal,
    val closedAt: String
)

data class DashboardMetricsResponse(
    val realizedPnl: BigDecimal,
    val dailyPnl: BigDecimal,
    val winRate: Double,
    val closedTradesCount: Int
)
