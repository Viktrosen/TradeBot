package ru.bolotov.tradebot.service

import org.springframework.stereotype.Service
import java.time.Instant

@Service
class HealthCheckService {

    private var lastHeartbeat = Instant.now()
    private val startTime = System.currentTimeMillis()

    fun recordHeartbeat() {
        lastHeartbeat = Instant.now()
    }

    fun isHealthy(): Boolean = Instant.now().minusSeconds(60).isBefore(lastHeartbeat)

    fun getStatus(): Map<String, Any> = mapOf(
        "status" to if (isHealthy()) "UP" else "DOWN",
        "lastHeartbeat" to lastHeartbeat.toString(),
        "uptime" to (System.currentTimeMillis() - startTime).toString()
    )
}