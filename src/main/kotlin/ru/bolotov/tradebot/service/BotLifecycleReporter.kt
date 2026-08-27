package ru.bolotov.tradebot.service

import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Публикует стартовый, периодический и финальный статусы жизненного цикла бота. */
@Component
class BotLifecycleReporter(
    private val tradingBotService: TradingBotService,
    private val eventPublisherService: EventPublisherService
) {

    @EventListener(ApplicationReadyEvent::class)
    /** Сообщает клиентам статус сразу после полной инициализации Spring-приложения. */
    fun publishInitialStatus() {
        publishCurrentStatus()
    }

    @Scheduled(fixedDelayString = "\${bot.heartbeat.delay-ms:20000}")
    /** Регулярно публикует heartbeat без изменения торгового состояния. */
    fun publishHeartbeat() {
        eventPublisherService.publishBotHeartbeat(tradingBotService.getStatus())
    }

    @PreDestroy
    /** Перед завершением процесса сообщает клиентам, что бот остановлен. */
    fun publishStoppedStatus() {
        eventPublisherService.publishBotStatusChanged("STOPPED")
    }

    private fun publishCurrentStatus() {
        val status = if (tradingBotService.getStatus()) "RUNNING" else "STOPPED"
        eventPublisherService.publishBotStatusChanged(status)
        publishHeartbeat()
    }
}
