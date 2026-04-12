// TradeBotApplication.kt
package ru.bolotov.tradebot

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

private val logger = KotlinLogging.logger {}

@SpringBootApplication
@EnableScheduling
class TradeBotApplication

fun main(args: Array<String>) {
    runApplication<TradeBotApplication>(*args)
    logger.info { "Trade Bot успешно запущен" }
}