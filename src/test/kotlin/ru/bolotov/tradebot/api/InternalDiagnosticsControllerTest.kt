package ru.bolotov.tradebot.api

import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import ru.bolotov.tradebot.config.WebSecurityConfig
import ru.bolotov.tradebot.service.CandleExportService
import ru.bolotov.tradebot.service.CandleExportUnavailableException
import java.time.Instant
import java.util.Base64
import java.util.UUID

@WebMvcTest(InternalDiagnosticsController::class)
@Import(WebSecurityConfig::class)
@TestPropertySource(properties = ["internal.api.username=diagnostic-test", "internal.api.password=test-only-password"])
class InternalDiagnosticsControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @MockBean lateinit var service: CandleExportService

    private val uid = UUID.fromString("c7485564-ed92-45fd-a724-1214aa202904")
    private val from = Instant.parse("2020-01-01T00:00:00Z")
    private val to = from.plusSeconds(86400)
    private val auth = "Basic " + Base64.getEncoder().encodeToString("diagnostic-test:test-only-password".toByteArray())

    private fun request() = get("/internal/diagnostics/candles.csv")
        .param("instrumentUid", uid.toString()).param("from", from.toString()).param("to", to.toString())

    @Test
    fun `anonymous access is rejected`() {
        mvc.perform(request()).andExpect(status().isUnauthorized)
    }

    @Test
    fun `authenticated export is downloaded as uncached CSV`() {
        `when`(service.export(uid, from, to)).thenReturn("instrument_uid,interval\n")
        mvc.perform(request().header(HttpHeaders.AUTHORIZATION, auth))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/csv;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"candles-$uid-m5.csv\""))
    }

    @Test
    fun `broker failure returns gateway error without provider details`() {
        `when`(service.export(uid, from, to)).thenThrow(CandleExportUnavailableException(IllegalStateException("private transport details")))
        mvc.perform(request().header(HttpHeaders.AUTHORIZATION, auth))
            .andExpect(status().isBadGateway)
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private transport details"))))
    }
}
