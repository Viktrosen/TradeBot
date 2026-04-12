package ru.bolotov.tradebot.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.tinkoff.piapi.core.OrdersService
import ru.tinkoff.piapi.contract.v1.OrderDirection as TinkoffOrderDirection
import ru.tinkoff.piapi.contract.v1.OrderType
import ru.tinkoff.piapi.contract.v1.Quotation
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
            OrderResult(success = true, orderId = order.orderId)
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при выставлении заявки" }
            OrderResult(success = false, error = e.message)
        }
    }

    private fun quotationFromBigDecimal(value: BigDecimal): Quotation {
        val units = value.toLong()
        val nano = value.remainder(BigDecimal.ONE).multiply(BigDecimal.valueOf(1_000_000_000)).toInt()
        return Quotation.newBuilder()
            .setUnits(units)
            .setNano(nano)
            .build()
    }
}

data class OrderResult(
    val success: Boolean,
    val orderId: String? = null,
    val error: String? = null
)