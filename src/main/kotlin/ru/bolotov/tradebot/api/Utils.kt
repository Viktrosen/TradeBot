package ru.bolotov.tradebot.api

fun RiskConfigRequest.updateDescriptions(): List<String> = buildList {
    positionSizePercent?.let { add("positionSizePercent = ${"%.0f".format(it * 100)}%") }
    stopLossPercent?.let { add("stopLossPercent = ${"%.1f".format(it * 100)}%") }
    takeProfitPercent?.let { add("takeProfitPercent = ${"%.1f".format(it * 100)}%") }
    maxCapitalUsage?.let { add("maxCapitalUsage = ${"%.0f".format(it * 100)}%") }
    maxPositions?.let { add("maxPositions = $it") }
    brokerLimitUsage?.let { add("brokerLimitUsage = ${"%.0f".format(it * 100)}%") }
    minOrderCashBuffer?.let { add("minOrderCashBuffer = $it") }
    shortTradingEnabled?.let { add("shortTradingEnabled = $it") }
}
