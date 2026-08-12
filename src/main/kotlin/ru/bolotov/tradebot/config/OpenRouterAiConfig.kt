package ru.bolotov.tradebot.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
class OpenRouterAiConfig(
    @Value("\${ai.openrouter.timeout-ms:5000}") private val timeoutMs: Int
) {

    @Bean
    fun openRouterRestClient(): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(timeoutMs)
            setReadTimeout(timeoutMs)
        }

        return RestClient.builder()
            .baseUrl(OPEN_ROUTER_URL)
            .requestFactory(requestFactory)
            .build()
    }

    private companion object {
        const val OPEN_ROUTER_URL = "https://openrouter.ai/api/v1"
    }
}
