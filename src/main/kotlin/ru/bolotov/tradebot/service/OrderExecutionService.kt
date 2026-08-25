package ru.bolotov.tradebot.service

import ru.bolotov.tradebot.broker.*

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.contract.v1.OrderDirection as TinkoffOrderDirection
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus
import ru.tinkoff.piapi.contract.v1.OrderType
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.ttech.piapi.core.OrdersServiceSync
import java.math.BigDecimal
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class OrderExecutionService(
    private val ordersService: OrdersServiceSync
) {

    suspend fun placeOrder(
        accountId: String,
        instrumentId: String,
        quantity: Long,
        price: BigDecimal,
        direction: String,
        isMarginTrade: Boolean = false
    ): OrderResult {
        return try {
            val tinkoffDirection = if (direction == "BUY")
                TinkoffOrderDirection.ORDER_DIRECTION_BUY
            else TinkoffOrderDirection.ORDER_DIRECTION_SELL

            // Преобразуем BigDecimal в Quotation
            val quotation = quotationFromBigDecimal(price)

            // Генерируем уникальный идентификатор заявки для идемпотентности
            val orderId = UUID.randomUUID().toString()

            val order = ordersService.postOrderSync(
                instrumentId,
                quantity,
                quotation,
                tinkoffDirection,
                accountId,
                OrderType.ORDER_TYPE_LIMIT,
                orderId,
                isMarginTrade
            )
            logger.info { "Заявка отправлена: $order" }
            OrderResult(
                success = true,
                orderId = order.orderId,
                executedPrice = moneyValueToBigDecimal(order.initialSecurityPrice),
                executedOrderAmount = moneyValueToBigDecimal(order.executedOrderPrice),
                totalOrderAmount = moneyValueToBigDecimal(order.totalOrderAmount),
                executedCommission = moneyValueToBigDecimal(order.executedCommission),
                lotsRequested = order.lotsRequested,
                lotsExecuted = order.lotsExecuted,
                executionStatus = order.executionReportStatus.name
            )
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при выставлении заявки" }
            OrderResult(success = false, error = e.message)
        }
    }

    suspend fun placeMarketOrder(
        accountId: String,
        instrumentId: String,
        quantity: Long,
        direction: String,
        isMarginTrade: Boolean = false
    ): OrderResult {
        return try {
            val tinkoffDirection = if (direction == "BUY")
                TinkoffOrderDirection.ORDER_DIRECTION_BUY
            else
                TinkoffOrderDirection.ORDER_DIRECTION_SELL

            val orderId = UUID.randomUUID().toString()
            val order = ordersService.postOrderSync(
                instrumentId,
                quantity,
                Quotation.getDefaultInstance(),
                tinkoffDirection,
                accountId,
                OrderType.ORDER_TYPE_MARKET,
                orderId,
                isMarginTrade
            )

            logger.info { "Рыночная заявка отправлена: $order" }
            OrderResult(
                success = true,
                orderId = order.orderId,
                executedPrice = moneyValueToBigDecimal(order.initialSecurityPrice),
                executedOrderAmount = moneyValueToBigDecimal(order.executedOrderPrice),
                totalOrderAmount = moneyValueToBigDecimal(order.totalOrderAmount),
                executedCommission = moneyValueToBigDecimal(order.executedCommission),
                lotsRequested = order.lotsRequested,
                lotsExecuted = order.lotsExecuted,
                executionStatus = order.executionReportStatus.name
            )
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при выставлении рыночной заявки" }
            OrderResult(success = false, error = e.message)
        }
    }

    fun estimateOrderAmount(
        accountId: String,
        instrumentId: String,
        quantity: Long,
        price: BigDecimal,
        direction: String
    ): BigDecimal? {
        return try {
            val tinkoffDirection = if (direction == "BUY")
                TinkoffOrderDirection.ORDER_DIRECTION_BUY
            else
                TinkoffOrderDirection.ORDER_DIRECTION_SELL

            val response = ordersService.getOrderPriceSync(
                accountId,
                instrumentId,
                quantity,
                quotationFromBigDecimal(price),
                tinkoffDirection
            )
            moneyValueToBigDecimal(response.totalOrderAmount)
        } catch (e: Exception) {
            logger.warn(e) { "Не удалось получить предварительную стоимость заявки" }
            null
        }
    }

    fun getBrokerLotLimits(
        accountId: String,
        instrumentId: String,
        price: BigDecimal,
        direction: String
    ): BrokerLotLimits? {
        return try {
            val response = ordersService.getMaxLotsSync(
                accountId,
                instrumentId,
                quotationFromBigDecimal(price)
            )
            val buyLimits = response.buyLimits
            val sellLimits = response.sellMarginLimits

            BrokerLotLimits(
                currency = response.currency,
                maxBuyLots = buyLimits.buyMaxLots,
                maxMarketBuyLots = buyLimits.buyMaxMarketLots,
                availableBuyMoney = quotationToBigDecimal(buyLimits.buyMoneyAmount),
                maxSellLots = sellLimits.sellMaxLots,
                direction = direction
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get broker lot limits for $instrumentId" }
            null
        }
    }

    fun findActiveOrder(
        accountId: String,
        instrumentId: String,
        direction: String
    ): ActiveOrderInfo? {
        val tinkoffDirection = toTinkoffDirection(direction)
        return ordersService.getOrdersSync(accountId)
            .firstOrNull {
                it.instrumentUid == instrumentId &&
                        it.direction == tinkoffDirection &&
                        it.executionReportStatus in activeOrderStatuses
            }
            ?.let {
                ActiveOrderInfo(
                    orderId = it.orderId,
                    executionStatus = it.executionReportStatus.name
                )
            }
    }

    suspend fun waitForOrderFill(
        accountId: String,
        orderId: String,
        maxAttempts: Int = 10,
        delayMs: Long = 1000L
    ): OrderFillResult {
        var lastState: Any? = null
        var lastExecutedPrice: BigDecimal? = null
        var lastExecutedOrderAmount: BigDecimal? = null
        var lastExecutedCommission: BigDecimal? = null
        var lastLotsRequested: Long? = null
        var lastLotsExecuted: Long? = null

        repeat(maxAttempts) { attempt ->
            val state = ordersService.getOrderStateSync(accountId, orderId)
            lastState = state
            val status = state.executionReportStatus
            lastExecutedPrice = moneyValueToBigDecimal(state.averagePositionPrice)
            lastExecutedOrderAmount = moneyValueToBigDecimal(state.executedOrderPrice)
            lastExecutedCommission = moneyValueToBigDecimal(state.executedCommission)
            lastLotsRequested = state.lotsRequested
            lastLotsExecuted = state.lotsExecuted

            if (status == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL) {
                return OrderFillResult(
                    filled = true,
                    executedPrice = moneyValueToBigDecimal(state.averagePositionPrice),
                    executedOrderAmount = moneyValueToBigDecimal(state.executedOrderPrice),
                    totalOrderAmount = moneyValueToBigDecimal(state.totalOrderAmount),
                    executedCommission = moneyValueToBigDecimal(state.executedCommission),
                    lotsRequested = state.lotsRequested,
                    lotsExecuted = state.lotsExecuted,
                    executionStatus = status.name,
                    brokerOrderState = state.toString()
                )
            }

            if (status in terminalUnfilledStatuses) {
                return OrderFillResult(
                    filled = false,
                    lotsRequested = state.lotsRequested,
                    lotsExecuted = state.lotsExecuted,
                    executionStatus = status.name,
                    errorMessage = "Order finished without fill: ${status.name}",
                    brokerOrderState = state.toString()
                )
            }

            if (attempt < maxAttempts - 1) {
                delay(delayMs)
            }
        }

        return OrderFillResult(
            filled = false,
            executedPrice = lastExecutedPrice,
            executedOrderAmount = lastExecutedOrderAmount,
            executedCommission = lastExecutedCommission,
            lotsRequested = lastLotsRequested,
            lotsExecuted = lastLotsExecuted,
            executionStatus = "TIMEOUT_WAITING_FILL",
            errorMessage = "Order was not filled after $maxAttempts attempts",
            brokerOrderState = lastState?.toString()
        )
    }

    fun cancelOrder(accountId: String, orderId: String): Boolean = try {
        ordersService.cancelOrderSync(accountId, orderId)
        logger.info { "Отменён неисполненный остаток заявки: $orderId" }
        true
    } catch (error: Exception) {
        logger.warn(error) { "Не удалось отменить заявку $orderId" }
        false
    }

    fun getExecutedOrder(accountId: String, orderId: String): BrokerOrderExecution? = runCatching {
        val state = ordersService.getOrderStateSync(accountId, orderId)
        BrokerOrderExecution(
            price = moneyValueToBigDecimal(state.averagePositionPrice),
            commission = moneyValueToBigDecimal(state.executedCommission) ?: BigDecimal.ZERO,
            status = state.executionReportStatus.name
        )
    }.onFailure { error ->
        logger.warn(error) { "Не удалось получить исполнение заявки $orderId" }
    }.getOrNull()

    private fun toTinkoffDirection(direction: String): TinkoffOrderDirection =
        if (direction == "BUY") TinkoffOrderDirection.ORDER_DIRECTION_BUY
        else TinkoffOrderDirection.ORDER_DIRECTION_SELL

    private fun quotationFromBigDecimal(value: BigDecimal): Quotation {
        val units = value.toLong()
        val nano = value.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(1_000_000_000)).toInt()
        return Quotation.newBuilder()
            .setUnits(units)
            .setNano(nano)
            .build()
    }

    private fun moneyValueToBigDecimal(value: MoneyValue?): BigDecimal? {
        if (value == null) return null
        return BigDecimal.valueOf(value.units)
            .add(BigDecimal.valueOf(value.nano.toLong(), 9))
            .takeIf { it > BigDecimal.ZERO }
    }

    private fun quotationToBigDecimal(value: Quotation?): BigDecimal {
        if (value == null) return BigDecimal.ZERO
        return BigDecimal.valueOf(value.units)
            .add(BigDecimal.valueOf(value.nano.toLong(), 9))
    }

    companion object {
        private val activeOrderStatuses = setOf(
            OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_NEW,
            OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_PARTIALLYFILL
        )

        private val terminalUnfilledStatuses = setOf(
            OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_REJECTED,
            OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_CANCELLED
        )
    }
}

