package ru.bolotov.tradebot.broker

import ru.tinkoff.piapi.contract.v1.Quotation
import java.math.BigDecimal

/** Преобразует представление цены T-Invest без потери точности nano-части. */
fun Quotation.toBigDecimal(): BigDecimal =
    BigDecimal.valueOf(units).add(BigDecimal.valueOf(nano.toLong(), 9))
