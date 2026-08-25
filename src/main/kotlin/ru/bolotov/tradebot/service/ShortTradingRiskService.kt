package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.broker.getInstrumentByUIDSync
import ru.bolotov.tradebot.broker.getMarginAttributesSync
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.ttech.piapi.core.InstrumentsServiceSync
import ru.ttech.piapi.core.UsersServiceSync
import java.math.BigDecimal

private val shortRiskLogger = KotlinLogging.logger {}

@Service
class ShortTradingRiskService(
    private val instrumentsService: InstrumentsServiceSync,
    private val usersService: UsersServiceSync,
    @Qualifier("sandboxEnabled") private val sandboxEnabled: Boolean,
    @Value("\${trading.short.min-margin-sufficiency}")
    private val minimumMarginSufficiency: Double
) {
    fun canOpenShort(accountId: String, instrumentId: String): ShortRiskCheck {
        if (sandboxEnabled) {
            return ShortRiskCheck.Rejected(
                "Шорты запрещены в песочнице: маржинальные показатели в ней не рассчитываются"
            )
        }

        return runCatching {
            val instrument = instrumentsService.getInstrumentByUIDSync(instrumentId).instrument
            if (!instrument.shortEnabledFlag || !instrument.apiTradeAvailableFlag) {
                return ShortRiskCheck.Rejected("Инструмент недоступен для открытия шорта через API")
            }

            val attributes = usersService.getMarginAttributesSync(accountId)
            val sufficiency = attributes.fundsSufficiencyLevel.toBigDecimal()
            val missingFunds = attributes.amountOfMissingFunds.toBigDecimal()

            when {
                missingFunds > BigDecimal.ZERO -> {
                    ShortRiskCheck.Rejected("Недостаточно средств для маржинальной позиции: $missingFunds RUB")
                }

                sufficiency < minimumMarginSufficiency.toBigDecimal() -> {
                    ShortRiskCheck.Rejected(
                        "Недостаточный запас маржи: $sufficiency, требуется не ниже $minimumMarginSufficiency"
                    )
                }

                else -> ShortRiskCheck.Allowed(
                    MarginSnapshot(
                        fundsSufficiencyLevel = sufficiency,
                        liquidPortfolio = attributes.liquidPortfolio.toBigDecimal(),
                        startingMargin = attributes.startingMargin.toBigDecimal(),
                        minimalMargin = attributes.minimalMargin.toBigDecimal(),
                        missingFunds = missingFunds
                    )
                )
            }
        }.onFailure { error ->
            shortRiskLogger.error(error) { "Не удалось проверить маржинальные риски для $instrumentId" }
        }.getOrElse { error ->
            ShortRiskCheck.Rejected("Маржинальные показатели недоступны: ${error.message}")
        }
    }

}

private fun MoneyValue.toBigDecimal(): BigDecimal = BigDecimal.valueOf(units)
    .add(BigDecimal.valueOf(nano.toLong(), 9))

private fun Quotation.toBigDecimal(): BigDecimal = BigDecimal.valueOf(units)
    .add(BigDecimal.valueOf(nano.toLong(), 9))

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
