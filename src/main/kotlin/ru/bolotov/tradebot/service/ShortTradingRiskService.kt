package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.broker.getInstrumentByUIDSync
import ru.bolotov.tradebot.broker.getMarginAttributesSync
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.service.data.MarginSnapshot
import ru.bolotov.tradebot.service.data.ShortRiskCheck
import ru.ttech.piapi.core.InstrumentsServiceSync
import ru.ttech.piapi.core.UsersServiceSync
import java.math.BigDecimal

private val shortRiskLogger = KotlinLogging.logger {}

/** Проверяет доступность инструмента и достаточность маржи до открытия SHORT. */
@Service
class ShortTradingRiskService(
    private val instrumentsService: InstrumentsServiceSync,
    private val usersService: UsersServiceSync,
    @Qualifier("sandboxEnabled") private val sandboxEnabled: Boolean,
    @Value("\${trading.short.min-margin-sufficiency}")
    private val minimumMarginSufficiency: Double
) {
    /** Возвращает разрешение либо причину безопасного отказа в открытии короткой позиции. */
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
            val margin = MarginSnapshot(
                fundsSufficiencyLevel = attributes.fundsSufficiencyLevel.toBigDecimal(),
                liquidPortfolio = attributes.liquidPortfolio.toBigDecimal(),
                startingMargin = attributes.startingMargin.toBigDecimal(),
                minimalMargin = attributes.minimalMargin.toBigDecimal(),
                missingFunds = attributes.amountOfMissingFunds.toBigDecimal()
            )
            val instrumentName = instrument.ticker.ifBlank { instrumentId }

            when {
                margin.missingFunds > BigDecimal.ZERO -> {
                    ShortRiskCheck.Rejected(
                        "Недостаточно средств для маржинальной позиции: ${margin.missingFunds} RUB; " +
                            margin.describe()
                    )
                }

                margin.hasCurrentMarginLoad() &&
                    margin.fundsSufficiencyLevel < minimumMarginSufficiency.toBigDecimal() -> {
                    ShortRiskCheck.Rejected(
                        "Недостаточный запас маржи: ${margin.fundsSufficiencyLevel}, " +
                            "требуется не ниже $minimumMarginSufficiency; ${margin.describe()}"
                    )
                }

                else -> {
                    if (!margin.hasCurrentMarginLoad()) {
                        shortRiskLogger.info {
                            "Шорт $instrumentName: текущая маржинальная нагрузка отсутствует; " +
                                "порог fundsSufficiencyLevel не применяется. ${margin.describe()}; " +
                                "допустимый объём проверяется через sell_margin_limits брокера"
                        }
                    }
                    ShortRiskCheck.Allowed(margin)
                }
            }
        }.onFailure { error ->
            shortRiskLogger.error(error) { "Не удалось проверить маржинальные риски для $instrumentId" }
        }.getOrElse { error ->
            ShortRiskCheck.Rejected("Маржинальные показатели недоступны: ${error.message}")
        }
    }

    /** Формирует компактную диагностическую сводку без реквизитов счёта. */
    private fun MarginSnapshot.describe(): String =
        "ликвидный портфель=$liquidPortfolio, начальная маржа=$startingMargin, " +
            "минимальная маржа=$minimalMargin, уровень достаточности=$fundsSufficiencyLevel, " +
            "недостающие средства=$missingFunds"

}
