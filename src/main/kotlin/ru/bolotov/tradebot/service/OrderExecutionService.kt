package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.contract.v1.OrderDirection as TinkoffOrderDirection
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus
import ru.tinkoff.piapi.contract.v1.OrderType
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.core.OrdersService
import java.math.BigDecimal
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class OrderExecutionService(
    private val ordersService: OrdersService
) {

    suspend fun placeOrder(
        accountId: String,
        instrumentId: String,
        quantity: Long,
        price: BigDecimal,
        direction: String
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
                orderId
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
        direction: String
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
                orderId
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
        repeat(maxAttempts) { attempt ->
            val state = ordersService.getOrderStateSync(accountId, orderId)
            val status = state.executionReportStatus

            if (status == OrderExecutionReportStatus.EXECUTION_REPORT_STATUS_FILL) {
                return OrderFillResult(
                    filled = true,
                    executedPrice = moneyValueToBigDecimal(state.averagePositionPrice),
                    executedOrderAmount = moneyValueToBigDecimal(state.executedOrderPrice),
                    totalOrderAmount = moneyValueToBigDecimal(state.totalOrderAmount),
                    executedCommission = moneyValueToBigDecimal(state.executedCommission),
                    lotsRequested = state.lotsRequested,
                    lotsExecuted = state.lotsExecuted,
                    executionStatus = status.name
                )
            }

            if (status in terminalUnfilledStatuses) {
                return OrderFillResult(
                    filled = false,
                    lotsRequested = state.lotsRequested,
                    lotsExecuted = state.lotsExecuted,
                    executionStatus = status.name
                )
            }

            if (attempt < maxAttempts - 1) {
                delay(delayMs)
            }
        }

        return OrderFillResult(
            filled = false,
            executionStatus = "TIMEOUT_WAITING_FILL"
        )
    }

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

data class OrderFillResult(
    val filled: Boolean,
    val executedPrice: BigDecimal? = null,
    val executedOrderAmount: BigDecimal? = null,
    val totalOrderAmount: BigDecimal? = null,
    val executedCommission: BigDecimal? = null,
    val lotsRequested: Long? = null,
    val lotsExecuted: Long? = null,
    val executionStatus: String? = null
)
