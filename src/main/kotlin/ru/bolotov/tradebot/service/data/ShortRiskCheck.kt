package ru.bolotov.tradebot.service.data

import java.math.BigDecimal

sealed interface ShortRiskCheck {
    data class Allowed(val margin: MarginSnapshot) : ShortRiskCheck
    data class Rejected(val reason: String) : ShortRiskCheck
}

data class MarginSnapshot(
    val fundsSufficiencyLevel: BigDecimal,
    val liquidPortfolio: BigDecimal,
    val startingMargin: BigDecimal,
    val minimalMargin: BigDecimal,
    val missingFunds: BigDecimal
)
