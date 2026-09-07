package ru.bolotov.tradebot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.bolotov.tradebot.service.data.AiAction

class GeminiDecisionParserTest {
    private val mapper = jacksonObjectMapper()
    private val parser = GeminiDecisionParser(mapper)
    private val approve = """{"action":"APPROVE","confidence":0.85,"reason":"Подтверждено"}"""
    private val reject = """{"action":"REJECT","confidence":0.85,"reason":"Противоречия"}"""

    private fun response(parts: List<Map<String, Any>>, finish: String? = "STOP") =
        mapper.readTree(mapper.writeValueAsString(mapOf("candidates" to listOf(
            mapOf("finishReason" to finish, "content" to mapOf("parts" to parts))
        ))))

    @Test
    fun `ignores approval in thoughts and reads split final rejection`() {
        val response = response(listOf(
            mapOf("thought" to true, "text" to approve),
            mapOf("inlineData" to mapOf("mimeType" to "image/png")),
            mapOf("text" to reject.take(20)),
            mapOf("text" to reject.drop(20))
        ))
        assertEquals(AiAction.REJECT, parser.parse(response).action)
        assertEquals(reject, parser.finalText(response))
    }

    @Test
    fun `never treats thoughts only as a decision`() {
        val response = response(listOf(mapOf("thought" to true, "text" to approve)))
        assertThrows(IllegalArgumentException::class.java) { parser.parse(response) }
        assertEquals("", parser.finalText(response))
    }

    @Test
    fun `rejects token limit even when text contains complete approval`() {
        val response = response(listOf(mapOf("text" to approve)), "MAX_TOKENS")
        assertEquals("MAX_TOKENS", parser.finishReason(response))
        assertThrows(IllegalArgumentException::class.java) { parser.parse(response) }
    }

    @Test
    fun `accepts ordinary final response with or without finish reason`() {
        for (finish in listOf("STOP", null)) {
            assertEquals(AiAction.APPROVE, parser.parse(response(listOf(mapOf("text" to approve)), finish)).action)
        }
    }

    @Test
    fun `rejects incomplete JSON and invalid confidence`() {
        for (text in listOf(approve.dropLast(1), approve.replace("0.85", "1.5"))) {
            assertThrows(Exception::class.java) { parser.parse(response(listOf(mapOf("text" to text)))) }
        }
    }
}