data class OrderResult(
    val success: Boolean,
    val orderId: String? = null,
    val error: String? = null,
    val executedPrice: BigDecimal? = null,
    val executedOrderAmount: BigDecimal? = null,
    val totalOrderAmount: BigDecimal? = null,
    val executedCommission: BigDecimal? = null,
    val lotsRequested: Long? = null,
    val lotsExecuted: Long? = null,
    val executionStatus: String? = null
)

data class ActiveOrderInfo(
    val orderId: String,
    val executionStatus: String
)

data class BrokerLotLimits(
    val currency: String,
    val maxBuyLots: Long,
    val maxMarketBuyLots: Long,
    val availableBuyMoney: BigDecimal,
    val maxSellLots: Long,
    val direction: String
)

data class BrokerOrderExecution(
    val price: BigDecimal?,
    val commission: BigDecimal,
    val status: String
)

data class OrderFillResult(
    val filled: Boolean,
    val executedPrice: BigDecimal? = null,
    val executedOrderAmount: BigDecimal? = null,
    val totalOrderAmount: BigDecimal? = null,
    val executedCommission: BigDecimal? = null,
    val lotsRequested: Long? = null,
    val lotsExecuted: Long? = null,
    val executionStatus: String? = null,
    val errorMessage: String? = null,
    val brokerOrderState: String? = null
) {
    val executedLots: Long
        get() = lotsExecuted ?: 0L
}
