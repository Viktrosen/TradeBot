package ru.bolotov.tradebot.service.data

import ru.bolotov.tradebot.service.OpenPosition
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.Signal

sealed class BotSignal {
    data class Trade(
        val marketData: MarketData,
        val signal: Signal,
        val strategyName: String,
        val strategyExplanation: String,
        val aiResult: AiFilterResult
    ) : BotSignal()

    data class Close(
        val position: OpenPosition,
        val reason: CloseReason,
        val aiResult: AiFilterResult? = null,
        val sourceCandleKey: String? = null
    ) : BotSignal()
}

enum class CloseReason(val eventReason: String) {
    STOP_LOSS("STOP_LOSS"),
    TAKE_PROFIT("TAKE_PROFIT"),
    STRATEGY_SIGNAL("SIGNAL_CLOSE")
}
