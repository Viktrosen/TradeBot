package ru.bolotov.tradebot.broker

import com.google.protobuf.Timestamp
import ru.tinkoff.piapi.contract.v1.CancelOrderRequest
import ru.tinkoff.piapi.contract.v1.CancelStopOrderRequest
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.GetAccountsRequest
import ru.tinkoff.piapi.contract.v1.GetCandlesRequest
import ru.tinkoff.piapi.contract.v1.GetLastPricesRequest
import ru.tinkoff.piapi.contract.v1.GetMarginAttributesRequest
import ru.tinkoff.piapi.contract.v1.GetMaxLotsRequest
import ru.tinkoff.piapi.contract.v1.GetOrderPriceRequest
import ru.tinkoff.piapi.contract.v1.GetOrderStateRequest
import ru.tinkoff.piapi.contract.v1.GetOrdersRequest
import ru.tinkoff.piapi.contract.v1.GetStopOrdersRequest
import ru.tinkoff.piapi.contract.v1.GetTradingStatusesRequest
import ru.tinkoff.piapi.contract.v1.InstrumentIdType
import ru.tinkoff.piapi.contract.v1.InstrumentRequest
import ru.tinkoff.piapi.contract.v1.InstrumentStatus
import ru.tinkoff.piapi.contract.v1.InstrumentsRequest
import ru.tinkoff.piapi.contract.v1.MoneyValue
import ru.tinkoff.piapi.contract.v1.OrderDirection
import ru.tinkoff.piapi.contract.v1.OrderType
import ru.tinkoff.piapi.contract.v1.PortfolioRequest
import ru.tinkoff.piapi.contract.v1.PositionsRequest
import ru.tinkoff.piapi.contract.v1.PostOrderRequest
import ru.tinkoff.piapi.contract.v1.PostStopOrderRequest
import ru.tinkoff.piapi.contract.v1.Quotation
import ru.tinkoff.piapi.contract.v1.SandboxPayInRequest
import ru.tinkoff.piapi.contract.v1.StopOrderDirection
import ru.tinkoff.piapi.contract.v1.StopOrderExpirationType
import ru.tinkoff.piapi.contract.v1.StopOrderType
import ru.tinkoff.piapi.contract.v1.TradingStatus
import ru.ttech.piapi.core.InstrumentsServiceSync
import ru.ttech.piapi.core.MarketDataServiceSync
import ru.ttech.piapi.core.OperationsServiceSync
import ru.ttech.piapi.core.OrdersServiceSync
import ru.ttech.piapi.core.SandboxServiceSync
import ru.ttech.piapi.core.StopOrdersServiceSync
import ru.ttech.piapi.core.UsersServiceSync
import java.time.Instant

fun UsersServiceSync.getAccountsSync() = getAccounts(GetAccountsRequest.getDefaultInstance()).accountsList

fun UsersServiceSync.getMarginAttributesSync(accountId: String) = getMarginAttributes(
    GetMarginAttributesRequest.newBuilder().setAccountId(accountId).build()
)

fun SandboxServiceSync.getAccountsSync() =
    getSandboxAccounts(GetAccountsRequest.getDefaultInstance()).accountsList

fun SandboxServiceSync.openAccountSync(): String =
    openSandboxAccount(ru.tinkoff.piapi.contract.v1.OpenSandboxAccountRequest.getDefaultInstance()).accountId

fun SandboxServiceSync.closeAccountSync(accountId: String) {
    closeSandboxAccount(
        ru.tinkoff.piapi.contract.v1.CloseSandboxAccountRequest.newBuilder()
            .setAccountId(accountId)
            .build()
    )
}

fun SandboxServiceSync.payInSync(accountId: String, amount: MoneyValue) {
    sandboxPayIn(
        SandboxPayInRequest.newBuilder()
            .setAccountId(accountId)
            .setAmount(amount)
            .build()
    )
}

fun OperationsServiceSync.getPortfolioSync(accountId: String) =
    getPortfolio(PortfolioRequest.newBuilder().setAccountId(accountId).build())

fun OperationsServiceSync.getPositionsSync(accountId: String) =
    getPositions(PositionsRequest.newBuilder().setAccountId(accountId).build())

fun OrdersServiceSync.postOrderSync(
    instrumentId: String,
    quantity: Long,
    price: Quotation,
    direction: OrderDirection,
    accountId: String,
    orderType: OrderType,
    orderId: String,
    confirmMarginTrade: Boolean = false
) = postOrder(
    PostOrderRequest.newBuilder()
        .setInstrumentId(instrumentId)
        .setQuantity(quantity)
        .setPrice(price)
        .setDirection(direction)
        .setAccountId(accountId)
        .setOrderType(orderType)
        .setOrderId(orderId)
        .setConfirmMarginTrade(confirmMarginTrade)
        .build()
)

