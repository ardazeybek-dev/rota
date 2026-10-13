package dev.rota.parse

import dev.rota.model.DateRange
import dev.rota.model.Person
import dev.rota.model.Preference
import dev.rota.model.Rota
import dev.rota.model.Rules
import dev.rota.model.ShiftDef
import dev.rota.model.Weights
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

enum class Severity { ERROR, WARNING }

/** A problem in the source, pinned to a 1-based line and column. */
data class Diagnostic(
    val severity: Severity,
    val line: Int,
    val column: Int,
    val length: Int,
    val message: String,
) {
    override fun toString(): String = "$line:$column: ${severity.name.lowercase()}: $message"
}

class RotaParseException(val diagnostics: List<Diagnostic>, val source: String) :
    IllegalArgumentException(diagnostics.filter { it.severity == Severity.ERROR }.joinToString("\n"))

data class ParseResult(val rota: Rota, val warnings: List<Diagnostic>)

/**
 * Parses the `.rota` text format. Every line is one statement; `#` starts a comment.
 * All errors in the file are collected before failing, so an editor can mark them at once.
 */
object RotaParser {

    fun parse(source: String): ParseResult = Parse(source).run()

    private class Token(val text: String, val line: Int, val column: Int) {
        val lower: String get() = text.lowercase()
    }

    private class LineError(val token: Token?, override val message: String) : Exception(message)

    private class Parse(val source: String) {
        val diagnostics = mutableListOf<Diagnostic>()

        var name: String? = null
        var period: Pair<LocalDate, LocalDate>? = null
        var periodToken: Token? = null
        var timezone: String? = null
        val shifts = mutableListOf<Pair<ShiftDef, Token>>()
        val people = mutableListOf<Pair<Person, Token>>()
        val skip = mutableSetOf<LocalDate>()
        var maxPerDay = 1
        var maxPerWeek: Int? = null
        var minRest = 11
        var weights = Weights()
        // avoid/prefer may name shifts declared later in the file; check them at the end.
        val shiftRefs = mutableListOf<Token>()

        fun run(): ParseResult {
            source.lines().forEachIndexed { index, raw ->
                val tokens = tokenize(raw, index + 1)
                if (tokens.isEmpty()) return@forEachIndexed
                try {
                    statement(tokens, index + 1, raw)
                } catch (e: LineError) {
                    val t = e.token
                    if (t != null) error(t, e.message) else diagnostics += Diagnostic(Severity.ERROR, index + 1, 1, raw.length.coerceAtLeast(1), e.message)
                }
            }
            return finish()
        }

        fun error(t: Token, message: String) {
            diagnostics += Diagnostic(Severity.ERROR, t.line, t.column, t.text.length.coerceAtLeast(1), message)
        }

        fun warn(t: Token, message: String) {
            diagnostics += Diagnostic(Severity.WARNING, t.line, t.column, t.text.length.coerceAtLeast(1), message)
        }

