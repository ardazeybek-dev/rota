package dev.rota.server

import dev.rota.Examples
import dev.rota.RotaEngine
import dev.rota.export.Csv
import dev.rota.export.Ics
import dev.rota.export.Markdown
import dev.rota.parse.RotaParseException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("rota")
const val VERSION = "1.0.0"

fun main() {
    val config = Config.load()
    log.info("rota {} listening on http://{}:{} (max {} units, {} req/min)", VERSION, config.host, config.port, config.maxUnits, config.rateLimitPerMinute)
    embeddedServer(Netty, port = config.port, host = config.host) { rota(config) }.start(wait = true)
}

@Serializable
data class Health(val status: String, val version: String)

@Serializable
data class ExampleDto(val id: String, val title: String, val source: String)

@Serializable
data class ErrorDto(val ok: Boolean = false, val error: String)

class BodyTooLarge(limit: Int) : RuntimeException("request body is larger than ${limit / 1024} KiB")

fun Application.rota(config: Config) {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
    }
    install(CallLogging) { level = Level.INFO }
    install(ContentNegotiation) { json(RotaEngine.json) }
    if (config.corsOrigins.isNotEmpty()) {
        install(CORS) {
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            if ("*" in config.corsOrigins) anyHost() else config.corsOrigins.forEach { allowHost(it.substringAfter("://"), schemes = listOf(it.substringBefore("://", "https"))) }
        }
    }
    if (config.rateLimitPerMinute > 0) {
        install(RateLimit) {
            register(API) { rateLimiter(limit = config.rateLimitPerMinute, refillPeriod = 1.minutes) }
        }
    }
    install(StatusPages) {
        exception<BodyTooLarge> { call, e -> call.respond(HttpStatusCode.PayloadTooLarge, ErrorDto(error = e.message!!)) }
        exception<RotaParseException> { call, e ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto(error = e.diagnostics.joinToString("\n")))
        }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto(error = e.message ?: "invalid request")) }
        exception<Throwable> { call, e ->
            log.error("unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto(error = "internal error"))
        }
    }

    routing {
        get("/api/health") { call.respond(Health("ok", VERSION)) }
        get("/api/examples") { call.respond(Examples.all.map { ExampleDto(it.id, it.title, it.source) }) }
        limited(config.rateLimitPerMinute > 0) {
            post("/api/solve") {
                val body = RotaEngine.planJson(call.readSource(config), maxUnits = config.maxUnits)
                val status = if (body.startsWith("{\"ok\":true")) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity
                call.respond(TextContent(body, ContentType.Application.Json, status))
            }
            post("/api/export/{format}") {
                val plan = RotaEngine.plan(call.readSource(config), maxUnits = config.maxUnits)
                val person = call.request.queryParameters["person"]?.takeIf { it.isNotBlank() }
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
                val slug = (person ?: plan.name).replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "rota" }
                val (text, type, ext) = when (call.parameters["format"]) {
                    "ics" -> Triple(Ics.render(plan, person, stamp), ContentType("text", "calendar"), "ics")
                    "csv" -> Triple(Csv.render(plan), ContentType.Text.CSV, "csv")
                    "md" -> Triple(Markdown.render(plan), ContentType("text", "markdown"), "md")
                    else -> throw IllegalArgumentException("format must be ics, csv or md")
                }
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$slug.$ext\"")
                call.respondText(text, type.withCharset(Charsets.UTF_8))
            }
        }
        if (config.serveWeb) staticResources("/", "web")
    }
}

private val API = RateLimitName("api")

private fun Route.limited(enabled: Boolean, build: Route.() -> Unit) {
    if (enabled) rateLimit(API) { build() } else route("") { build() }
}

/** Accepts the rota as a text/plain body, or as JSON `{"source": "..."}`. */
private suspend fun ApplicationCall.readSource(config: Config): String {
    val bytes = receiveChannel().readRemaining(config.maxBodyBytes.toLong() + 1).readByteArray()
    if (bytes.size > config.maxBodyBytes) throw BodyTooLarge(config.maxBodyBytes)
    val text = bytes.toString(Charsets.UTF_8)
    if (request.contentType().match(ContentType.Application.Json)) {
        return Json.parseToJsonElement(text).jsonObject["source"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("JSON body needs a \"source\" field")
    }
    return text
}
