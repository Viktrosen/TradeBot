package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import ru.bolotov.tradebot.domain.model.BotOperationEventType
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
import ru.bolotov.tradebot.strategy.regime.MarketRegimeDecision
import ru.bolotov.tradebot.strategy.regime.StrategySelection
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore

private val aiFilterLogger = KotlinLogging.logger {}

/** Запрашивает AI как консервативный дополнительный фильтр торговых сигналов. */
@Service
class AiTradeSignalFilter(
    @Qualifier("geminiRestClient") private val restClient: RestClient,
    @Qualifier("gigaChatRestClient") private val gigaChatRestClient: RestClient,
    @Qualifier("gigaChatOAuthRestClient") private val gigaChatOAuthRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val operationJournal: BotOperationJournal,
    @Value("\${ai.enabled:false}") private val enabled: Boolean,
    @Value("\${ai.provider:gemini}") private val provider: String,
    @Value("\${ai.gemini.api-key:}") private val apiKey: String,
    @Value("\${ai.gemini.model:gemini-2.0-flash}") private val model: String,
    @Value("\${ai.gigachat.api-key:}") private val gigaChatApiKey: String,
    @Value("\${ai.gigachat.model:GigaChat-2}") private val gigaChatModel: String,
    @Value("\${ai.gigachat.scope:GIGACHAT_API_PERS}") private val gigaChatScope: String,
    @Value("\${ai.min-confidence.buy:0.75}") private val minBuyConfidence: Double,
    @Value("\${ai.min-confidence.profit-sell:0.70}") private val minProfitSellConfidence: Double,
    @Value("\${ai.min-confidence.loss-sell:0.85}") private val minLossSellConfidence: Double
) {
    private val decisionParser = GeminiDecisionParser(objectMapper)
    private val gigaChatDecisionParser = GigaChatDecisionParser(objectMapper)
    private val rateLimitLock = Any()
    private val gigaChatTokenLock = Any()
    private val gigaChatRequestSemaphore = Semaphore(1, true)
    private var rateLimitedUntil: Instant? = null
    private var rateLimitAttempt = 0
    @Volatile private var gigaChatToken: AccessToken? = null

    private val providerName: String = when (provider.trim().lowercase()) {
        "gemini" -> "Gemini"
        "gigachat" -> "GigaChat"
        else -> "Неизвестный AI-провайдер ($provider)"
    }
    private val selectedModel: String get() = if (providerName == "GigaChat") gigaChatModel else model
    private val isSupportedProvider: Boolean get() = providerName == "Gemini" || providerName == "GigaChat"
    private val isConfigured: Boolean get() = when (providerName) {
        "Gemini" -> apiKey.isNotBlank()
        "GigaChat" -> gigaChatApiKey.isNotBlank()
        else -> false
    }

    init {
        when {
            !enabled -> aiFilterLogger.info { "AI-фильтр отключён настройкой AI_ENABLED" }
            !isSupportedProvider -> aiFilterLogger.error {
                "AI-фильтр включён, но ai.provider=$provider не поддерживается; сигналы открытия будут отклоняться"
            }
            !isConfigured -> aiFilterLogger.error {
                "AI-фильтр включён, но ключ для $providerName не задан; сигналы открытия будут отклоняться"
            }
            else -> aiFilterLogger.info { "AI-фильтр включён, провайдер=$providerName, модель: $selectedModel" }
        }
    }

    /** Возвращает решение AI либо отклонение при ошибке, лимите или недостаточной уверенности. */
    fun evaluate(
        marketData: MarketData,
        signal: Signal,
        selection: StrategySelection,
        regimeDecision: MarketRegimeDecision,
        position: OpenPosition?
    ): AiFilterResult {
        if (!enabled) return AiFilterResult(approved = true)
        if (!isSupportedProvider || !isConfigured) {
            aiFilterLogger.error { "AI-фильтр включён, но $providerName не настроен; сигнал отклонён" }
            return AiFilterResult(approved = false)
        }
        if (isRateLimited()) return AiFilterResult(approved = false)

        return try {
            val startedAt = System.nanoTime()
            logRequest(marketData, signal, selection.strategy)
            val decision = requestDecision(marketData, signal, selection, regimeDecision, position)
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
            logFailure(
                marketData = marketData,
                signal = signal,
                message = "AI-фильтр: $providerName ограничил частоту запросов; сигнал отклонён",
                context = mapOf("provider" to providerName, "model" to selectedModel, "httpStatus" to HTTP_TOO_MANY_REQUESTS)
            )
            AiFilterResult(approved = false)
        } catch (error: AiResponseFormatException) {
            logFailure(
                marketData = marketData,
                signal = signal,
                message = "AI-фильтр: $providerName вернул ответ неподдерживаемого формата; сигнал отклонён",
                consoleMessage = "AI-фильтр: не удалось проверить ${signal.actionDescription} ${marketData.instrumentName}; " +
                    "сигнал отклонён. Причина: ${error.cause?.message ?: error.message}; " +
                    "HTTP=${error.statusCode}, провайдер=$providerName, модель=$selectedModel, " +
                    "finishReason=${error.finishReason ?: "<не указан>"}, " +
                    "ответ AI (усечён): ${error.contentPreview}",
                error = error.cause ?: error,
                context = mapOf(
                    "provider" to providerName,
                    "model" to selectedModel,
                    "httpStatus" to error.statusCode,
                    "finishReason" to error.finishReason
                )
            )
            AiFilterResult(approved = false)
        } catch (error: ResourceAccessException) {
            val rootCause = error.mostSpecificCause
            logFailure(
                marketData = marketData,
                signal = signal,
                message = "AI-фильтр: $providerName недоступен; сигнал отклонён",
                consoleMessage = "AI-фильтр: $providerName недоступен для ${signal.actionDescription} ${marketData.instrumentName}; " +
                    "сигнал отклонён. Причина=${rootCause.javaClass.simpleName}: " +
                    "${rootCause.message ?: "<не указана>"}; модель=$selectedModel",
                error = rootCause,
                context = mapOf("provider" to providerName, "model" to selectedModel)
            )
            AiFilterResult(approved = false)
        } catch (error: AiProviderBusyException) {
            logFailure(
                marketData = marketData,
                signal = signal,
                message = "AI-фильтр: $providerName занят другой проверкой; сигнал отклонён",
                error = null,
                context = mapOf("provider" to providerName, "model" to selectedModel, "reason" to "concurrency_limit")
            )
            AiFilterResult(approved = false)
        } catch (error: Exception) {
            logFailure(
                marketData = marketData,
                signal = signal,
                message = "AI-фильтр: проверка $providerName завершилась ошибкой; сигнал отклонён",
                consoleMessage = "AI-фильтр: не удалось проверить ${signal.actionDescription} ${marketData.instrumentName}; " +
                    "сигнал отклонён. Причина: ${error.message ?: error.javaClass.simpleName}",
                error = error,
                context = mapOf("provider" to providerName, "model" to selectedModel)
            )
            AiFilterResult(approved = false)
        }
    }

    private fun requestDecision(
        marketData: MarketData,
        signal: Signal,
        selection: StrategySelection,
        regimeDecision: MarketRegimeDecision,
        position: OpenPosition?
    ): AiDecision {
        val requestId = UUID.randomUUID().toString()
        return if (providerName == "GigaChat") {
            requestGigaChatDecision(requestId, marketData, signal, selection, regimeDecision, position)
        } else {
            requestGeminiDecision(requestId, marketData, signal, selection, regimeDecision, position)
        }
    }

    private fun requestGeminiDecision(
        requestId: String,
        marketData: MarketData,
        signal: Signal,
        selection: StrategySelection,
        regimeDecision: MarketRegimeDecision,
        position: OpenPosition?
    ): AiDecision {
        val requestBody = createRequest(marketData, signal, selection, regimeDecision, position)
        aiFilterLogger.debug {
            "AI-фильтр: Gemini запрос, requestId=$requestId, " +
                "инструмент=${marketData.instrumentName}, стратегия=${selection.id}, модель=$model, " +
                "endpoint=/models/$model:generateContent, " +
                "generationConfig=${objectMapper.writeValueAsString(requestBody["generationConfig"])}"
        }
        val response = restClient.post()
            .uri("/models/{model}:generateContent", model)
            .contentType(MediaType.APPLICATION_JSON)
            .header(GEMINI_API_KEY_HEADER, apiKey)
            .body(requestBody)
            .exchange { _, response ->
                response.body.bufferedReader().use { reader ->
                    val responseBody = reader.readText()
                    aiFilterLogger.debug {
                        "AI-фильтр: Gemini ответ, requestId=$requestId, " +
                            "инструмент=${marketData.instrumentName}, модель=$model, " +
                            "HTTP=${response.statusCode.value()}, размер ответа=${responseBody.length} символов" +
                            if (response.statusCode.isError) "; ${providerErrorDiagnostics(responseBody)}" else ""
                    }
                    if (response.statusCode.value() == HTTP_TOO_MANY_REQUESTS) {
                        throw AiRateLimitException()
                    }
                    if (response.statusCode.isError) {
                        throw AiProviderException("Gemini", response.statusCode.value(), responseBody)
                    }
                    GeminiResponse(response.statusCode.value(), responseBody)
                }
            }
            ?: error("Gemini вернул пустой ответ")

        return try {
            decisionParser.parse(objectMapper.readTree(response.body))
        } catch (error: Exception) {
            throw AiResponseFormatException(
                statusCode = response.statusCode,
                contentPreview = responseContentPreview(response.body),
                finishReason = responseFinishReason(response.body),
                cause = error
            )
        }
    }

    /** Личный GigaChat допускает один запрос; конкурирующий сигнал не ждёт и безопасно отклоняется. */
    private fun requestGigaChatDecision(
        requestId: String,
        marketData: MarketData,
        signal: Signal,
        selection: StrategySelection,
        regimeDecision: MarketRegimeDecision,
        position: OpenPosition?
    ): AiDecision {
        if (!gigaChatRequestSemaphore.tryAcquire()) throw AiProviderBusyException("GigaChat уже обрабатывает другой запрос")
        try {
            val contextJson = objectMapper.writeValueAsString(AiTradeContext.from(marketData, signal, selection, regimeDecision, position))
            val requestBody = mapOf(
                "model" to gigaChatModel,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                    mapOf("role" to "user", "content" to contextJson)
                ),
                "temperature" to 1.0,
                "max_tokens" to MAX_COMPLETION_TOKENS,
                "stream" to false
            )
            aiFilterLogger.debug {
                "AI-фильтр: GigaChat запрос, requestId=$requestId, инструмент=${marketData.instrumentName}, " +
                    "стратегия=${selection.id}, модель=$gigaChatModel, endpoint=/chat/completions, " +
                    "temperature=1.0, maxTokens=$MAX_COMPLETION_TOKENS, stream=false"
            }
            val response = gigaChatRestClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer ${gigaChatAccessToken()}")
                .header("User-Agent", GIGA_CHAT_USER_AGENT)
                .body(requestBody)
                .exchange { _, clientResponse ->
                    clientResponse.body.bufferedReader().use { reader ->
                        val responseBody = reader.readText()
                        aiFilterLogger.debug {
                            "AI-фильтр: GigaChat ответ, requestId=$requestId, инструмент=${marketData.instrumentName}, " +
                                "модель=$gigaChatModel, HTTP=${clientResponse.statusCode.value()}, размер ответа=${responseBody.length} символов"
                        }
                        if (clientResponse.statusCode.value() == HTTP_TOO_MANY_REQUESTS) throw AiRateLimitException()
                        if (clientResponse.statusCode.isError) {
                            throw AiProviderException("GigaChat", clientResponse.statusCode.value(), responseBody)
                        }
                        GigaChatResponse(clientResponse.statusCode.value(), responseBody)
                    }
                } ?: error("GigaChat вернул пустой ответ")
            return try {
                gigaChatDecisionParser.parse(objectMapper.readTree(response.body))
            } catch (error: Exception) {
                throw AiResponseFormatException(
                    statusCode = response.statusCode,
                    contentPreview = gigaChatResponseContentPreview(response.body),
                    finishReason = gigaChatFinishReason(response.body),
                    cause = error
                )
            }
        } finally {
            gigaChatRequestSemaphore.release()
        }
    }

    /** OAuth-токен GigaChat действует 30 минут; обновляем его за минуту до истечения. */
    private fun gigaChatAccessToken(): String = synchronized(gigaChatTokenLock) {
        gigaChatToken?.takeIf { it.expiresAt.isAfter(Instant.now().plusSeconds(TOKEN_REFRESH_SAFETY_SECONDS)) }
            ?.let { return it.value }
        val responseBody = gigaChatOAuthRestClient.post()
            .uri("/api/v2/oauth")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(MediaType.APPLICATION_JSON)
            .header("RqUID", UUID.randomUUID().toString())
            .header("Authorization", "Basic $gigaChatApiKey")
            .body("scope=$gigaChatScope")
            .exchange { _, clientResponse ->
                clientResponse.body.bufferedReader().use { reader ->
                    val body = reader.readText()
                    if (clientResponse.statusCode.isError) {
                        throw AiProviderException("GigaChat OAuth", clientResponse.statusCode.value(), body)
                    }
                    body
                }
            } ?: error("GigaChat OAuth вернул пустой ответ")
        val response = objectMapper.readTree(responseBody)
        val token = response.path("access_token").asText("")
        require(token.isNotBlank()) { "GigaChat OAuth не вернул access_token" }
        val expiresAt = response.path("expires_at").asLong(0).takeIf { it > 0 }
            ?.let(Instant::ofEpochSecond) ?: Instant.now().plusSeconds(DEFAULT_TOKEN_TTL_SECONDS)
        return AccessToken(token, expiresAt).also { gigaChatToken = it }.value
    }

    /**
     * Из ошибки провайдера извлекает только служебные поля для диагностики HTTP 400.
     * Не выводит message, descriptions, metadata и исходное тело: они могут повторять
     * пользовательский промпт или другие данные запроса. Диагностика не влияет на обработку ошибки.
     */
    private fun providerErrorDiagnostics(responseBody: String): String = runCatching {
        val error = objectMapper.readTree(responseBody).path("error")
        val details = error.path("details").take(10).map { detail ->
            mapOf(
                "type" to diagnosticValue(detail.path("@type")),
                "reason" to diagnosticValue(detail.path("reason")),
                "domain" to diagnosticValue(detail.path("domain")),
                "fields" to detail.path("fieldViolations").take(10).map {
                    diagnosticValue(it.path("field"))
                }
            )
        }
        "providerCode=${diagnosticValue(error.path("code"))}, " +
            "providerStatus=${diagnosticValue(error.path("status"))}, " +
            "details=${objectMapper.writeValueAsString(details)}"
    }.getOrDefault("детали ошибки недоступны: тело не удалось разобрать как JSON")

    /** Ограничивает служебное значение одной строкой и маскирует ключ даже при его отражении API. */
    private fun diagnosticValue(node: JsonNode): String = node.asText("")
        .let { value -> sequenceOf(apiKey, gigaChatApiKey).filter(String::isNotBlank)
            .fold(value) { masked, key -> masked.replace(key, "<скрыто>") } }
        .replace(Regex("\\s+"), " ")
        .take(RESPONSE_PREVIEW_MAX_LENGTH)

    private fun createRequest(
        marketData: MarketData,
        signal: Signal,
        selection: StrategySelection,
        regimeDecision: MarketRegimeDecision,
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
                            AiTradeContext.from(
                                marketData, signal, selection, regimeDecision, position
                            )
                        )
                    )
                )
            )
        ),
        "generationConfig" to mapOf(
            "temperature" to 1.0,
            "maxOutputTokens" to MAX_COMPLETION_TOKENS,
            "responseMimeType" to "application/json",
            "responseJsonSchema" to DECISION_JSON_SCHEMA
        )
    )

    /**
     * Возвращает безопасный фрагмент ответа для диагностики несовместимого формата.
     * Полный ответ не логируется, чтобы не раздувать логи и не сохранять лишние данные.
     */
    private fun responseContentPreview(responseBody: String): String = runCatching {
        decisionParser.finalText(objectMapper.readTree(responseBody))
    }.getOrDefault("<не удалось разобрать ответ>")
        .replace(Regex("\\s+"), " ")
        .take(RESPONSE_PREVIEW_MAX_LENGTH)
        .ifBlank { "<пусто>" }

    /** Возвращает причину завершения ответа Gemini для диагностики обрезанных ответов. */
    private fun responseFinishReason(responseBody: String): String? = runCatching {
        decisionParser.finishReason(objectMapper.readTree(responseBody))
    }.getOrNull()

    private fun gigaChatResponseContentPreview(responseBody: String): String = runCatching {
        gigaChatDecisionParser.finalText(objectMapper.readTree(responseBody))
    }.getOrDefault("<не удалось разобрать ответ>")
        .replace(Regex("\\s+"), " ").take(RESPONSE_PREVIEW_MAX_LENGTH).ifBlank { "<пусто>" }

    private fun gigaChatFinishReason(responseBody: String): String? = runCatching {
        gigaChatDecisionParser.finishReason(objectMapper.readTree(responseBody))
    }.getOrNull()

    private fun logRequest(marketData: MarketData, signal: Signal, strategy: TradingStrategy) {
        operationJournal.info(
            eventType = BotOperationEventType.AI_REQUEST,
            message = "AI-фильтр: отправлена проверка ${signal.actionDescription} ${marketData.instrumentName}; " +
                "стратегия=${strategy.name}, цена=${marketData.currentPrice}, " +
                "уверенность сигнала=${signal.confidence}",
            instrumentId = marketData.instrumentId,
            instrumentName = marketData.instrumentName,
            context = mapOf(
                "provider" to providerName,
                "model" to selectedModel,
                "strategy" to strategy.name,
                "signal" to signal.direction.name
            )
        )
    }

    private fun logDecision(
        marketData: MarketData,
        signal: Signal,
        decision: AiDecision,
        requiredConfidence: Double,
        approved: Boolean,
        durationMs: Long
    ) {
        operationJournal.info(
            eventType = BotOperationEventType.AI_DECISION,
            message = "AI-фильтр: ${marketData.instrumentName}, сигнал=${signal.direction}, " +
                "решение=${if (approved) "одобрено" else "отклонено"}, " +
                "ответ=${decision.action.description}, уверенность=${decision.confidence}, " +
                "минимум=$requiredConfidence, время=${durationMs} мс, причина=${decision.reason}",
            instrumentId = marketData.instrumentId,
            instrumentName = marketData.instrumentName,
            context = mapOf(
                "provider" to providerName,
                "model" to selectedModel,
                "signal" to signal.direction.name,
                "approved" to approved,
                "confidence" to decision.confidence,
                "requiredConfidence" to requiredConfidence,
                "durationMs" to durationMs
            )
        )
    }

    /** Writes failure metadata without persisting the AI response or prompt. */
    private fun logFailure(
        marketData: MarketData,
        signal: Signal,
        message: String,
        consoleMessage: String = message,
        error: Throwable? = null,
        context: Map<String, Any?>
    ) {
        operationJournal.warn(
            eventType = BotOperationEventType.AI_FAILURE,
            message = message,
            consoleMessage = consoleMessage,
            instrumentId = marketData.instrumentId,
            instrumentName = marketData.instrumentName,
            context = context + mapOf("signal" to signal.direction.name),
            error = error
        )
    }

    /** Проверяет активную паузу, не сбрасывая ступень backoff до успешного ответа AI. */
    private fun isRateLimited(): Boolean = synchronized(rateLimitLock) {
        val blockedUntil = rateLimitedUntil ?: return false
        if (!Instant.now().isBefore(blockedUntil)) {
            rateLimitedUntil = null
            return false
        }
        aiFilterLogger.debug { "AI-фильтр временно не вызывает $providerName до $blockedUntil после HTTP 429" }
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
            "AI-фильтр получил HTTP 429 от $providerName; пауза $waitSeconds с, запросы приостановлены до $blockedUntil"
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
        const val GIGA_CHAT_USER_AGENT = "TradeBot/1.0"
        const val TOKEN_REFRESH_SAFETY_SECONDS = 60L
        const val DEFAULT_TOKEN_TTL_SECONDS = 1_740L
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

            marketRegime — внутренний подтверждённый режим TradeBot, по которому выбрана strategy.id.
            Он объясняет назначение стратегии, но не является самостоятельным торговым сигналом.
            Оценивай его только совместно с данными strategy, signal и market.

            Если position отсутствует, BUY означает открытие LONG, а SELL — открытие SHORT.
            Одобряй открытие только при согласованном подтверждении стратегии и индикаторов. Не одобряй
            вход при противоречивых индикаторах, недостатке данных или чрезмерно растянутом движении цены.

            Особенности стратегий:
            - Cross EMA: проверяй именно факт пересечения по previousEma5, previousEma21 и текущим EMA,
              а не только их текущее взаимное положение.
            - CandlestickPatterns: используй candlestickPattern, candlestickConfidence и candlestickTimeframe.
              Отсутствие паттерна или низкая уверенность — основание не одобрять новый вход.
            - Voting: signal.reason содержит итог голосования и весов. Не считай его самостоятельным
              подтверждением, если данные market противоречат направлению сигнала.
            - Confirmation: signal.reason описывает индикаторы, достигшие согласия; проверяй их
              по структурированным полям market.
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

private class AiProviderBusyException(message: String) : RuntimeException(message)

private data class GeminiResponse(
    val statusCode: Int,
    val body: String
)

private data class GigaChatResponse(val statusCode: Int, val body: String)

private data class AccessToken(val value: String, val expiresAt: Instant)

/** Содержит безопасно усечённый ответ Gemini при кодах HTTP, отличных от 429. */
private class AiProviderException(provider: String, statusCode: Int, responseBody: String) : RuntimeException(
    "$provider вернул HTTP $statusCode: ${responseBody.replace(Regex("\\s+"), " ").take(400)}"
)

private class AiResponseFormatException(
    val statusCode: Int,
    val contentPreview: String,
    val finishReason: String?,
    cause: Throwable
) : RuntimeException(cause.message, cause)
