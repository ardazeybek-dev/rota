package dev.rota.export

import dev.rota.solve.Plan
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * RFC 5545 calendar for one person (or everyone when [person] is null), ready to import into
 * Google Calendar, Outlook or Apple Calendar.
 */
object Ics {
    fun render(plan: Plan, person: String? = null, stamp: String = "20260101T000000Z"): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//rota//rota planner//EN",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:" + escape(if (person == null) plan.name else "${plan.name} — $person"),
        )
        plan.timezone?.let { lines += "X-WR-TIMEZONE:$it" }
        val shifts = plan.shifts.associateBy { it.name }
        for (slot in plan.slots) {
            val who = if (person == null) slot.assigned else slot.assigned.filter { it.equals(person, ignoreCase = true) }
            for (name in who) {
                val shift = shifts.getValue(slot.shift)
                val date = LocalDate.parse(slot.date)
                val endDate = if (shift.overnight) date.plus(1, DateTimeUnit.DAY) else date
                lines += "BEGIN:VEVENT"
                lines += "UID:" + uid(plan.name, slot.date, slot.shift, name)
                lines += "DTSTAMP:$stamp"
                lines += "DTSTART" + tz(plan) + ":" + dateTime(date, slot.start)
                lines += "DTEND" + tz(plan) + ":" + dateTime(endDate, slot.end)
                lines += "SUMMARY:" + escape(if (person == null) "${slot.shift} — $name" else "${slot.shift} shift (${plan.name})")
                val crew = slot.assigned.filter { it != name }
                if (crew.isNotEmpty()) lines += "DESCRIPTION:" + escape("With ${crew.joinToString()}")
                lines += "CATEGORIES:" + escape(slot.shift)
                lines += "TRANSP:OPAQUE"
                lines += "END:VEVENT"
            }
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    private fun tz(plan: Plan) = plan.timezone?.let { ";TZID=$it" } ?: ""

    private fun dateTime(date: LocalDate, time: String): String =
        date.toString().replace("-", "") + "T" + time.replace(":", "").padEnd(6, '0')

    /** Stable UID so re-importing an updated rota replaces events instead of duplicating them. */
    private fun uid(vararg parts: String): String {
        var h = 1469598103934665603L
        for (c in parts.joinToString("|")) { h = h xor c.code.toLong(); h *= 1099511628211L }
        return h.toULong().toString(16).padStart(16, '0') + "@rota"
    }

    internal fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            ';' -> append("\\;")
            ',' -> append("\\,")
            '\n' -> append("\\n")
            '\r' -> {}
            else -> append(c)
        }
    }

    /**
     * Folds a content line at 75 octets (not characters): continuation lines start with a space,
     * and a multi-byte UTF-8 character is never split across the fold.
     */
    internal fun fold(line: String): String {
        val out = StringBuilder()
        var octets = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line[i]
            val len = if (cp.isHighSurrogate() && i + 1 < line.length) 2 else 1
            val bytes = utf8Length(line, i, len)
            if (octets + bytes > limit) {
                out.append("\r\n ")
                octets = 0
                limit = 74 // the leading space counts toward the next 75
            }
            out.append(line, i, i + len)
            octets += bytes
            i += len
        }
        return out.toString()
    }

    private fun utf8Length(s: String, i: Int, len: Int): Int {
        if (len == 2) return 4
        val c = s[i].code
        return when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            else -> 3
        }
    }
}

object Csv {
    fun render(plan: Plan): String = buildString {
        append("date,weekday,shift,start,end,person\r\n")
        for (slot in plan.slots) {
            val names = slot.assigned.ifEmpty { listOf("") }
            for (n in names) append(listOf(slot.date, slot.weekday, slot.shift, slot.start, slot.end, n).joinToString(",") { cell(it) }).append("\r\n")
        }
    }

    private fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' } || s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) {
            // Quote, and defuse spreadsheet formula injection from user-typed names.
            "\"" + (if (s.firstOrNull() in listOf('=', '+', '-', '@')) "'" else "") + s.replace("\"", "\"\"") + "\""
        } else s
}

object Markdown {
    fun render(plan: Plan): String = buildString {
        appendLine("# ${plan.name}")
        appendLine()
        appendLine("${plan.start} → ${plan.end} · ${plan.stats.units} shift-person units · " + if (plan.complete) "fully staffed" else "**${plan.gaps.sumOf { it.missing }} unfilled**")
        appendLine()
        appendLine("| Date | Day | " + plan.shifts.joinToString(" | ") { it.name } + " |")
        appendLine("|---|---|" + plan.shifts.joinToString("") { "---|" })
        for ((date, slots) in plan.slots.groupBy { it.date }) {
            val byShift = slots.associateBy { it.shift }
            append("| $date | ${slots.first().weekday} | ")
            append(plan.shifts.joinToString(" | ") { s ->
                val slot = byShift[s.name] ?: return@joinToString ""
                val names = slot.assigned.joinToString(", ")
                if (slot.open > 0) (if (names.isEmpty()) "" else "$names, ") + "⚠ ${slot.open} open" else names
            })
            appendLine(" |")
        }
        appendLine()
        appendLine("| Person | Shifts | Fair target | Avoided | Preferred |")
        appendLine("|---|---:|---:|---:|---:|")
        for (p in plan.people) appendLine("| ${p.name} | ${p.assigned} | ${p.target} | ${p.avoidedHits} | ${p.preferredHits} |")
        if (plan.gaps.isNotEmpty()) {
            appendLine()
            appendLine("## Unfilled")
            for (g in plan.gaps) {
                appendLine("- **${g.date} ${g.shift}** (${g.missing} missing): ${g.reason}")
                for (b in g.blockers) appendLine("  - ${b.person}: ${b.reason}")
            }
        }
    }
}
