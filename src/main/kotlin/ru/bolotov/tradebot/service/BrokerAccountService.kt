package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.core.OperationsService
import ru.tinkoff.piapi.core.SandboxService
import ru.tinkoff.piapi.core.UsersService
import java.math.BigDecimal

private val accountLogger = KotlinLogging.logger {}

@Service
class BrokerAccountService(
    private val operationsService: OperationsService,
    private val usersService: UsersService,
    private val sandboxService: SandboxService,
    @Qualifier("sandboxEnabled") private val sandboxEnabled: Boolean
) {

    suspend fun initializeAccount(): String? {
        return try {
            if (sandboxEnabled) {
                initializeSandboxAccount()
            } else {
                val accountId = usersService.getAccountsSync().firstOrNull()?.id
                accountLogger.info { "Используется реальный счёт: $accountId" }
                accountId
            }
        } catch (e: Exception) {
            accountLogger.error(e) { "Ошибка получения или создания брокерского счёта" }
            null
        }
    }

    private fun initializeSandboxAccount(): String? {
        val accountId = sandboxService.accountsSync.firstOrNull()?.id ?: run {
            closeAllSandboxAccounts()
            sandboxService.openAccountSync().also {
                accountLogger.info { "Создан новый sandbox-счёт: $it" }
            }
        }

        accountLogger.info { "Используется sandbox-счёт: $accountId" }
        ensureSandboxBalance(accountId)
        return accountId
    }

    private fun ensureSandboxBalance(accountId: String) {
        try {
            val portfolio = operationsService.getPortfolioSync(accountId)
            val currentBalance = portfolio.totalAmountCurrencies?.value ?: BigDecimal.ZERO
            if (currentBalance >= SANDBOX_MIN_BALANCE) return

            sandboxService.payInSync(
                accountId,
                MoneyValue.newBuilder()
                    .setCurrency("rub")
                    .setUnits(SANDBOX_MIN_BALANCE.toLong())
                    .setNano(0)
                    .build()
            )
            accountLogger.info { "Sandbox-счёт пополнен на $SANDBOX_MIN_BALANCE RUB" }
        } catch (e: Exception) {
            accountLogger.error(e) { "Ошибка проверки или пополнения sandbox-счёта" }
        }
    }

    fun closeAllSandboxAccounts() {
        try {
            val accounts = sandboxService.getAccountsSync()
            if (accounts.isEmpty()) {
                accountLogger.info { "Нет активных sandbox-счетов для закрытия" }
                return
            }

            accountLogger.info { "Найдено ${accounts.size} sandbox-счетов. Начинаем закрытие..." }
            accounts.forEach { account ->
                try {
                    sandboxService.closeAccountSync(account.id)
                    accountLogger.info { "Закрыт sandbox-счёт: ${account.id}" }
                } catch (e: Exception) {
                    accountLogger.warn(e) { "Не удалось закрыть sandbox-счёт ${account.id}: ${e.message}" }
                }
            }
            accountLogger.info { "Завершено закрытие sandbox-счетов" }
        } catch (e: Exception) {
            accountLogger.error(e) { "Ошибка при закрытии sandbox-счетов" }
        }
    }

    private companion object {
        val SANDBOX_MIN_BALANCE: BigDecimal = BigDecimal.valueOf(50_000)
    }
}

