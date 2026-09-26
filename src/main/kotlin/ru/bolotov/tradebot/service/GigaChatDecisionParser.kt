package ru.bolotov.tradebot.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ru.bolotov.tradebot.service.data.AiDecision

/** Разбирает единственный итоговый ответ GigaChat в совместимое с ботом решение. */
internal class GigaChatDecisionParser(private val objectMapper: ObjectMapper) {
    fun parse(response: JsonNode): AiDecision {
        val finishReason = finishReason(response)
        require(finishReason == null || finishReason.equals("stop", ignoreCase = true)) {
            "GigaChat не завершил решение штатно: finishReason=$finishReason"
        }
        val content = finalText(response)
        require(content.isNotBlank()) { "Ответ GigaChat не содержит итогового решения" }
        val startIndex = content.indexOf('{')
        val endIndex = content.lastIndexOf('}')
        require(startIndex in 0..<endIndex) { "Ответ AI не содержит завершённый JSON-объект" }
        val decision = objectMapper.readValue(content.substring(startIndex, endIndex + 1), AiDecision::class.java)
            ?: error("Ответ AI не содержит решения")
        require(decision.reason.isNotBlank()) { "Ответ AI не содержит объяснения" }
        require(decision.confidence in 0.0..1.0) { "Уверенность AI вне диапазона от 0 до 1" }
        return decision
    }

    fun finalText(response: JsonNode): String = response.path("choices").path(0)
        .path("message").path("content").asText("")

    fun finishReason(response: JsonNode): String? = response.path("choices").path(0)
        .path("finish_reason").asText("").takeIf(String::isNotBlank)
}