        fun tokenize(raw: String, line: Int): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '#' -> break
                    c.isWhitespace() -> i++
                    c == '"' -> {
                        val close = raw.indexOf('"', i + 1)
                        if (close < 0) throw LineError(Token(raw.substring(i), line, i + 1), "unterminated quote")
                        out += Token(raw.substring(i + 1, close), line, i + 1)
                        i = close + 1
                    }
                    else -> {
                        val startCol = i
                        while (i < raw.length && !raw[i].isWhitespace() && raw[i] != '#') i++
                        out += Token(raw.substring(startCol, i), line, startCol + 1)
                    }
                }
            }
            // "a, b ,c" -> "a,b,c": a list may be written with spaces after the commas.
            val merged = mutableListOf<Token>()
            for (t in out) {
                val last = merged.lastOrNull()
                if (last != null && (last.text.endsWith(",") || t.text.startsWith(","))) {
                    merged[merged.lastIndex] = Token(last.text + t.text, last.line, last.column)
                } else {
                    merged += t
                }
            }
            return merged
        }

        fun statement(tokens: List<Token>, line: Int, raw: String) {
            val head = tokens[0]
            val args = tokens.drop(1)
            when (head.lower) {
                "rota", "name" -> {
                    if (args.isEmpty()) throw LineError(head, "expected a name after '${head.text}'")
                    name = args.joinToString(" ") { it.text }
                }
                "period" -> {
                    periodToken = head
                    period = parsePeriod(head, args)
                }
                "timezone", "tz" -> {
                    val tz = args.singleOrNull() ?: throw LineError(head, "expected one IANA zone, e.g. 'timezone Europe/Berlin'")
                    if (!Regex("^[A-Za-z_]+(/[A-Za-z0-9_+\\-]+)*$").matches(tz.text)) throw LineError(tz, "'${tz.text}' is not an IANA time zone name")
                    timezone = tz.text
                }
                "skip", "holiday", "holidays" -> {
                    if (args.isEmpty()) throw LineError(head, "expected dates to skip")
                    args.forEach { a -> parseDateRanges(a).forEach { r -> var d = r.from; while (d <= r.to) { skip += d; d = d.plus(1, DateTimeUnit.DAY) } } }
                }
                "shift" -> shifts += parseShift(head, args) to head
                "person", "member" -> people += parsePerson(head, args) to head
                "max-per-day" -> maxPerDay = positiveInt(args.singleOrNull() ?: throw LineError(head, "expected a number"))
                "max-per-week" -> maxPerWeek = positiveInt(args.singleOrNull() ?: throw LineError(head, "expected a number"))
                "min-rest" -> {
                    val t = args.singleOrNull() ?: throw LineError(head, "expected hours, e.g. 'min-rest 11h'")
                    minRest = nonNegativeInt(Token(t.text.lowercase().removeSuffix("h"), t.line, t.column))
                }
                "weight" -> {
                    if (args.size != 2) throw LineError(head, "expected 'weight <fairness|avoid|prefer|spread> <number>'")
                    val v = nonNegativeInt(args[1]).toLong()
                    weights = when (args[0].lower) {
                        "fairness" -> weights.copy(fairness = v)
                        "avoid" -> weights.copy(avoid = v)
                        "prefer" -> weights.copy(prefer = v)
                        "spread" -> weights.copy(spread = v)
                        else -> throw LineError(args[0], "unknown weight '${args[0].text}' (fairness, avoid, prefer, spread)")
                    }
                }
                else -> throw LineError(head, "unknown statement '${head.text}'" + suggest(head.lower, STATEMENTS))
            }
        }

        fun parsePeriod(head: Token, args: List<Token>): Pair<LocalDate, LocalDate> {
            val joined = args.joinToString(" ") { it.text }
            val at = args.firstOrNull() ?: throw LineError(head, "expected 'period <from> .. <to>', 'period 2026-11' or 'period <from> for 4 weeks'")
            Regex("^(\\d{4})-(\\d{2})$").matchEntire(joined)?.let { m ->
                val first = LocalDate(m.groupValues[1].toInt(), m.groupValues[2].toInt().also { if (it !in 1..12) throw LineError(at, "month must be 01-12") }, 1)
                return first to first.plus(DatePeriod(months = 1)).plus(-1, DateTimeUnit.DAY)
            }
            Regex("^(\\S+)\\s+for\\s+(\\d+)\\s+(day|days|week|weeks)$", RegexOption.IGNORE_CASE).matchEntire(joined)?.let { m ->
                val from = date(at, m.groupValues[1])
                val n = m.groupValues[2].toInt()
                if (n < 1) throw LineError(args[1], "length must be at least 1")
                val days = if (m.groupValues[3].startsWith("week", ignoreCase = true)) n * 7 else n
                return from to from.plus(days - 1, DateTimeUnit.DAY)
            }
            val compact = joined.replace(" ", "")
            val parts = compact.split("..")
            if (parts.size != 2) throw LineError(at, "expected 'period <from> .. <to>'")
            val from = date(at, parts[0])
            val to = date(args.last(), parts[1])
            if (to < from) throw LineError(args.last(), "period ends before it starts")
            if (from.plus(3, DateTimeUnit.YEAR) < to) throw LineError(args.last(), "period is longer than three years")
            return from to to
        }

        fun parseShift(head: Token, args: List<Token>): ShiftDef {
            if (args.size < 3) throw LineError(head, "expected 'shift <name> <days> <HH:MM-HH:MM> [needs N] [requires skill]'")
            val nameTok = args[0]
            if (shifts.any { it.first.name.equals(nameTok.text, ignoreCase = true) }) throw LineError(nameTok, "shift '${nameTok.text}' is defined twice")
            if (!IDENT.matches(nameTok.text)) throw LineError(nameTok, "shift names use letters, digits, '-' and '_'")
            val days = parseDays(args[1]) ?: throw LineError(args[1], "unknown days '${args[1].text}' (e.g. daily, weekdays, weekends, mon-fri, sat,sun)")
            val (start, end) = parseTimeRange(args[2])
            var needs = 1
            var requires = emptySet<String>()
            var i = 3
            while (i < args.size) {
                val key = args[i]
                val value = args.getOrNull(i + 1) ?: throw LineError(key, "'${key.text}' needs a value")
                when (key.lower) {
                    "needs", "x" -> needs = positiveInt(value)
                    "requires", "skill", "skills" -> requires = list(value).map { it.lowercase() }.toSet()
                    else -> throw LineError(key, "unknown shift option '${key.text}'" + suggest(key.lower, listOf("needs", "requires")))
                }
                i += 2
            }
            return ShiftDef(nameTok.text, days, start, end, needs, requires)
        }

        fun parsePerson(head: Token, args: List<Token>): Person {
            val nameTok = args.firstOrNull() ?: throw LineError(head, "expected a name after 'person'")
            if (people.any { it.first.name.equals(nameTok.text, ignoreCase = true) }) throw LineError(nameTok, "person '${nameTok.text}' is listed twice")
            var p = Person(nameTok.text)
            var i = 1
            while (i < args.size) {
                val key = args[i]
                val value = args.getOrNull(i + 1) ?: throw LineError(key, "'${key.text}' needs a value")
                p = when (key.lower) {
                    "share" -> {
                        val s = value.text.toDoubleOrNull() ?: throw LineError(value, "share must be a number like 0.5")
                        if (s < 0.1 || s > 10) throw LineError(value, "share must be between 0.1 and 10")
                        p.copy(share = s)
                    }
                    "skills", "skill", "can" -> p.copy(skills = list(value).map { it.lowercase() }.toSet())
                    "off", "away" -> p.copy(off = p.off + parseDateRanges(value))
                    "avoid", "avoids" -> p.copy(avoid = preference(value))
                    "prefer", "prefers", "likes" -> p.copy(prefer = preference(value))
                    "history", "done" -> p.copy(history = nonNegativeInt(value))
                    "max" -> p.copy(max = nonNegativeInt(value))
                    else -> throw LineError(key, "unknown person option '${key.text}'" + suggest(key.lower, PERSON_OPTIONS))
                }
                i += 2
            }
            return p
        }

        fun preference(t: Token): Preference {
            val days = mutableSetOf<DayOfWeek>()
            val shiftNames = mutableSetOf<String>()
            for (item in list(t)) {
                val shift = shifts.firstOrNull { it.first.name.equals(item, ignoreCase = true) }?.first
                val asDays = if (shift == null) parseDays(Token(item, t.line, t.column)) else null
                if (shift != null) shiftNames += shift.name
                else if (asDays != null) days += asDays
                else {
                    shiftNames += item
                    shiftRefs += Token(item, t.line, t.column)
                }
            }
            return Preference(days, shiftNames)
        }

        fun parseDateRanges(t: Token): List<DateRange> = list(t).map { item ->
            val parts = item.split("..")
            when (parts.size) {
                1 -> date(t, parts[0]).let { DateRange(it, it) }
                2 -> {
                    val a = date(t, parts[0]); val b = date(t, parts[1])
                    if (b < a) throw LineError(t, "'$item' ends before it starts")
                    DateRange(a, b)
                }
                else -> throw LineError(t, "'$item' is not a date or date range")
            }
        }

        fun parseTimeRange(t: Token): Pair<LocalTime, LocalTime> {
            val parts = t.text.split("-")
            if (parts.size != 2) throw LineError(t, "expected a time range like 09:00-17:00")
            val a = time(t, parts[0]); val b = time(t, parts[1])
            if (a == b) throw LineError(t, "a shift cannot start and end at the same time")
            return a to b
        }

        fun time(t: Token, s: String): LocalTime {
            val m = Regex("^(\\d{1,2})(?::(\\d{2}))?$").matchEntire(s) ?: throw LineError(t, "'$s' is not a time (HH:MM)")
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h == 24 && min == 0) return LocalTime(0, 0)
            if (h > 23 || min > 59) throw LineError(t, "'$s' is not a valid time")
            return LocalTime(h, min)
        }

        fun date(t: Token, s: String): LocalDate =
            runCatching { LocalDate.parse(s) }.getOrElse { throw LineError(t, "'$s' is not a date (YYYY-MM-DD)") }

        fun positiveInt(t: Token): Int {
            val n = t.text.toIntOrNull() ?: throw LineError(t, "'${t.text}' is not a whole number")
            if (n < 1) throw LineError(t, "must be at least 1")
            return n
        }

        fun nonNegativeInt(t: Token): Int {
            val n = t.text.toIntOrNull() ?: throw LineError(t, "'${t.text}' is not a whole number")
            if (n < 0) throw LineError(t, "must not be negative")
            return n
        }

        fun list(t: Token): List<String> {
            val items = t.text.split(",").map { it.trim() }
            if (items.any { it.isEmpty() }) throw LineError(t, "empty item in list '${t.text}'")
            return items
        }

        fun finish(): ParseResult {
            val anchor = Token("", 1, 1)
            // "Nothing defined" only helps when the file is otherwise clean; after a typo it is noise.
            val clean = diagnostics.none { it.severity == Severity.ERROR }
            if (period == null && (clean || periodToken == null)) error(anchor, "missing 'period' (e.g. 'period 2026-11-02 .. 2026-11-29')")
            if (shifts.isEmpty() && clean) error(anchor, "no shifts defined (e.g. 'shift day weekdays 09:00-17:00')")
            if (people.isEmpty() && clean) error(anchor, "no people listed (e.g. 'person Ana')")

            val shiftNames = shifts.map { it.first.name }.toSet()
            for (ref in shiftRefs) {
                if (ref.text !in shiftNames) error(ref, "'${ref.text}' is neither a weekday nor a shift" + suggest(ref.text, shiftNames.toList() + DAY_NAMES.keys))
            }
            for ((s, tok) in shifts) {
                for (skill in s.requires) {
                    if (people.none { skill in it.first.skills }) warn(tok, "shift '${s.name}' requires '$skill' but nobody has it")
                }
            }
            val p = period
            if (p != null) {
                for ((person, tok) in people) {
                    for (r in person.off) if (r.to < p.first || r.from > p.second) warn(tok, "${person.name} is off $r, outside the period")
                }
            }

            val errors = diagnostics.filter { it.severity == Severity.ERROR }
            if (errors.isNotEmpty() || p == null) throw RotaParseException(diagnostics.sortedWith(compareBy({ it.line }, { it.column })), source)
            val rota = Rota(
                name = name ?: "Rota",
                start = p.first,
                end = p.second,
                timezone = timezone,
                shifts = shifts.map { it.first },
                people = people.map { it.first },
                skip = skip,
                rules = Rules(maxPerDay = maxPerDay, maxPerWeek = maxPerWeek, minRestHours = minRest, weights = weights),
            )
            return ParseResult(rota, diagnostics.filter { it.severity == Severity.WARNING })
        }
    }

    internal val DAY_NAMES: Map<String, DayOfWeek> = linkedMapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )

    private val IDENT = Regex("^[A-Za-z][A-Za-z0-9_-]*$")
    private val STATEMENTS = listOf("rota", "period", "timezone", "skip", "shift", "person", "max-per-day", "max-per-week", "min-rest", "weight")
    private val PERSON_OPTIONS = listOf("share", "skills", "off", "avoid", "prefer", "history", "max")

    /** `daily`, `weekdays`, `weekends`, `mon-fri`, `sat,sun`, `mon,wed-fri`; null when it is not a day spec. */
    fun parseDays(text: String): Set<DayOfWeek>? {
        when (text.lowercase()) {
            "daily", "everyday", "all" -> return DayOfWeek.entries.toSet()
            "weekdays" -> return DayOfWeek.entries.take(5).toSet()
            "weekends", "weekend" -> return setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        }
        val out = mutableSetOf<DayOfWeek>()
        for (part in text.lowercase().split(",")) {
            val range = part.split("-")
            when (range.size) {
                1 -> out += dayName(range[0]) ?: return null
                2 -> {
                    val a = dayName(range[0]) ?: return null
                    val b = dayName(range[1]) ?: return null
                    var d = a.ordinal
                    while (true) {
                        out += DayOfWeek.entries[d]
                        if (d == b.ordinal) break
                        d = (d + 1) % 7
                    }
                }
                else -> return null
            }
        }
        return out
    }

    private fun parseDays(t: Token): Set<DayOfWeek>? = parseDays(t.text)

    private fun dayName(s: String): DayOfWeek? = DAY_NAMES[s.take(3)]?.takeIf {
        s.length == 3 || DayOfWeek.entries.any { d -> d.name.lowercase() == s }
    }

    private fun suggest(word: String, options: Collection<String>): String {
        val best = options.minByOrNull { editDistance(word.lowercase(), it.lowercase()) } ?: return ""
        return if (editDistance(word.lowercase(), best.lowercase()) <= 2) " — did you mean '$best'?" else ""
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
