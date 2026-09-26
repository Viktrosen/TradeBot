package ru.bolotov.tradebot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.bolotov.tradebot.service.data.AiAction

class GigaChatDecisionParserTest {
    private val mapper = jacksonObjectMapper()
    private val parser = GigaChatDecisionParser(mapper)
    private val approve = """{"action":"APPROVE","confidence":0.85,"reason":"Подтверждено"}"""

    private fun response(content: String, finishReason: String? = "stop") = mapper.readTree(
        mapper.writeValueAsString(mapOf("choices" to listOf(mapOf(
            "finish_reason" to finishReason,
            "message" to mapOf("content" to content)
        ))))
    )

    @Test
    fun `parses ordinary final answer`() {
        assertEquals(AiAction.APPROVE, parser.parse(response(approve)).action)
    }

    @Test
    fun `rejects incomplete or truncated answer`() {
        assertThrows(IllegalArgumentException::class.java) { parser.parse(response(approve.dropLast(1))) }
        assertThrows(IllegalArgumentException::class.java) { parser.parse(response(approve, "length")) }
    }
}
