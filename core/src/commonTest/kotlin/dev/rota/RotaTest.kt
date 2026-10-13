package dev.rota

import dev.rota.export.Csv
import dev.rota.export.Ics
import dev.rota.parse.RotaParseException
import dev.rota.parse.RotaParser
import dev.rota.parse.Severity
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserTest {
    @Test
    fun allExamplesParse() {
        for (e in Examples.all) RotaParser.parse(e.source)
    }

    @Test
    fun periodForms() {
        fun period(p: String) = RotaParser.parse("period $p\nshift a daily 9-17\nperson X").rota.let { it.start to it.end }
        assertEquals(LocalDate(2026, 2, 1) to LocalDate(2026, 2, 28), period("2026-02"))
        assertEquals(LocalDate(2026, 11, 2) to LocalDate(2026, 11, 15), period("2026-11-02 for 2 weeks"))
        assertEquals(LocalDate(2026, 11, 2) to LocalDate(2026, 11, 4), period("2026-11-02..2026-11-04"))
        assertEquals(LocalDate(2026, 11, 2) to LocalDate(2026, 11, 4), period("2026-11-02 .. 2026-11-04"))
    }

    @Test
    fun daySpecs() {
        assertEquals(setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY), RotaParser.parseDays("fri-mon"))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), RotaParser.parseDays("Mon,wed-fri"))
        assertEquals(null, RotaParser.parseDays("night"))
    }

    @Test
    fun listsMayHaveSpacesAfterCommas() {
        val rota = RotaParser.parse("period 2026-11\nshift a mon, wed 9-17\nperson X skills a, b").rota
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), rota.shifts[0].days)
        assertEquals(setOf("a", "b"), rota.people[0].skills)
    }

    @Test
    fun collectsEveryErrorWithPositionAndSuggestion() {
        val e = assertFailsWith<RotaParseException> {
            RotaParser.parse(
                """
                period 2026-11-02 .. 2026-11-30
                shift day weekdays 09:00-25:00
                persn Ana
                person Ben avoid nite
                """.trimIndent(),
            )
        }
        val errors = e.diagnostics.filter { it.severity == Severity.ERROR }
        assertEquals(listOf(2, 3, 4), errors.map { it.line })
        assertTrue(errors[0].message.contains("'25:00' is not a valid time"), errors[0].message)
        assertTrue(errors[1].message.contains("did you mean 'person'"), errors[1].message)
        assertEquals(1, errors[1].column)
        assertTrue(errors[2].message.contains("neither a weekday nor a shift"))
    }

    @Test
    fun warnsAboutSkillNobodyHas() {
        val r = RotaParser.parse("period 2026-11\nshift n daily 22-06 requires medic\nperson A")
        assertTrue(r.warnings.single().message.contains("medic"))
    }
}

class SolverTest {
    @Test
    fun onCallExampleIsFullyStaffedAndFair() {
        val parsed = RotaParser.parse(Examples.ONCALL.source).rota
        val plan = RotaEngine.plan(Examples.ONCALL.source, certify = true)
        assertTrue(plan.complete, "gaps: ${plan.gaps}")
        assertTrue(plan.stats.certified)
        val byDayAndPerson = plan.slots.flatMap { s -> s.assigned.map { s.date to it } }
        assertEquals(byDayAndPerson.size, byDayAndPerson.toSet().size, "max-per-day 1 violated")
        for (slot in plan.slots) for (name in slot.assigned) {
            val p = parsed.people.first { it.name == name }
            val shift = parsed.shifts.first { it.name == slot.shift }
            assertTrue(p.canWork(shift, LocalDate.parse(slot.date)), "$name cannot work $slot")
        }
        for (p in plan.people) assertTrue(p.perWeek.all { it <= 3 }, "${p.name} over max-per-week: ${p.perWeek}")
        assertTrue(plan.slots.none { it.date == "2026-11-26" }, "skipped day still has shifts")
        // History carries over: Chen starts the month 4 shifts behind Emre, so Chen takes more.
        val load = plan.people.associate { it.name to it.assigned }
        assertTrue(load.getValue("Chen") > load.getValue("Emre"), load.toString())
    }

    @Test
    fun impossibleExampleExplainsTheGaps() {
        val plan = RotaEngine.plan(Examples.IMPOSSIBLE.source)
        assertTrue(plan.gaps.isNotEmpty())
        val gap = plan.gaps.first()
        assertTrue(gap.blockers.isNotEmpty() || gap.reason.startsWith("nobody"), gap.toString())
        for (g in plan.gaps) assertTrue(g.reason.isNotBlank())
    }

    @Test
    fun sharesAreHonoured() {
        val plan = RotaEngine.plan(Examples.FLAT.source)
        val sam = plan.people.first { it.name == "Sam" }
        val lea = plan.people.first { it.name == "Lea" }
        assertTrue(sam.assigned * 2 <= lea.assigned + 2, "Sam ${sam.assigned} vs Lea ${lea.assigned}")
    }

    @Test
    fun nobodyQualifiedNamesTheReason() {
        val plan = RotaEngine.plan("period 2026-11-02 for 1 days\nshift n daily 22-06 requires medic\nperson A off 2026-11-02\nperson B")
        val gap = plan.gaps.single()
        assertTrue(gap.reason.contains("A is off") && gap.reason.contains("B lacks 'medic'"), gap.reason)
    }

    @Test
    fun restWarningsCatchNightThenMorning() {
        val plan = RotaEngine.plan("period 2026-11-02 for 2 days\nshift night mon 22:00-06:00\nshift early tue 07:00-15:00\nperson Solo")
        assertEquals(1.0, plan.restWarnings.single().restHours)
    }

    @Test
    fun jsonErrorResponseCarriesDiagnostics() {
        val body = RotaEngine.planJson("shift x daily 9-17")
        assertTrue(body.contains("\"ok\":false") && body.contains("missing 'period'"), body)
    }
}

class ExportTest {
    private val plan = RotaEngine.plan(Examples.ONCALL.source)

    @Test
    fun icsLinesAreFoldedAt75OctetsWithCrlf() {
        val long = RotaEngine.plan("rota \"Ünïcödé — ${"ş".repeat(60)}\"\nperiod 2026-11-02 for 1 days\nshift a daily 9-17\nperson Çağla")
        val ics = Ics.render(long)
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        for (line in ics.split("\r\n")) assertTrue(line.encodeToByteArray().size <= 75, "too long: $line")
        assertTrue(ics.split("\r\n").any { it.startsWith(" ") }, "expected a folded line")
    }

    @Test
    fun overnightShiftEndsNextDayInTheRightZone() {
        val ics = Ics.render(plan, "Ana")
        assertTrue(ics.contains("DTSTART;TZID=Europe/Berlin:"))
        val night = ics.split("BEGIN:VEVENT").first { it.contains("SUMMARY:night") }
        val start = Regex("DTSTART;TZID=Europe/Berlin:(\\d{8})").find(night)!!.groupValues[1]
        val end = Regex("DTEND;TZID=Europe/Berlin:(\\d{8})").find(night)!!.groupValues[1]
        assertEquals(start.toInt() + 1, end.toInt())
    }

    @Test
    fun icsEscapesTextValues() = assertEquals("a\\, b\\; c\\\\d\\ne", Ics.escape("a, b; c\\d\ne"))

    @Test
    fun csvDefusesFormulaNames() {
        val csv = Csv.render(RotaEngine.plan("period 2026-11-02 for 1 days\nshift a daily 9-17\nperson \"=HYPERLINK(1)\""))
        assertTrue(csv.contains("\"'=HYPERLINK(1)\""), csv)
    }
}
