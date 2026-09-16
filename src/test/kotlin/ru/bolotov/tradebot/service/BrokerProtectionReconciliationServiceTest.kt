package ru.bolotov.tradebot.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import ru.bolotov.tradebot.domain.model.OrderDirection
import ru.bolotov.tradebot.domain.model.PositionSide
import ru.ttech.piapi.core.InvestApi
import ru.ttech.piapi.core.UsersServiceSync
import java.math.BigDecimal
import java.time.Instant

class BrokerProtectionReconciliationServiceTest {

    @Test
    fun `periodic reconciliation restores protection for position still open at broker`() = runBlocking {
        val portfolioSync = mock<BrokerPortfolioSyncService>()
        val protectionService = mock<PositionProtectionService>()
        val position = openLongPosition()
        `when`(portfolioSync.isBotTrackedPosition(position)).thenReturn(true)
        `when`(portfolioSync.getBrokerPositionQuantities("account"))
            .thenReturn(mapOf(position.instrumentId to BigDecimal.ONE))
        `when`(protectionService.loadBrokerSnapshot("account"))
            .thenReturn(BrokerProtectionSnapshot.empty())

        reconciliationService(portfolioSync, protectionService).reconcilePositions(
            accountId = "account",
            positions = listOf(position),
            reason = "периодическая сверка",
            onClosed = null
        )

        verify(protectionService).reconcileProtection("account", listOf(position))
    }

    @Test
    fun `reconciliation removes broker-absent tracked position without inventing pnl`() = runBlocking {
        val portfolioSync = mock<BrokerPortfolioSyncService>()
        val protectionService = mock<PositionProtectionService>()
        val lifecycleService = mock<PositionLifecycleService>()
        val position = openLongPosition()
        var removedPosition: OpenPosition? = null
        `when`(portfolioSync.isBotTrackedPosition(position)).thenReturn(true)
        `when`(portfolioSync.getBrokerPositionQuantities("account")).thenReturn(emptyMap())
        `when`(protectionService.loadBrokerSnapshot("account"))
            .thenReturn(BrokerProtectionSnapshot.empty())
        `when`(protectionService.findTriggeredProtection(position, BrokerProtectionSnapshot.empty()))
            .thenReturn(null)
        `when`(lifecycleService.recordBrokerReconciliationClose(position, BigDecimal.ZERO))
            .thenReturn(ClosePositionResult(position, closed = true, removeFromState = true))

        reconciliationService(portfolioSync, protectionService, lifecycleService).reconcilePositions(
            accountId = "account",
            positions = listOf(position),
            reason = "периодическая сверка",
            onClosed = { removedPosition = it }
        )

        verify(lifecycleService).recordBrokerReconciliationClose(position, BigDecimal.ZERO)
        assertEquals(position, removedPosition)
    }

    @Test
    fun `reconciliation removes untracked in-memory position even when broker has it`() = runBlocking {
        val portfolioSync = mock<BrokerPortfolioSyncService>()
        val protectionService = mock<PositionProtectionService>()
        val position = openLongPosition()
        var removedPosition: OpenPosition? = null
        `when`(portfolioSync.isBotTrackedPosition(position)).thenReturn(false)

        reconciliationService(portfolioSync, protectionService).reconcilePositions(
            accountId = "account",
            positions = listOf(position),
            reason = "периодическая сверка",
            onClosed = { removedPosition = it }
        )

        assertEquals(position, removedPosition)
    }

    private fun reconciliationService(
        portfolioSync: BrokerPortfolioSyncService,
        protectionService: PositionProtectionService,
        lifecycleService: PositionLifecycleService = mock<PositionLifecycleService>()
    ) = BrokerProtectionReconciliationService(
        investApi = mock<InvestApi>(),
        brokerPortfolioSyncService = portfolioSync,
        positionProtectionService = protectionService,
        positionLifecycleService = lifecycleService,
        orderExecutionService = mock<OrderExecutionService>(),
        usersService = mock<UsersServiceSync>(),
        portfolioSnapshotService = mock<PortfolioSnapshotService>(),
        eventPublisherService = mock<EventPublisherService>(),
        sandboxEnabled = false,
        reconciliationDelayMs = 1_000,
        minimumMarginSufficiency = 1.25
    )

    private fun openLongPosition() = OpenPosition(
        positionId = "position",
        instrumentId = "instrument",
        instrumentName = "Инструмент",
        direction = OrderDirection.BUY,
        side = PositionSide.LONG,
        entryPrice = BigDecimal("100"),
        quantity = 1,
        entryTime = Instant.EPOCH
    )
}
