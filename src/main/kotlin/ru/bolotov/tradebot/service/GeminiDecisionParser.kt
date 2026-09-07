package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ru.bolotov.tradebot.service.data.AiDecision

/** Разбирает итог первого кандидата Gemini, исключая части с рассуждениями. */
internal class GeminiDecisionParser(private val objectMapper: ObjectMapper) {
    fun parse(response: JsonNode): AiDecision {
        val finishReason = finishReason(response)
        require(finishReason == null || finishReason == "STOP") {
            "Gemini не завершил решение штатно: finishReason=$finishReason"
        }
        val content = finalText(response)
        require(content.isNotBlank()) { "Ответ Gemini не содержит итогового решения" }
        val startIndex = content.indexOf('{')
        val endIndex = content.lastIndexOf('}')
        require(startIndex in 0..<endIndex) { "Ответ AI не содержит завершённый JSON-объект" }
        val decision = objectMapper.readValue(content.substring(startIndex, endIndex + 1), AiDecision::class.java)
            ?: error("Ответ AI не содержит решения")
        require(decision.reason.isNotBlank()) { "Ответ AI не содержит объяснения" }
        require(decision.confidence in 0.0..1.0) { "Уверенность AI вне диапазона от 0 до 1" }
        return decision
    }

    /** Объединяет только итоговые текстовые части, не помеченные thought=true. */
    fun finalText(response: JsonNode): String = response.path("candidates").path(0)
        .path("content").path("parts")
        .filter { !it.path("thought").asBoolean(false) && it.path("text").isTextual }
        .joinToString("") { it.path("text").asText() }

    fun finishReason(response: JsonNode): String? = response.path("candidates").path(0)
        .path("finishReason").asText("").takeIf(String::isNotBlank)
}
