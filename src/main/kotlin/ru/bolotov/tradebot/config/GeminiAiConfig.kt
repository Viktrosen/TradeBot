package ru.bolotov.tradebot.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/** Создаёт HTTP-клиент прямого Gemini API с ограниченными тайм-аутами. */
@Configuration
class GeminiAiConfig(
    @Value("\${ai.gemini.timeout-ms:5000}") private val timeoutMs: Int
) {
    @Bean
    fun geminiRestClient(): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(timeoutMs)
            setReadTimeout(timeoutMs)
        }

        return RestClient.builder()
            .baseUrl(GEMINI_API_URL)
            .requestFactory(requestFactory)
            .build()
    }

    private companion object {
        const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}
