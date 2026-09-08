package ru.bolotov.tradebot.service.data

import ru.bolotov.tradebot.domain.model.ProfitProtectionStage
import java.math.BigDecimal

sealed interface ProfitProtectionDecision {
    data object NoChange : ProfitProtectionDecision
    data class Updated(
        val exitPrice: BigDecimal,
        val stage: ProfitProtectionStage
    ) : ProfitProtectionDecision

    data class Exit(val exitPrice: BigDecimal) : ProfitProtectionDecision
}
