package dev.rota.cli

import dev.rota.parse.RotaParseException
import dev.rota.parse.Severity
import dev.rota.solve.Plan
import kotlinx.datetime.LocalDate

/** ANSI styling that collapses to plain text when colour is off. */
class Style(private val on: Boolean) {
    fun c(code: String, s: String) = if (on) "\u001b[${code}m$s\u001b[0m" else s
    fun bold(s: String) = c("1", s)
    fun dim(s: String) = c("2", s)
    fun red(s: String) = c("31", s)
    fun green(s: String) = c("32", s)
    fun yellow(s: String) = c("33", s)
    fun cyan(s: String) = c("36", s)
    fun person(index: Int, s: String) = c("38;5;${PALETTE[index % PALETTE.size]}", s)

    private companion object {
        val PALETTE = intArrayOf(75, 214, 114, 205, 141, 80, 222, 210, 151, 183, 117, 179)
    }
}

/** Renders a plan as week grids, a fairness table and the unfilled shifts with their reasons. */
class Terminal(color: Boolean) {
    private val s = Style(color)
    private val cell = 10

    fun render(plan: Plan): String = buildString {
        val index = plan.people.withIndex().associate { it.value.name to it.index }
        val unfilled = plan.gaps.sumOf { it.missing }
        appendLine()
        append(" ").append(s.bold(plan.name)).append("  ").append(s.dim("${plan.start} → ${plan.end}")).append("  ")
        appendLine(if (unfilled == 0) s.green("✓ fully staffed") else s.red("✗ $unfilled unfilled"))
        appendLine(s.dim(" ${plan.stats.units} shift-person units · solved in ${"%.1f".format(plan.stats.millis)} ms · exact optimum" + (if (plan.stats.certified) " (certified)" else "") + if (plan.stats.swaps > 0) " · ${plan.stats.swaps} balancing swaps" else ""))

        val shiftNames = plan.shifts.map { it.name }
        val labelWidth = maxOf(8, shiftNames.maxOf { it.length } + 2)
        for ((week, slots) in plan.slots.groupBy { it.week }) {
            val monday = LocalDate.parse(slots.first().date).let { it.toEpochDays() - it.dayOfWeek.ordinal }
            val days = (0 until 7).map { LocalDate.fromEpochDays(monday + it) }
            appendLine()
            append(" ").append(s.dim("week ${week + 1}".padEnd(labelWidth)))
            for (d in days) append(s.bold("${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.day.toString().padStart(2, '0')}".padEnd(cell)))
            appendLine()
            for (shift in plan.shifts) {
                val byDate = slots.filter { it.shift == shift.name }.associateBy { it.date }
                if (byDate.isEmpty()) continue
                val rows = byDate.values.maxOf { it.needs }
                for (row in 0 until rows) {
                    append(" ").append(s.cyan((if (row == 0) shift.name else "").padEnd(labelWidth)))
                    for (d in days) {
                        val slot = byDate[d.toString()]
                        val text = when {
                            slot == null || row >= slot.needs -> s.dim("·".padEnd(cell))
                            row < slot.assigned.size -> slot.assigned[row].let { s.person(index.getValue(it), it.take(cell - 1).padEnd(cell)) }
                            else -> s.red("OPEN".padEnd(cell))
                        }
                        append(text)
                    }
                    appendLine()
                }
            }
        }

        appendLine()
        val nameWidth = maxOf(8, plan.people.maxOf { it.name.length } + 2)
        appendLine(" " + s.dim("person".padEnd(nameWidth) + "shifts  fair   load".padEnd(36) + "avoided  preferred  by shift"))
        val maxLoad = maxOf(1, plan.people.maxOf { it.assigned })
        for ((i, p) in plan.people.withIndex()) {
            val bar = "█".repeat((p.assigned * 20 + maxLoad - 1) / maxLoad).padEnd(20, '░')
            append(" ").append(s.person(i, p.name.padEnd(nameWidth)))
            append(p.assigned.toString().padEnd(8)).append(p.target.toString().padEnd(7)).append(s.person(i, bar)).append(" ")
            append(p.avoidedHits.toString().padEnd(9)).append(p.preferredHits.toString().padEnd(11))
            appendLine(s.dim(p.byShift.entries.joinToString(" ") { "${it.key}:${it.value}" }))
        }

        if (plan.gaps.isNotEmpty()) {
            appendLine()
            appendLine(" " + s.red(s.bold("Unfilled shifts")))
            for (g in plan.gaps) {
                appendLine(" " + s.red("✗") + " ${g.date} ${s.bold(g.shift)} — ${g.missing} missing: ${g.reason}")
                for (b in g.blockers) appendLine("     ${s.person(index.getValue(b.person), b.person)}: ${s.dim(b.reason)}")
            }
        }
        if (plan.restWarnings.isNotEmpty()) {
            appendLine()
            appendLine(" " + s.yellow("Short rest"))
            for (r in plan.restWarnings) appendLine("   ${r.person}: ${r.from} → ${r.to} (${"%.1f".format(r.restHours)} h)")
        }
    }
}

/** rustc-style diagnostics: message, location and the offending source line with a caret. */
object Diagnostics {
    fun render(e: RotaParseException, file: String, color: Boolean): String = buildString {
        val s = Style(color)
        val lines = e.source.lines()
        for (d in e.diagnostics) {
            val label = if (d.severity == Severity.ERROR) s.red(s.bold("error")) else s.yellow(s.bold("warning"))
            appendLine("$label: ${s.bold(d.message)}")
            appendLine(s.cyan("  --> ") + "$file:${d.line}:${d.column}")
            val text = lines.getOrNull(d.line - 1) ?: continue
            val gutter = d.line.toString().length
            appendLine(s.cyan(" ".repeat(gutter + 1) + " |"))
            appendLine(s.cyan(" ${d.line} | ") + text)
            appendLine(s.cyan(" ".repeat(gutter + 1) + " | ") + " ".repeat(d.column - 1) + s.red("^".repeat(d.length.coerceAtMost(maxOf(1, text.length - d.column + 1)))))
        }
        val errors = e.diagnostics.count { it.severity == Severity.ERROR }
        append(s.red("$errors error${if (errors == 1) "" else "s"}") + " in $file")
    }
}
