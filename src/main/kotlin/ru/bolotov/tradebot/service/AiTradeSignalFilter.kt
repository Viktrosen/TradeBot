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
    @Value("\${ai.openrouter.model:openai/gpt-4o}") private val model: String
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

    fun shouldExecute(
        marketData: MarketData,
        signal: Signal,
        strategy: TradingStrategy,
        position: OpenPosition?
    ): Boolean {
        if (!enabled) return true
        if (signal.direction != OrderDirection.BUY) return true

        if (apiKey.isBlank()) {
            aiFilterLogger.error { "AI-фильтр включён, но OPENROUTER_API_KEY не задан; сигнал отклонён" }
            return false
        }

        return try {
            val startedAt = System.nanoTime()
            logRequest(marketData, signal, strategy)
            val decision = requestDecision(marketData, signal, strategy, position)
            val durationMs = (System.nanoTime() - startedAt) / NANOS_IN_MILLISECOND
            logDecision(marketData, signal, decision, durationMs)
            decision.action == AiAction.APPROVE
        } catch (error: Exception) {
            aiFilterLogger.warn(error) {
                "AI-фильтр: не удалось проверить покупку ${marketData.instrumentName}; " +
                    "сигнал отклонён. Причина: ${error.message ?: error.javaClass.simpleName}"
            }
            false
        }
    }

    private fun requestDecision(
        marketData: MarketData,
        signal: Signal,
        strategy: TradingStrategy,
        position: OpenPosition?
    ): AiDecision {
        val response = restClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer $apiKey")
            .body(createRequest(marketData, signal, strategy, position))
            .retrieve()
            .body(JsonNode::class.java)
            ?: error("OpenRouter вернул пустой ответ")

        return parseDecision(response)
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
        "response_format" to RESPONSE_FORMAT
    )

    private fun parseDecision(response: JsonNode): AiDecision {
        val content = response.path("choices")
            .path(0)
            .path("message")
            .path("content")
            .asText()
            .takeIf(String::isNotBlank)
            ?: error("Ответ OpenRouter не содержит решения")

        val decision = objectMapper.readValue(content, AiDecision::class.java)
        require(decision.confidence in 0.0..1.0) { "Уверенность AI вне диапазона от 0 до 1" }
        return decision
    }

    private fun logRequest(marketData: MarketData, signal: Signal, strategy: TradingStrategy) {
        aiFilterLogger.info {
            "AI-фильтр: отправлена проверка покупки ${marketData.instrumentName}; " +
                "стратегия=${strategy.name}, цена=${marketData.currentPrice}, " +
                "уверенность сигнала=${signal.confidence}"
        }
    }

    private fun logDecision(
        marketData: MarketData,
        signal: Signal,
        decision: AiDecision,
        durationMs: Long
    ) {
        aiFilterLogger.info {
            "AI-фильтр: ${marketData.instrumentName}, сигнал=${signal.direction}, " +
                "решение=${decision.action.description}, уверенность=${decision.confidence}, " +
                "время=${durationMs} мс, причина=${decision.reason}"
        }
    }

    private companion object {
        const val MAX_COMPLETION_TOKENS = 180
        const val NANOS_IN_MILLISECOND = 1_000_000L

        const val SYSTEM_PROMPT = """
            You are a conservative confirmation filter for a long-only trading bot.
            Evaluate only the supplied structured market data and strategy signal.
            Never invent market data, news, prices, or indicators.
            APPROVE only when the signal has adequate confirmation.
            REJECT means the trade should not be executed. HOLD means insufficient evidence.
            A stop-loss, take-profit, and emergency close never reach you and must not be discussed.
            Return only JSON matching the provided schema.
        """

        val RESPONSE_FORMAT = mapOf(
            "type" to "json_schema",
            "json_schema" to mapOf(
                "name" to "trade_signal_decision",
                "strict" to true,
                "schema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf(
                            "type" to "string",
                            "enum" to AiAction.entries.map(AiAction::name)
                        ),
                        "confidence" to mapOf("type" to "number"),
                        "reason" to mapOf("type" to "string")
                    ),
                    "required" to listOf("action", "confidence", "reason"),
                    "additionalProperties" to false
                )
            )
        )
    }
}

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
