@file:OptIn(ExperimentalJsExport::class)

package dev.rota

import dev.rota.export.Csv
import dev.rota.export.Ics
import dev.rota.export.Markdown
import dev.rota.solve.Plan
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

// The browser build exposes plain-string functions: JSON in the shape of the HTTP API.

@JsExport
fun solveRota(source: String): String = RotaEngine.planJson(source)

@JsExport
fun rotaIcs(source: String, person: String?, stamp: String): String = Ics.render(planOrThrow(source), person?.ifBlank { null }, stamp)

@JsExport
fun rotaCsv(source: String): String = Csv.render(planOrThrow(source))

@JsExport
fun rotaMarkdown(source: String): String = Markdown.render(planOrThrow(source))

@JsExport
fun rotaExamples(): String = RotaEngine.json.encodeToString(
    ListSerializer(ListSerializer(String.serializer())),
    Examples.all.map { listOf(it.id, it.title, it.source) },
)

private fun planOrThrow(source: String): Plan = RotaEngine.plan(source)
