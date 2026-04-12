package ru.bolotov.tradebot.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.core.InvestApi
import ru.tinkoff.piapi.core.SandboxService
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}


@Configuration
class InvestApiConfig(
    @Value("\${invest.token}") private val token: String,
    @Value("\${invest.connector.sandbox.enabled:false}") private val sandboxEnabled: Boolean,
    @Value("\${invest.connector.app-name:TradeBot}") private val appName: String
) {

    @Bean
    fun investApi(): InvestApi {
        val api = if (sandboxEnabled) {
            logger.info { "Создание InvestApi для ПЕСОЧНИЦЫ (Sandbox)" }
            InvestApi.createSandbox(token, appName)
        } else {
            logger.info { "Создание InvestApi для РЕАЛЬНОГО СЧЕТА (Production)" }
            InvestApi.create(token, appName)
        }
        logger.info { "InvestApi успешно создан. Режим песочницы: $sandboxEnabled" }
        return api
    }

    @Bean(name = ["sandboxEnabled"])
    fun sandboxEnabled(): Boolean = sandboxEnabled

    @Bean
    fun ordersService(investApi: InvestApi) = investApi.ordersService

    @Bean
    fun marketDataService(investApi: InvestApi) = investApi.marketDataService

    @Bean
    fun instrumentsService(investApi: InvestApi) = investApi.instrumentsService

    @Bean
    fun operationsService(investApi: InvestApi) = investApi.operationsService

    @Bean
    fun usersService(investApi: InvestApi) = investApi.userService

    @Bean
    fun sandboxService(investApi: InvestApi): SandboxService = investApi.sandboxService

    fun moneyValueToBigDecimal(moneyValue: MoneyValue?): BigDecimal? {
        if (moneyValue == null) return null
        return BigDecimal.valueOf(moneyValue.units)
            .add(BigDecimal.valueOf(moneyValue.nano.toLong(), 9))
    }
}