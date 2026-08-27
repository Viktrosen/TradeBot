package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.broker.*

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.domain.model.PortfolioSnapshot
import ru.bolotov.tradebot.domain.repository.PortfolioSnapshotRepository
import ru.ttech.piapi.core.OperationsServiceSync
import java.math.BigDecimal

private val portfolioSnapshotLogger = KotlinLogging.logger {}

/** Сохраняет снимки портфеля и публикует изменения баланса для клиента. */
@Service
class PortfolioSnapshotService(
    private val operationsService: OperationsServiceSync,
    private val portfolioSnapshotRepository: PortfolioSnapshotRepository,
    private val objectMapper: ObjectMapper,
    private val eventPublisherService: EventPublisherService
) {
    /** Возвращает последние известные свободные деньги без синхронного вызова брокера. */
    fun getLatestAvailableCash(): BigDecimal =
        portfolioSnapshotRepository.findTopByOrderByTimestampDesc()?.availableCash ?: BigDecimal.ZERO


    /** Запрашивает портфель у брокера, сохраняет его снимок и отправляет обновление клиенту. */
    suspend fun takeSnapshot(accountId: String?) {
        try {
            if (accountId == null) {
                portfolioSnapshotLogger.warn { "Снимок портфеля пропущен: брокерский счёт не выбран" }
                return
            }

            val portfolio = operationsService.getPortfolioSync(accountId)
            val positions = operationsService.getPositionsSync(accountId)
            val total = portfolio.totalAmountPortfolio.toBigDecimal()
            val moneyRub = positions.moneyList.firstOrNull { it.currency.equals("rub", ignoreCase = true) }
            val blockedRub = positions.blockedList.firstOrNull { it.currency.equals("rub", ignoreCase = true) }
            val availableCash = moneyRub?.toBigDecimal() ?: BigDecimal.ZERO
            val blockedCash = blockedRub?.toBigDecimal() ?: BigDecimal.ZERO
            val cash = if (moneyRub != null || blockedRub != null) {
                availableCash + blockedCash
            } else {
                portfolio.totalAmountCurrencies.toBigDecimal()
            }

            val positionsJson = objectMapper.writeValueAsString(
                portfolio.positionsList.map { pos ->
                    val currentPrice = pos.currentPrice.toBigDecimal()
                    mapOf(
                        "instrumentId" to pos.instrumentUid,
                        "figi" to pos.figi,
                        "instrumentType" to pos.instrumentType,
                        "quantity" to pos.quantity.toBigDecimal(),
                        "averagePrice" to pos.averagePositionPrice.toBigDecimal(),
                        "currentPrice" to currentPrice,
                        "positionValue" to currentPrice.multiply(pos.quantity.toBigDecimal()),
                        "expectedYield" to pos.expectedYield.toBigDecimal(),
                        "blocked" to pos.blocked,
                        "blockedLots" to pos.blockedLots.toBigDecimal()
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
            eventPublisherService.publishPortfolioChanged(
                totalValue = total,
                availableCash = availableCash,
                blockedCash = blockedCash
            )
        } catch (e: Exception) {
            portfolioSnapshotLogger.error(e) { "Ошибка сохранения снимка портфеля" }
        }
    }
}
