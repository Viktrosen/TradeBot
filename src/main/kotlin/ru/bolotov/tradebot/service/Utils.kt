package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.domain.model.PositionSide
import ru.bolotov.tradebot.domain.model.OrderDirection as TradeEventOrderDirection
import ru.bolotov.tradebot.service.data.AiFilterResult
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.OrderDirection as StrategyOrderDirection
import ru.bolotov.tradebot.strategy.Signal
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.contract.v1.Quotation
import java.math.BigDecimal
import java.math.RoundingMode

fun String.withAiExplanation(aiResult: AiFilterResult): String =
    aiResult.toEventExplanation()?.let { "$this\n$it" } ?: this

fun AiFilterResult.toEventExplanation(): String? = explanation?.let { reason ->
    "AI: $reason${confidence?.let { "; уверенность: ${(it * 100).toInt()}%" }.orEmpty()}"
}

fun String.aiExplanation(): String? =
    lineSequence().firstOrNull { it.startsWith("AI: ") }?.removePrefix("AI: ")

val Signal.actionDescription: String
    get() = when (direction) {
        StrategyOrderDirection.BUY -> "покупки"
        StrategyOrderDirection.SELL -> "продажи"
        StrategyOrderDirection.HOLD -> "сделки"
    }

fun OpenPosition.currentPnlPercent(currentPrice: BigDecimal): Double =
    priceDifference(currentPrice)
        .divide(entryPrice, PNL_SCALE, RoundingMode.HALF_UP)
        .toDouble()

fun OpenPosition.hasUnrealizedLoss(currentPrice: BigDecimal): Boolean =
    priceDifference(currentPrice) < BigDecimal.ZERO

fun OpenPosition.priceDifference(currentPrice: BigDecimal): BigDecimal = when (side) {
    PositionSide.LONG -> currentPrice - entryPrice
    PositionSide.SHORT -> entryPrice - currentPrice
}

val MarketData.signalCandleKey: String?
    get() = candlestickPattern?.candleKey ?: strategyCandleKey

fun Signal.toPositionSideOrNull(): PositionSide? = when (direction) {
    StrategyOrderDirection.BUY -> PositionSide.LONG
    StrategyOrderDirection.SELL -> PositionSide.SHORT
    StrategyOrderDirection.HOLD -> null
}

fun PositionSide.openDirection(): TradeEventOrderDirection = when (this) {
    PositionSide.LONG -> TradeEventOrderDirection.BUY
    PositionSide.SHORT -> TradeEventOrderDirection.SELL
}

fun MoneyValue.toBigDecimal(): BigDecimal = BigDecimal.valueOf(units)
    .add(BigDecimal.valueOf(nano.toLong(), NANO_SCALE))

fun Quotation.toBigDecimal(): BigDecimal = BigDecimal.valueOf(units)
    .add(BigDecimal.valueOf(nano.toLong(), NANO_SCALE))

private const val PNL_SCALE = 8
private const val NANO_SCALE = 9
