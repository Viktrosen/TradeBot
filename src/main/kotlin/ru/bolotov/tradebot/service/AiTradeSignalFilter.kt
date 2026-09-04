package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import ru.bolotov.tradebot.domain.model.PositionSide
import org.springframework.web.client.RestClient
import ru.bolotov.tradebot.service.data.AiAction
import ru.bolotov.tradebot.service.data.AiDecision
import ru.bolotov.tradebot.service.data.AiFilterResult
import ru.bolotov.tradebot.service.data.AiTradeContext
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.OrderDirection
import ru.bolotov.tradebot.strategy.Signal
import ru.bolotov.tradebot.strategy.TradingStrategy
import java.math.BigDecimal
import java.time.Instant

private val aiFilterLogger = KotlinLogging.logger {}

/** Запрашивает AI как консервативный дополнительный фильтр торговых сигналов. */
@Service
class AiTradeSignalFilter(
    @Qualifier("geminiRestClient") private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    @Value("\${ai.enabled:false}") private val enabled: Boolean,
    @Value("\${ai.gemini.api-key:}") private val apiKey: String,
    @Value("\${ai.gemini.model:gemini-2.0-flash}") private val model: String,
    @Value("\${ai.gemini.thinking-budget:0}") private val thinkingBudget: Int,
    @Value("\${ai.min-confidence.buy:0.75}") private val minBuyConfidence: Double,
    @Value("\${ai.min-confidence.profit-sell:0.70}") private val minProfitSellConfidence: Double,
    @Value("\${ai.min-confidence.loss-sell:0.85}") private val minLossSellConfidence: Double
) {
    private val rateLimitLock = Any()
    private var rateLimitedUntil: Instant? = null
    private var rateLimitAttempt = 0

    init {
        when {
            !enabled -> aiFilterLogger.info { "AI-фильтр отключён настройкой AI_ENABLED" }
            apiKey.isBlank() -> aiFilterLogger.error {
                "AI-фильтр включён, но GEMINI_API_KEY не задан; сигналы открытия будут отклоняться"
            }

            else -> aiFilterLogger.info { "AI-фильтр включён, провайдер=Gemini, модель: $model" }
        }
    }

    /** Возвращает решение AI либо отклонение при ошибке, лимите или недостаточной уверенности. */
    fun evaluate(
        marketData: MarketData,
        signal: Signal,
        strategy: TradingStrategy,
        position: OpenPosition?
    ): AiFilterResult {
        if (!enabled) return AiFilterResult(approved = true)
        if (apiKey.isBlank()) {
            aiFilterLogger.error { "AI-фильтр включён, но GEMINI_API_KEY не задан; сигнал отклонён" }
            return AiFilterResult(approved = false)
        }
        if (isRateLimited()) return AiFilterResult(approved = false)

        return try {
            val startedAt = System.nanoTime()
            logRequest(marketData, signal, strategy)
            val decision = requestDecision(marketData, signal, strategy, position)
            resetRateLimitBackoff()
            val durationMs = (System.nanoTime() - startedAt) / NANOS_IN_MILLISECOND
            val requiredConfidence = requiredConfidence(signal, position, marketData.currentPrice)
            val result = AiFilterResult(
                approved = decision.action == AiAction.APPROVE && decision.confidence >= requiredConfidence,
                explanation = decision.reason,
                confidence = decision.confidence
            )
            logDecision(marketData, signal, decision, requiredConfidence, result.approved, durationMs)
            result
        } catch (_: AiRateLimitException) {
            blockRequestsAfterRateLimit()
            AiFilterResult(approved = false)
        } catch (error: AiResponseFormatException) {
            aiFilterLogger.warn(error.cause) {
                "AI-фильтр: не удалось проверить ${signal.actionDescription} ${marketData.instrumentName}; " +
                    "сигнал отклонён. Причина: ${error.cause?.message ?: error.message}; " +
                    "HTTP=${error.statusCode}, провайдер=Gemini, модель=$model, " +
                    "finish_reason=${error.finishReason ?: "<не указан>"}, " +
                    "ответ AI (усечён): ${error.contentPreview}"
            }
            AiFilterResult(approved = false)
        } catch (error: ResourceAccessException) {
            val rootCause = error.mostSpecificCause
            aiFilterLogger.warn(error) {
                "AI-фильтр: Gemini недоступен для ${signal.actionDescription} ${marketData.instrumentName}; " +
                    "сигнал отклонён. Причина=${rootCause.javaClass.simpleName}: " +
                    "${rootCause.message ?: "<не указана>"}; модель=$model"
            }
            AiFilterResult(approved = false)
        } catch (error: Exception) {
            aiFilterLogger.warn(error) {
                "AI-фильтр: не удалось проверить ${signal.actionDescription} ${marketData.instrumentName}; " +
                    "сигнал отклонён. Причина: ${error.message ?: error.javaClass.simpleName}"
            }
            AiFilterResult(approved = false)
        }
    }

    private fun requestDecision(
        marketData: MarketData,
        signal: Signal,
        strategy: TradingStrategy,
        position: OpenPosition?
    ): AiDecision {
        val response = restClient.post()
            .uri("/models/{model}:generateContent", model)
            .contentType(MediaType.APPLICATION_JSON)
            .header(GEMINI_API_KEY_HEADER, apiKey)
            .body(createRequest(marketData, signal, strategy, position))
            .exchange { _, response ->
                response.body.bufferedReader().use { reader ->
                    val responseBody = reader.readText()
                    if (response.statusCode.value() == HTTP_TOO_MANY_REQUESTS) {
                        throw AiRateLimitException()
                    }
                    if (response.statusCode.isError) {
                        throw AiProviderException(response.statusCode.value(), responseBody)
                    }
                    GeminiResponse(response.statusCode.value(), responseBody)
                }
            }
            ?: error("Gemini вернул пустой ответ")

        return try {
            parseDecision(objectMapper.readTree(response.body))
        } catch (error: Exception) {
            throw AiResponseFormatException(
                statusCode = response.statusCode,
                contentPreview = responseContentPreview(response.body),
                finishReason = responseFinishReason(response.body),
                cause = error
            )
        }
    }

    private fun createRequest(
        marketData: MarketData,
        signal: Signal,
        strategy: TradingStrategy,
        position: OpenPosition?
    ): Map<String, Any> = mapOf(
        "systemInstruction" to mapOf(
            "parts" to listOf(mapOf("text" to SYSTEM_PROMPT))
        ),
        "contents" to listOf(
            mapOf(
                "role" to "user",
                "parts" to listOf(
                    mapOf(
                        "text" to objectMapper.writeValueAsString(
                            AiTradeContext.from(marketData, signal, strategy, position)
                        )
                    )
                )
            )
        ),
        "generationConfig" to mapOf(
            "temperature" to 0,
            "maxOutputTokens" to MAX_COMPLETION_TOKENS,
            "responseMimeType" to "application/json",
            "responseJsonSchema" to DECISION_JSON_SCHEMA,
            "thinkingConfig" to mapOf("thinkingBudget" to thinkingBudget)
        )
    )

    private fun parseDecision(response: JsonNode): AiDecision {
        val content = response.path("candidates")
            .path(0)
            .path("content")
            .path("parts")
            .path(0)
            .path("text")
            .asText()
            .takeIf(String::isNotBlank)
            ?: error("Ответ Gemini не содержит решения")

        val decision = objectMapper.readValue(extractJsonObject(content), AiDecision::class.java)
            ?: error("Ответ AI не содержит решения")
        require(decision.reason.isNotBlank()) { "Ответ AI не содержит объяснения" }
        require(decision.confidence in 0.0..1.0) { "Уверенность AI вне диапазона от 0 до 1" }
        return decision
    }

    private fun extractJsonObject(content: String): String {
        val startIndex = content.indexOf('{')
        val endIndex = content.lastIndexOf('}')
        require(startIndex >= 0 && endIndex > startIndex) {
            "Ответ AI не содержит завершённый JSON-объект"
        }
        return content.substring(startIndex, endIndex + 1)
    }

    /**
     * Возвращает безопасный фрагмент ответа для диагностики несовместимого формата.
     * Полный ответ не логируется, чтобы не раздувать логи и не сохранять лишние данные.
     */
    private fun responseContentPreview(responseBody: String): String = runCatching {
        objectMapper.readTree(responseBody)
            .path("candidates")
            .path(0)
            .path("content")
            .path("parts")
            .path(0)
            .path("text")
            .asText()
    }.getOrDefault(responseBody)
        .replace(Regex("\\s+"), " ")
        .take(RESPONSE_PREVIEW_MAX_LENGTH)
        .ifBlank { "<пусто>" }

    /** Возвращает причину завершения ответа Gemini для диагностики обрезанных ответов. */
    private fun responseFinishReason(responseBody: String): String? = runCatching {
        objectMapper.readTree(responseBody)
            .path("candidates")
            .path(0)
            .path("finish_reason")
            .asText()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun logRequest(marketData: MarketData, signal: Signal, strategy: TradingStrategy) {
        aiFilterLogger.info {
            "AI-фильтр: отправлена проверка ${signal.actionDescription} ${marketData.instrumentName}; " +
                "стратегия=${strategy.name}, цена=${marketData.currentPrice}, " +
                "уверенность сигнала=${signal.confidence}"
        }
    }

    private fun logDecision(
        marketData: MarketData,
        signal: Signal,
        decision: AiDecision,
        requiredConfidence: Double,
        approved: Boolean,
        durationMs: Long
    ) {
        aiFilterLogger.info {
            "AI-фильтр: ${marketData.instrumentName}, сигнал=${signal.direction}, " +
                "решение=${if (approved) "одобрено" else "отклонено"}, " +
                "ответ=${decision.action.description}, уверенность=${decision.confidence}, " +
                "минимум=$requiredConfidence, " +
                "время=${durationMs} мс, причина=${decision.reason}"
        }
    }

    /** Проверяет активную паузу, не сбрасывая ступень backoff до успешного ответа AI. */
    private fun isRateLimited(): Boolean = synchronized(rateLimitLock) {
        val blockedUntil = rateLimitedUntil ?: return false
        if (!Instant.now().isBefore(blockedUntil)) {
            rateLimitedUntil = null
            return false
        }
        aiFilterLogger.debug { "AI-фильтр временно не вызывает Gemini до $blockedUntil после HTTP 429" }
        true
    }

    /**
     * Увеличивает паузу после каждого фактического HTTP 429. После последней
     * ступени список заканчивается постоянной паузой 300 секунд.
     */
    private fun blockRequestsAfterRateLimit() {
        val (waitSeconds, blockedUntil) = synchronized(rateLimitLock) {
            val delayIndex = rateLimitAttempt.coerceAtMost(RATE_LIMIT_BACKOFF_SECONDS.lastIndex)
            val delaySeconds = RATE_LIMIT_BACKOFF_SECONDS[delayIndex]
            rateLimitAttempt = (delayIndex + 1).coerceAtMost(RATE_LIMIT_BACKOFF_SECONDS.size)
            val until = Instant.now().plusSeconds(delaySeconds)
            rateLimitedUntil = until
            delaySeconds to until
        }
        aiFilterLogger.warn {
            "AI-фильтр получил HTTP 429 от Gemini; пауза $waitSeconds с, запросы к Gemini приостановлены до $blockedUntil"
        }
    }

    /** Сбрасывает накопленный backoff только после успешного и валидного ответа AI. */
    private fun resetRateLimitBackoff() = synchronized(rateLimitLock) {
        if (rateLimitAttempt == 0 && rateLimitedUntil == null) return

        rateLimitAttempt = 0
        rateLimitedUntil = null
        aiFilterLogger.info { "AI-фильтр: успешный ответ получен, backoff после HTTP 429 сброшен" }
    }

    private fun requiredConfidence(
        signal: Signal,
        position: OpenPosition?,
        currentPrice: BigDecimal
    ): Double = when (signal.direction) {
        OrderDirection.BUY -> minBuyConfidence
        OrderDirection.SELL -> {
            if (position == null) {
                minBuyConfidence
            } else if (position.hasUnrealizedLoss(currentPrice)) {
                minLossSellConfidence
            } else {
                minProfitSellConfidence
            }
        }

        OrderDirection.HOLD -> 1.0
    }

    private companion object {
        const val MAX_COMPLETION_TOKENS = 180
        const val NANOS_IN_MILLISECOND = 1_000_000L
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val RESPONSE_PREVIEW_MAX_LENGTH = 400
        const val GEMINI_API_KEY_HEADER = "x-goog-api-key"
        val RATE_LIMIT_BACKOFF_SECONDS = listOf(10L, 20L, 40L, 80L, 160L, 300L)
        val DECISION_JSON_SCHEMA = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "enum" to listOf("APPROVE", "REJECT", "HOLD")
                ),
                "confidence" to mapOf(
                    "type" to "number",
                    "minimum" to 0,
                    "maximum" to 1
                ),
                "reason" to mapOf("type" to "string")
            ),
            "required" to listOf("action", "confidence", "reason"),
            "additionalProperties" to false
        )

        const val SYSTEM_PROMPT = """
            Ты — консервативный фильтр подтверждения сигналов торгового бота.
            Оценивай только переданные структурированные данные рынка и сигнал стратегии.
            Не выдумывай новости, цены, индикаторы или прочие данные.

            Если position отсутствует, BUY означает открытие LONG, а SELL — открытие SHORT.
            Одобряй открытие только при согласованном подтверждении стратегии и индикаторов. Не одобряй
            вход при противоречивых индикаторах, недостатке данных или чрезмерно растянутом движении цены.

            Особенности стратегий:
            - SuperTrend: BUY или SELL означает смену направления тренда, подтверждённую ATR-полосой.
              Для неё используй strategy.details.trendDirection, upperBand и lowerBand. Не оценивай этот
              сигнал как возврат цены к средней.
            - VWAP: BUY ниже VWAP и SELL выше VWAP — контртрендовый вход в расчёте на возврат к
              средневзвешенной цене. Используй market.vwap и market.vwapDeviationPercent. Не отклоняй
              корректный VWAP-сигнал только из-за того, что EMA или MACD ещё отражают прежний импульс.

            Если position присутствует, action в signal — это действие закрытия: SELL закрывает LONG,
            BUY закрывает SHORT. Используй positionSide, currentPnlPercent и entryPrice из position.
            При положительном P&L одобряй закрытие при достаточном подтверждении выхода.
            При отрицательном P&L одобряй закрытие только при сильном и согласованном подтверждении
            неблагоприятного движения: для LONG — дальнейшего снижения, для SHORT — дальнейшего роста.
            Учитывай близость текущего убытка к stopLossPrice. При слабых, смешанных или недостаточных
            признаках возвращай HOLD, не одобряй преждевременное убыточное закрытие.
            Стоп-лосс, тейк-профит, ручное и аварийное закрытие к тебе не поступают и не должны обсуждаться.

            REJECT означает не исполнять сделку. HOLD означает недостаточность данных.
            Поле reason пиши на русском языке одной фразой не длиннее 180 символов.
            Всегда возвращай только один валидный JSON-объект без Markdown, пояснений и текста до или после JSON.
            Ответ обязан начинаться с { и заканчиваться }.
            Используй ровно этот формат: {"action":"APPROVE","confidence":0.85,"reason":"Краткое объяснение на русском"}.
        """
    }
}

private class AiRateLimitException : RuntimeException("Gemini вернул HTTP 429")

private data class GeminiResponse(
    val statusCode: Int,
    val body: String
)

/** Содержит безопасно усечённый ответ Gemini при кодах HTTP, отличных от 429. */
private class AiProviderException(statusCode: Int, responseBody: String) : RuntimeException(
    "Gemini вернул HTTP $statusCode: ${responseBody.replace(Regex("\\s+"), " ").take(400)}"
)

private class AiResponseFormatException(
    val statusCode: Int,
    val contentPreview: String,
    val finishReason: String?,
    cause: Throwable
) : RuntimeException(cause.message, cause)
