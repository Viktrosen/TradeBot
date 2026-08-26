package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.bolotov.tradebot.broker.closeAccountSync
import ru.bolotov.tradebot.broker.getAccountsSync
import ru.bolotov.tradebot.broker.getPortfolioSync
import ru.bolotov.tradebot.broker.openAccountSync
import ru.bolotov.tradebot.broker.payInSync
import ru.tinkoff.piapi.contract.v1.Account
import ru.tinkoff.piapi.contract.v1.AccountStatus
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.ttech.piapi.core.OperationsServiceSync
import ru.ttech.piapi.core.SandboxServiceSync
import ru.ttech.piapi.core.UsersServiceSync
import java.math.BigDecimal

private val accountLogger = KotlinLogging.logger {}

@Service
class BrokerAccountService(
    private val operationsService: OperationsServiceSync,
    private val usersService: UsersServiceSync,
    private val sandboxService: SandboxServiceSync,
    @Qualifier("sandboxEnabled") private val sandboxEnabled: Boolean,
    @Value("\${broker.account.name:}") private val productionAccountName: String
) {

    suspend fun initializeAccount(): String? {
        return try {
            if (sandboxEnabled) {
                initializeSandboxAccount()
            } else {
                initializeProductionAccount()
            }
        } catch (e: Exception) {
            accountLogger.error(e) { "Ошибка получения или создания брокерского счёта" }
            null
        }
    }

    private fun initializeProductionAccount(): String? {
        val accountName = productionAccountName.trim()
        if (accountName.isBlank()) {
            accountLogger.error {
                "Не задан BROKER_ACCOUNT_NAME: для реального режима бот не выбирает счёт автоматически"
            }
            return null
        }

        val accounts = usersService.getAccountsSync()
        logAvailableProductionAccounts(accounts)
        val matches = accounts.filter { account ->
            account.status == AccountStatus.ACCOUNT_STATUS_OPEN && account.name.trim() == accountName
        }

        return when (matches.size) {
            0 -> {
                accountLogger.error { "Открытый реальный счёт с именем '$accountName' не найден" }
                null
            }

            1 -> matches.single().id.also { accountId ->
                accountLogger.info { "Используется реальный счёт '$accountName': $accountId" }
            }

            else -> {
                accountLogger.error {
                    "Найдено ${matches.size} открытых реальных счетов с именем '$accountName'; " +
                        "бот не будет выбирать счёт неоднозначно"
                }
                null
            }
        }
    }

    private fun logAvailableProductionAccounts(accounts: List<Account>) {
        if (accounts.isEmpty()) {
            accountLogger.warn { "T-Invest не вернул доступных реальных счетов" }
            return
        }

        accountLogger.info {
            "Доступные реальные счета: " + accounts.joinToString { account ->
                "id=${account.id}, имя='${account.name}', тип=${account.type}, статус=${account.status}"
            }
        }
    }

    private fun initializeSandboxAccount(): String? {
        val accountId = sandboxService.getAccountsSync().firstOrNull()?.id ?: run {
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
            val amount = portfolio.totalAmountCurrencies
            val currentBalance = BigDecimal.valueOf(amount.units)
                .add(BigDecimal.valueOf(amount.nano.toLong(), 9))
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
