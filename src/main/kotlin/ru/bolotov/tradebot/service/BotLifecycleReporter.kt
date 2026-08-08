package ru.bolotov.tradebot.service

import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class BotLifecycleReporter(
    private val tradingBotService: TradingBotService,
    private val eventPublisherService: EventPublisherService
) {

    @EventListener(ApplicationReadyEvent::class)
    fun publishInitialStatus() {
        publishCurrentStatus()
    }

    @Scheduled(fixedDelayString = "\${bot.heartbeat.delay-ms:20000}")
    fun publishHeartbeat() {
        eventPublisherService.publishBotHeartbeat(tradingBotService.getStatus())
    }

    @PreDestroy
    fun publishStoppedStatus() {
        eventPublisherService.publishBotStatusChanged("STOPPED")
    }

    private fun publishCurrentStatus() {
        val status = if (tradingBotService.getStatus()) "RUNNING" else "STOPPED"
        eventPublisherService.publishBotStatusChanged(status)
        publishHeartbeat()
    }
}
