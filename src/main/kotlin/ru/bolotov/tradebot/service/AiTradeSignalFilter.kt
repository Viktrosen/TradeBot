package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import ru.bolotov.tradebot.strategy.MarketData
import ru.bolotov.tradebot.strategy.OrderDirection
import ru.bolotov.tradebot.strategy.Signal
import ru.bolotov.tradebot.strategy.TradingStrategy
import java.math.BigDecimal

private val aiFilterLogger = KotlinLogging.logger {}

@Service
class AiTradeSignalFilter(
    @Qualifier("openRouterRestClient") private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    @Value("\${ai.enabled:false}") private val enabled: Boolean,
    @Value("\${ai.openrouter.api-key:}") private val apiKey: String,
    @Value("\${ai.openrouter.model:openai/gpt-4o}") private val model: String,
    @Value("\${ai.min-confidence.buy:0.75}") private val minBuyConfidence: Double,
    @Value("\${ai.min-confidence.profit-sell:0.70}") private val minProfitSellConfidence: Double,
    @Value("\${ai.min-confidence.loss-sell:0.85}") private val minLossSellConfidence: Double
) {

    init {
        when {
            !enabled -> aiFilterLogger.info { "AI-фильтр отключён настройкой AI_ENABLED" }
            apiKey.isBlank() -> aiFilterLogger.error {
                "AI-фильтр включён, но OPENROUTER_API_KEY не задан; сигналы покупки будут отклоняться"
            }

            else -> aiFilterLogger.info { "AI-фильтр включён, модель: $model" }
        }
    }

    fun evaluate(
        marketData: MarketData,
        signal: Signal,
        strategy: TradingStrategy,
        position: OpenPosition?
    ): AiFilterResult {
        if (!enabled) return AiFilterResult(approved = true)
        if (apiKey.isBlank()) {
            aiFilterLogger.error { "AI-фильтр включён, но OPENROUTER_API_KEY не задан; сигнал отклонён" }
            return AiFilterResult(approved = false)
        }

        return try {
            val startedAt = System.nanoTime()
            logRequest(marketData, signal, strategy)
            val decision = requestDecision(marketData, signal, strategy, position)
            val durationMs = (System.nanoTime() - startedAt) / NANOS_IN_MILLISECOND
            val requiredConfidence = requiredConfidence(signal, position, marketData.currentPrice)
            val result = AiFilterResult(
                approved = decision.action == AiAction.APPROVE && decision.confidence >= requiredConfidence,
                explanation = decision.reason,
                confidence = decision.confidence
            )
            logDecision(marketData, signal, decision, requiredConfidence, result.approved, durationMs)
            result
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
        val responseBody = restClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer $apiKey")
            .body(createRequest(marketData, signal, strategy, position))
            .exchange { _, response ->
                response.body.bufferedReader().use { reader ->
                    val responseBody = reader.readText()
                    if (response.statusCode.isError) {
                        error("OpenRouter вернул HTTP ${response.statusCode.value()}")
                    }
                    responseBody
                }
            }
            ?: error("OpenRouter вернул пустой ответ")

        return parseDecision(objectMapper.readTree(responseBody))
    }

    private fun createRequest(
        marketData: MarketData,
        signal: Signal,
        strategy: TradingStrategy,
        position: OpenPosition?
    ): Map<String, Any> = mapOf(
        "model" to model,
        "temperature" to 0,
        "max_tokens" to MAX_COMPLETION_TOKENS,
        "messages" to listOf(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT),
            mapOf(
                "role" to "user",
                "content" to objectMapper.writeValueAsString(
                    AiTradeContext.from(marketData, signal, strategy, position)
                )
            )
        ),
        "response_format" to JSON_OBJECT_RESPONSE_FORMAT
    )

    private fun parseDecision(response: JsonNode): AiDecision {
        val content = response.path("choices")
            .path(0)
            .path("message")
            .path("content")
            .asText()
            .takeIf(String::isNotBlank)
            ?: error("Ответ OpenRouter не содержит решения")

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

    private fun requiredConfidence(
        signal: Signal,
        position: OpenPosition?,
        currentPrice: BigDecimal
    ): Double = when (signal.direction) {
        OrderDirection.BUY -> minBuyConfidence
        OrderDirection.SELL -> {
            if (position != null && currentPrice < position.entryPrice) {
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

        const val SYSTEM_PROMPT = """
            Ты — консервативный фильтр подтверждения сигналов long-only торгового бота.
            Оценивай только переданные структурированные данные рынка и сигнал стратегии.
            Не выдумывай новости, цены, индикаторы или прочие данные.

            Для BUY одобряй вход только при согласованном подтверждении стратегии и индикаторов.
            Не одобряй покупку при противоречивых индикаторах, недостатке данных, чрезмерно растянутом
            движении цены или когда сигнал выглядит как попытка догнать уже совершившийся рост.

            Для SELL используй currentPnlPercent и entryPrice из position.
            Если позиция в прибыли, одобряй продажу при достаточном подтверждении выхода.
            Если позиция в убытке, одобряй продажу только при сильном и согласованном подтверждении
            продолжения снижения: паттерн, тренд и доступные индикаторы должны указывать в одну сторону.
            Учитывай близость текущего убытка к stopLossPrice. При слабых, смешанных или недостаточных
            признаках продолжения снижения возвращай HOLD, не одобряй преждевременную убыточную продажу.
            Однако не пытайся любой ценой удерживать убыточную позицию: при убедительном риске дальнейшего
            падения одобряй SELL. Стоп-лосс остаётся безусловной защитой и к тебе не поступает.

            REJECT означает не исполнять сделку. HOLD означает недостаточность данных.
            Стоп-лосс, тейк-профит, ручное и аварийное закрытие к тебе не поступают и не должны обсуждаться.
            Поле reason пиши на русском языке.
            Всегда возвращай только один валидный JSON-объект без Markdown, пояснений и текста до или после JSON.
            Ответ обязан начинаться с { и заканчиваться }.
            Используй ровно этот формат: {"action":"APPROVE","confidence":0.85,"reason":"Краткое объяснение на русском"}.
        """

        val JSON_OBJECT_RESPONSE_FORMAT = mapOf("type" to "json_object")
    }
}

data class AiFilterResult(
    val approved: Boolean,
    val explanation: String? = null,
    val confidence: Double? = null
)

private enum class AiAction {
    APPROVE,
    REJECT,
    HOLD;

    val description: String
        get() = when (this) {
            APPROVE -> "одобрено"
            REJECT -> "отклонено"
            HOLD -> "недостаточно данных"
        }
}

private data class AiDecision(
    val action: AiAction,
    val confidence: Double,
    val reason: String
)

private val Signal.actionDescription: String
    get() = when (direction) {
        OrderDirection.BUY -> "покупки"
        OrderDirection.SELL -> "продажи"
        OrderDirection.HOLD -> "сделки"
    }

private data class AiTradeContext(
    val strategy: AiStrategyContext,
    val signal: AiSignalContext,
    val market: AiMarketContext,
    val position: AiPositionContext?
) {
    companion object {
        fun from(
            marketData: MarketData,
            signal: Signal,
            strategy: TradingStrategy,
            position: OpenPosition?
        ) = AiTradeContext(
            strategy = AiStrategyContext(
                name = strategy.name,
                explanation = strategy.getExplanation(marketData),
                candlestickPattern = marketData.candlestickPattern?.pattern?.name,
                candlestickConfidence = marketData.candlestickPattern?.confidence,
                candlestickTimeframe = marketData.candlestickPattern?.candleKey?.substringBefore(':')
            ),
            signal = AiSignalContext(
                direction = signal.direction.name,
                confidence = signal.confidence,
                reason = signal.reason
            ),
            market = AiMarketContext.from(marketData),
            position = position?.let { AiPositionContext.from(it, marketData.currentPrice) }
        )
    }
}

private data class AiStrategyContext(
    val name: String,
    val explanation: String,
    val candlestickPattern: String?,
    val candlestickConfidence: Double?,
    val candlestickTimeframe: String?
)

private data class AiSignalContext(
    val direction: String,
    val confidence: Double,
    val reason: String?
)

private data class AiMarketContext(
    val instrument: String,
    val currentPrice: BigDecimal,
    val ema5: BigDecimal?,
    val ema21: BigDecimal?,
    val rsi: Double?,
    val macdHistogram: BigDecimal?,
    val bollingerPercentB: Double?,
    val atr: BigDecimal?,
    val volume: Long,
    val averageVolume: Long,
    val volatility: Double,
    val spread: BigDecimal
) {
    companion object {
        fun from(data: MarketData) = AiMarketContext(
            instrument = data.instrumentName,
            currentPrice = data.currentPrice,
            ema5 = data.ema5,
            ema21 = data.ema21,
            rsi = data.rsi,
            macdHistogram = data.macd?.histogram,
            bollingerPercentB = data.bollingerBands?.percentB,
            atr = data.atr,
            volume = data.volume,
            averageVolume = data.avgVolume,
            volatility = data.volatility,
            spread = data.spread
        )
    }
}

private data class AiPositionContext(
    val entryPrice: BigDecimal,
    val currentPnlPercent: Double,
    val stopLossPrice: BigDecimal?,
    val openedAt: String
) {
    companion object {
        fun from(position: OpenPosition, currentPrice: BigDecimal) = AiPositionContext(
            entryPrice = position.entryPrice,
            currentPnlPercent = position.currentPnlPercent(currentPrice),
            stopLossPrice = position.stopLossPrice,
            openedAt = position.entryTime.toString()
        )
    }
}

private fun OpenPosition.currentPnlPercent(currentPrice: BigDecimal): Double =
    (currentPrice - entryPrice)
        .divide(entryPrice, PNL_SCALE, java.math.RoundingMode.HALF_UP)
        .toDouble()

private const val PNL_SCALE = 8
