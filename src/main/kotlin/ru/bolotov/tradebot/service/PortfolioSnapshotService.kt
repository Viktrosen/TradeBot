package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.PortfolioSnapshot
import ru.bolotov.tradebot.domain.repository.PortfolioSnapshotRepository
import ru.tinkoff.piapi.core.OperationsService
import java.math.BigDecimal

private val portfolioSnapshotLogger = KotlinLogging.logger {}

@Service
class PortfolioSnapshotService(
    private val operationsService: OperationsService,
    private val portfolioSnapshotRepository: PortfolioSnapshotRepository,
    private val objectMapper: ObjectMapper,
    private val eventPublisherService: EventPublisherService
) {
    fun getLatestAvailableCash(): BigDecimal =
        portfolioSnapshotRepository.findTopByOrderByTimestampDesc()?.availableCash ?: BigDecimal.ZERO


    suspend fun takeSnapshot(accountId: String?) {
        try {
            if (accountId == null) {
                portfolioSnapshotLogger.warn { "Снимок портфеля пропущен: брокерский счёт не выбран" }
                return
            }

            val portfolio = operationsService.getPortfolioSync(accountId)
            val positions = operationsService.getPositionsSync(accountId)
            val total = portfolio.totalAmountPortfolio?.value ?: BigDecimal.ZERO
            val moneyRub = positions.money.firstOrNull { it.currency.equals("rub", ignoreCase = true) }
            val blockedRub = positions.blocked.firstOrNull { it.currency.equals("rub", ignoreCase = true) }
            val availableCash = moneyRub?.value ?: BigDecimal.ZERO
            val blockedCash = blockedRub?.value ?: BigDecimal.ZERO
            val cash = if (moneyRub != null || blockedRub != null) {
                availableCash + blockedCash
            } else {
                portfolio.totalAmountCurrencies?.value ?: BigDecimal.ZERO
            }

            val positionsJson = objectMapper.writeValueAsString(
                portfolio.positions.map { pos ->
                    val currentPrice = pos.currentPrice?.value ?: BigDecimal.ZERO
                    mapOf(
                        "instrumentId" to pos.instrumentUid,
                        "figi" to pos.figi,
                        "instrumentType" to pos.instrumentType,
                        "quantity" to pos.quantity,
                        "averagePrice" to (pos.averagePositionPrice?.value ?: BigDecimal.ZERO),
                        "currentPrice" to currentPrice,
                        "positionValue" to (currentPrice * pos.quantity),
                        "expectedYield" to pos.expectedYield,
                        "blocked" to pos.isBlocked,
                        "blockedLots" to pos.blockedLots
                    )
                }
            )

            portfolioSnapshotRepository.save(
                PortfolioSnapshot(
                    totalValue = total,
                    cashBalance = cash,
                    blockedCash = blockedCash,
                    availableCash = availableCash,
                    currency = "rub",
                    positions = positionsJson
                )
            )
            eventPublisherService.publishPortfolioChanged()
        } catch (e: Exception) {
            portfolioSnapshotLogger.error(e) { "Ошибка сохранения снимка портфеля" }
        }
    }
}
