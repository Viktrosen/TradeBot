package ru.bolotov.tradebot.config

import ru.bolotov.tradebot.broker.*

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.ttech.piapi.core.InvestApi
import ru.ttech.piapi.core.InstrumentsServiceSync
import ru.ttech.piapi.core.MarketDataServiceSync
import ru.ttech.piapi.core.OperationsServiceSync
import ru.ttech.piapi.core.OrdersServiceSync
import ru.ttech.piapi.core.SandboxServiceSync
import ru.ttech.piapi.core.StopOrdersServiceSync
import ru.ttech.piapi.core.UsersServiceSync
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

@Configuration
class InvestApiConfig(
    @Value("\${invest.connector.token}") private val token: String,
    @Value("\${invest.connector.sandbox.enabled:false}") private val sandboxEnabled: Boolean,
    @Value("\${invest.connector.app-name:TradeBot}") private val appName: String
) {
    @Bean
    fun investApi(): InvestApi {
        val target = if (sandboxEnabled) SANDBOX_TARGET else PRODUCTION_TARGET
        val mode = if (sandboxEnabled) "ПЕСОЧНИЦА (Sandbox)" else "РЕАЛЬНЫЙ СЧЁТ (Production)"
        logger.info { "Создание клиента T-Bank Invest API: $mode, endpoint=$target" }
        return InvestApi.createApi(InvestApi.defaultChannel(token, appName, target))
    }

    @Bean(name = ["sandboxEnabled"])
    fun sandboxEnabled(): Boolean = sandboxEnabled

    @Bean fun ordersService(investApi: InvestApi): OrdersServiceSync = investApi.ordersServiceSync
    @Bean fun stopOrdersService(investApi: InvestApi): StopOrdersServiceSync = investApi.stopOrdersServiceSync
    @Bean fun marketDataService(investApi: InvestApi): MarketDataServiceSync = investApi.marketDataServiceSync
    @Bean fun instrumentsService(investApi: InvestApi): InstrumentsServiceSync = investApi.instrumentsServiceSync
    @Bean fun operationsService(investApi: InvestApi): OperationsServiceSync = investApi.operationsServiceSync
    @Bean fun usersService(investApi: InvestApi): UsersServiceSync = investApi.usersServiceSync
    @Bean fun sandboxService(investApi: InvestApi): SandboxServiceSync = investApi.sandboxServiceSync

    fun moneyValueToBigDecimal(moneyValue: MoneyValue?): BigDecimal? = moneyValue?.let {
        BigDecimal.valueOf(it.units).add(BigDecimal.valueOf(it.nano.toLong(), 9))
    }

    private companion object {
        const val PRODUCTION_TARGET = "invest-public-api.tbank.ru:443"
        const val SANDBOX_TARGET = "sandbox-invest-public-api.tbank.ru:443"
    }
}
