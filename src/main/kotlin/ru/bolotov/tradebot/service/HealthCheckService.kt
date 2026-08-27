package ru.bolotov.tradebot.service

import org.springframework.stereotype.Service
import java.time.Instant

/** Хранит heartbeat бота и формирует лёгкий статус health-check. */
@Service
class HealthCheckService {

    private var lastHeartbeat = Instant.now()
    private val startTime = System.currentTimeMillis()

    /** Обновляет время последней подтверждённой активности бота. */
    fun recordHeartbeat() {
        lastHeartbeat = Instant.now()
    }

    /** Проверяет, поступал ли heartbeat за последнюю минуту. */
    fun isHealthy(): Boolean = Instant.now().minusSeconds(60).isBefore(lastHeartbeat)

    /** Формирует ответ диагностического endpoint без обращения к брокеру. */
    fun getStatus(): Map<String, Any> = mapOf(
        "status" to if (isHealthy()) "UP" else "DOWN",
        "lastHeartbeat" to lastHeartbeat.toString(),
        "uptime" to (System.currentTimeMillis() - startTime).toString()
    )
}
