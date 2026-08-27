package ru.bolotov.tradebot.service.data

import ru.bolotov.tradebot.service.OpenPosition

/** Итог синхронизации локального состояния позиций с портфелем брокера. */
data class BrokerPortfolioRestoreResult(
    val positions: Map<String, OpenPosition>,
    val instrumentIds: List<String>
)
