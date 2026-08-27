package ru.bolotov.tradebot.strategy.data

import ru.tinkoff.piapi.contract.v1.HistoricCandle
import java.time.Instant

/** Кэш закрытых свечей одного инструмента для свечной стратегии. */
data class CachedCandles(
    val candles: List<HistoricCandle>,
    val loadedAt: Instant
)
