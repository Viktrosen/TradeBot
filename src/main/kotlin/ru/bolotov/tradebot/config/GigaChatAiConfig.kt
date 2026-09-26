package ru.bolotov.tradebot.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/** HTTP-клиенты GigaChat API и OAuth. Токен доступа получает сервис фильтра и не хранит в БД. */
@Configuration
class GigaChatAiConfig(
    @Value("\${ai.gigachat.timeout-ms:5000}") private val timeoutMs: Int
) {
    @Bean
    fun gigaChatRestClient(): RestClient = restClient(GIGA_CHAT_API_URL)

    @Bean
    fun gigaChatOAuthRestClient(): RestClient = restClient(GIGA_CHAT_OAUTH_URL)

    private fun restClient(baseUrl: String): RestClient {
        val timeout = Duration.ofMillis(timeoutMs.toLong())
        val requestFactory = JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(timeout).build()
        ).apply { setReadTimeout(timeout) }
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build()
    }

    private companion object {
        const val GIGA_CHAT_API_URL = "https://api.giga.chat/v1"
        const val GIGA_CHAT_OAUTH_URL = "https://ngw.devices.sberbank.ru:9443"
    }
}
