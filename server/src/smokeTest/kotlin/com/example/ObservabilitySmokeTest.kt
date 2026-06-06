package com.example

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ObservabilitySmokeTest {
    @Test
    fun `response carries a generated X-Request-Id when none is sent`() =
        testApplication {
            application { configure(ReadinessGate().also { it.markReady() }) }
            val response = client.get("/health/live")
            assertEquals(HttpStatusCode.OK, response.status)
            assertNotNull(response.headers[HttpHeaders.XRequestId], "CallId should mint and echo a request id")
        }

    @Test
    fun `inbound X-Request-Id is echoed back`() =
        testApplication {
            application { configure(ReadinessGate().also { it.markReady() }) }
            val response =
                client.get("/health/live") {
                    header(HttpHeaders.XRequestId, "smoke-req-123")
                }
            assertEquals("smoke-req-123", response.headers[HttpHeaders.XRequestId])
        }
}
