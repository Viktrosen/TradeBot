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
    @Value("\${trading.short.min-margin-sufficiency:0.3}")
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

            // Комплексная проверка через метод MarginSnapshot
            if (!margin.canOpenNewPosition(minimumMarginSufficiency.toBigDecimal())) {
                val reason = when {
                    !margin.hasCurrentMarginLoad() -> "невозможно определить маржинальную нагрузку"
                    margin.missingFunds > BigDecimal.ZERO -> "недостаточно средств: ${margin.missingFunds} RUB"
                    else -> "недостаточный запас маржи: ${margin.fundsSufficiencyLevel}, требуется >= $minimumMarginSufficiency"
                }
                shortRiskLogger.warn {
                    "Шорт $instrumentName отклонён: $reason. ${margin.describe()}"
                }
                return ShortRiskCheck.Rejected(
                    "Маржинальные показатели не позволяют открыть шорт: $reason. ${margin.describe()}"
                )
            }

            shortRiskLogger.info {
                "Шорт $instrumentName разрешён: маржинальные показатели в норме. ${margin.describe()}"
            }
            return ShortRiskCheck.Allowed(margin)

        }.onFailure { error ->
            shortRiskLogger.error(error) { "Не удалось проверить маржинальные риски для $instrumentId" }
        }.getOrElse { error ->
            ShortRiskCheck.Rejected("Маржинальные показатели недоступны: ${error.message}")
        }
    }
}