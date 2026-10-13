package dev.rota

import dev.rota.parse.Diagnostic
import dev.rota.parse.RotaParseException
import dev.rota.parse.RotaParser
import dev.rota.solve.Plan
import dev.rota.solve.Solver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One-call entry point shared by the CLI, the HTTP server and the browser build. */
object RotaEngine {
    val json = Json { prettyPrint = false; encodeDefaults = true; explicitNulls = false }
    val prettyJson = Json(json) { prettyPrint = true }

    fun plan(source: String, certify: Boolean = false, maxUnits: Int = Solver.DEFAULT_MAX_UNITS): Plan {
        val parsed = RotaParser.parse(source)
        return Solver.solve(parsed.rota, certify, maxUnits).copy(warnings = parsed.warnings.map { it.toString() })
    }

    /** Never throws: parse errors and limits come back as a JSON error body the UI can render. */
    fun planJson(source: String, maxUnits: Int = Solver.DEFAULT_MAX_UNITS): String = try {
        json.encodeToString(Response.serializer(), Response(ok = true, plan = plan(source, maxUnits = maxUnits)))
    } catch (e: RotaParseException) {
        json.encodeToString(Response.serializer(), Response(ok = false, error = "The rota file has errors", diagnostics = e.diagnostics.map { it.toDto() }))
    } catch (e: IllegalArgumentException) {
        json.encodeToString(Response.serializer(), Response(ok = false, error = e.message ?: "invalid rota"))
    }

    @Serializable
    data class Response(
        val ok: Boolean,
        val plan: Plan? = null,
        val error: String? = null,
        val diagnostics: List<DiagnosticDto> = emptyList(),
    )

    @Serializable
    data class DiagnosticDto(val severity: String, val line: Int, val column: Int, val length: Int, val message: String)

    private fun Diagnostic.toDto() = DiagnosticDto(severity.name.lowercase(), line, column, length, message)
}
