package dev.rota.server

import java.nio.file.Files
import java.nio.file.Path

/**
 * Server settings from the environment. A `.env` file in the working directory is read
 * first; real environment variables always win over it.
 */
data class Config(
    val host: String,
    val port: Int,
    val maxUnits: Int,
    val maxBodyBytes: Int,
    val rateLimitPerMinute: Int,
    val corsOrigins: List<String>,
    val serveWeb: Boolean,
) {
    companion object {
        fun load(env: Map<String, String> = System.getenv(), dotenv: Path = Path.of(".env")): Config {
            val merged = readDotenv(dotenv) + env
            fun get(key: String) = merged[key]?.trim()?.takeIf { it.isNotEmpty() }
            fun int(key: String, default: Int, range: IntRange): Int {
                val raw = get(key) ?: return default
                val n = raw.toIntOrNull() ?: error("$key must be a whole number, got '$raw'")
                require(n in range) { "$key must be in $range, got $n" }
                return n
            }
            return Config(
                host = get("ROTA_HOST") ?: "0.0.0.0",
                port = int("PORT", int("ROTA_PORT", 8080, 1..65535), 1..65535),
                maxUnits = int("ROTA_MAX_UNITS", 5_000, 1..100_000),
                maxBodyBytes = int("ROTA_MAX_BODY_KB", 256, 1..10_240) * 1024,
                rateLimitPerMinute = int("ROTA_RATE_LIMIT", 120, 0..100_000),
                corsOrigins = get("ROTA_CORS_ORIGINS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                serveWeb = get("ROTA_SERVE_WEB")?.lowercase() != "false",
            )
        }

        /** Minimal dotenv: `KEY=value`, `export KEY=value`, `#` comments, optional quotes. */
        internal fun readDotenv(path: Path): Map<String, String> {
            if (!Files.isRegularFile(path)) return emptyMap()
            return Files.readAllLines(path).mapNotNull { raw ->
                val line = raw.trim().removePrefix("export ").trim()
                if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
                val key = line.substringBefore('=').trim()
                var value = line.substringAfter('=').trim()
                if (value.length >= 2 && value.first() == value.last() && value.first() in "\"'") value = value.substring(1, value.length - 1)
                else value = value.substringBefore(" #").trim()
                key to value
            }.toMap()
        }
    }
}