fun OrdersServiceSync.getOrderPriceSync(
    accountId: String,
    instrumentId: String,
    quantity: Long,
    price: Quotation,
    direction: OrderDirection
) = getOrderPrice(
    GetOrderPriceRequest.newBuilder()
        .setAccountId(accountId)
        .setInstrumentId(instrumentId)
        .setQuantity(quantity)
        .setPrice(price)
        .setDirection(direction)
        .build()
)

fun OrdersServiceSync.getMaxLotsSync(accountId: String, instrumentId: String, price: Quotation) =
    getMaxLots(
        GetMaxLotsRequest.newBuilder()
            .setAccountId(accountId)
            .setInstrumentId(instrumentId)
            .setPrice(price)
            .build()
    )

fun OrdersServiceSync.getOrdersSync(accountId: String) =
    getOrders(GetOrdersRequest.newBuilder().setAccountId(accountId).build()).ordersList

fun OrdersServiceSync.getOrderStateSync(accountId: String, orderId: String) =
    getOrderState(
        GetOrderStateRequest.newBuilder()
            .setAccountId(accountId)
            .setOrderId(orderId)
            .build()
    )

fun OrdersServiceSync.cancelOrderSync(accountId: String, orderId: String) {
    cancelOrder(
        CancelOrderRequest.newBuilder()
            .setAccountId(accountId)
            .setOrderId(orderId)
            .build()
    )
}

fun StopOrdersServiceSync.getStopOrdersSync(
    accountId: String,
    from: Instant,
    to: Instant,
    status: ru.tinkoff.piapi.contract.v1.StopOrderStatusOption
) = getStopOrders(
    GetStopOrdersRequest.newBuilder()
        .setAccountId(accountId)
        .setFrom(from.toTimestamp())
        .setTo(to.toTimestamp())
        .setStatus(status)
        .build()
).stopOrdersList

fun StopOrdersServiceSync.postStopOrderGoodTillCancelSync(
    instrumentId: String,
    quantity: Long,
    price: Quotation,
    stopPrice: Quotation,
    direction: StopOrderDirection,
    accountId: String,
    type: StopOrderType,
    orderId: java.util.UUID,
    confirmMarginTrade: Boolean = false
): String = postStopOrder(
    PostStopOrderRequest.newBuilder()
        .setInstrumentId(instrumentId)
        .setQuantity(quantity)
        .setPrice(price)
        .setStopPrice(stopPrice)
        .setDirection(direction)
        .setAccountId(accountId)
        .setExpirationType(StopOrderExpirationType.STOP_ORDER_EXPIRATION_TYPE_GOOD_TILL_CANCEL)
        .setStopOrderType(type)
        .setOrderId(orderId.toString())
        .setConfirmMarginTrade(confirmMarginTrade)
        .build()
).stopOrderId

fun StopOrdersServiceSync.cancelStopOrderSync(accountId: String, stopOrderId: String) {
    cancelStopOrder(
        CancelStopOrderRequest.newBuilder()
            .setAccountId(accountId)
            .setStopOrderId(stopOrderId)
            .build()
    )
}

val InstrumentsServiceSync.tradableSharesSync
    get() = shares(
        InstrumentsRequest.newBuilder()
            .setInstrumentStatus(InstrumentStatus.INSTRUMENT_STATUS_BASE)
            .build()
    ).instrumentsList

fun InstrumentsServiceSync.getInstrumentByUIDSync(instrumentUid: String) =
    getInstrumentBy(
        InstrumentRequest.newBuilder()
            .setIdType(InstrumentIdType.INSTRUMENT_ID_TYPE_UID)
            .setId(instrumentUid)
            .build()
    )

fun InstrumentsServiceSync.getShareByUidSync(instrumentUid: String) =
    shareBy(
        InstrumentRequest.newBuilder()
            .setIdType(InstrumentIdType.INSTRUMENT_ID_TYPE_UID)
            .setId(instrumentUid)
            .build()
    )

fun MarketDataServiceSync.getTradingStatusesSync(instrumentIds: List<String>) =
    getTradingStatuses(
        GetTradingStatusesRequest.newBuilder()
            .addAllInstrumentId(instrumentIds)
            .build()
    ).tradingStatusesList

fun MarketDataServiceSync.getLastPricesSync(instrumentIds: List<String>) =
    getLastPrices(GetLastPricesRequest.newBuilder().addAllInstrumentId(instrumentIds).build()).lastPricesList

fun MarketDataServiceSync.getCandlesSync(
    instrumentId: String,
    from: Instant,
    to: Instant,
    interval: CandleInterval
) = getCandles(
    GetCandlesRequest.newBuilder()
        .setInstrumentId(instrumentId)
        .setFrom(from.toTimestamp())
        .setTo(to.toTimestamp())
        .setInterval(interval)
        .build()
).candlesList

private fun Instant.toTimestamp(): Timestamp = Timestamp.newBuilder()
    .setSeconds(epochSecond)
    .setNanos(nano)
    .build()
