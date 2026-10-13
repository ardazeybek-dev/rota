package dev.rota.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** A whole rota file: who is available, which shifts exist, and the rules that bind them. */
data class Rota(
    val name: String,
    val start: LocalDate,
    val end: LocalDate,
    val timezone: String?,
    val shifts: List<ShiftDef>,
    val people: List<Person>,
    val skip: Set<LocalDate>,
    val rules: Rules,
) {
    init {
        require(start <= end) { "period start $start is after end $end" }
    }
}

/** A recurring shift, e.g. `night mon-fri 21:00-07:00 needs 1 requires pager`. */
data class ShiftDef(
    val name: String,
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
    val needs: Int,
    val requires: Set<String>,
) {
    /** True when the shift ends on the next calendar day (e.g. 21:00-07:00). */
    val overnight: Boolean get() = end <= start
}

data class Person(
    val name: String,
    /** Relative workload: 1.0 is a full share, 0.5 does half as many shifts. */
    val share: Double = 1.0,
    val skills: Set<String> = emptySet(),
    val off: List<DateRange> = emptyList(),
    val avoid: Preference = Preference.NONE,
    val prefer: Preference = Preference.NONE,
    /** Shifts already worked in earlier periods; keeps fairness across rotas. */
    val history: Int = 0,
    /** Hard cap on shifts this period. */
    val max: Int? = null,
) {
    fun isOff(date: LocalDate): Boolean = off.any { date in it }

    fun canWork(shift: ShiftDef, date: LocalDate): Boolean =
        !isOff(date) && skills.containsAll(shift.requires)
}

data class DateRange(val from: LocalDate, val to: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = date in from..to
    override fun toString(): String = if (from == to) "$from" else "$from..$to"
}

/** Soft preference over weekdays and/or shift names. */
data class Preference(val days: Set<DayOfWeek>, val shifts: Set<String>) {
    fun matches(shift: ShiftDef, date: LocalDate): Boolean =
        date.dayOfWeek in days || shift.name in shifts

    val isEmpty: Boolean get() = days.isEmpty() && shifts.isEmpty()

    companion object {
        val NONE = Preference(emptySet(), emptySet())
    }
}

data class Rules(
    val maxPerDay: Int = 1,
    val maxPerWeek: Int? = null,
    /** Hours of rest between two shifts of one person below which the plan carries a warning. */
    val minRestHours: Int = 11,
    val weights: Weights = Weights(),
)

/**
 * Integer weights of the objective. Fairness is the quadratic term that makes an unequal
 * split expensive; avoid/prefer are soft per-assignment costs; spread pushes a person's
 * shifts apart across weeks.
 */
data class Weights(
    val fairness: Long = 100,
    val avoid: Long = 250,
    val prefer: Long = 60,
    val spread: Long = 20,
)
