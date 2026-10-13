package dev.rota.server

import dev.rota.Examples
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    private val config = Config.load(env = emptyMap(), dotenv = Files.createTempFile("missing", ".env").also { Files.delete(it) })
        .copy(rateLimitPerMinute = 0)

    @Test
    fun solvesPlainTextAndJsonBodies() = testApplication {
        application { rota(config) }
        val plain = client.post("/api/solve") { setBody(Examples.FLAT.source) }
        assertEquals(HttpStatusCode.OK, plain.status)
        assertTrue(plain.bodyAsText().contains("\"name\":\"Flat 3B chores\""))

        val json = client.post("/api/solve") {
            contentType(ContentType.Application.Json)
            setBody("{\"source\": ${kotlinx.serialization.json.JsonPrimitive(Examples.CAFE.source)}}")
        }
        assertEquals(HttpStatusCode.OK, json.status)
    }

    @Test
    fun parseErrorsAre422WithDiagnostics() = testApplication {
        application { rota(config) }
        val r = client.post("/api/solve") { setBody("period 2026-11\nshift a daily 9-17\npersn X") }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertTrue(r.bodyAsText().contains("did you mean 'person'"))
    }

    @Test
    fun exportsIcsForOnePerson() = testApplication {
        application { rota(config) }
        val r = client.post("/api/export/ics?person=Ana") { setBody(Examples.ONCALL.source) }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.ContentDisposition]!!.contains("Ana.ics"))
        assertTrue(r.bodyAsText().startsWith("BEGIN:VCALENDAR\r\n"))
    }

    @Test
    fun rejectsOversizedBodies() = testApplication {
        application { rota(config.copy(maxBodyBytes = 1024)) }
        val r = client.post("/api/solve") { setBody("#".repeat(5000)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
    }

    @Test
    fun healthAndExamples() = testApplication {
        application { rota(config) }
        assertTrue(client.get("/api/health").bodyAsText().contains("\"ok\""))
        assertTrue(client.get("/api/examples").bodyAsText().contains("oncall"))
    }

    @Test
    fun dotenvIsReadButRealEnvironmentWins() {
        val file = Files.createTempFile("rota", ".env")
        Files.writeString(file, "# comment\nROTA_PORT=9000\nexport ROTA_MAX_UNITS=\"77\"\nROTA_RATE_LIMIT=5 # per minute\n")
        val c = Config.load(env = mapOf("ROTA_PORT" to "9100"), dotenv = file)
        assertEquals(9100, c.port)
        assertEquals(77, c.maxUnits)
        assertEquals(5, c.rateLimitPerMinute)
    }
}
