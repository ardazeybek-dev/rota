package dev.rota.cli

import kotlin.random.Random

/** Deterministic large rota for `rota bench`: a hospital-style ward with skills, leave and preferences. */
object Synthetic {
    fun rota(people: Int, weeks: Int, seed: Int = 7): String = buildString {
        val r = Random(seed)
        appendLine("rota \"Synthetic ward — $people staff, $weeks weeks\"")
        appendLine("period 2027-01-04 for $weeks weeks")
        appendLine("shift early daily 07:00-15:00 needs ${maxOf(1, people / 10)}")
        appendLine("shift late  daily 15:00-23:00 needs ${maxOf(1, people / 12)}")
        appendLine("shift night daily 23:00-07:00 needs ${maxOf(1, people / 16)} requires nights")
        appendLine("shift admin weekdays 09:00-17:00")
        val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        for (i in 1..people) {
            append("person staff$i")
            if (r.nextInt(3) != 0) append(" skills nights")
            if (r.nextInt(5) == 0) append(" share 0.5")
            if (r.nextInt(3) == 0) {
                val start = r.nextInt(weeks * 7 - 7)
                append(" off 2027-01-04..2027-01-04".replace("2027-01-04..2027-01-04", range(start, start + 4 + r.nextInt(7))))
            }
            if (r.nextBoolean()) append(" avoid ${days[r.nextInt(7)]}")
            if (r.nextInt(3) == 0) append(" prefer ${listOf("early", "late", "night")[r.nextInt(3)]}")
            append(" history ${r.nextInt(30)}")
            appendLine()
        }
        appendLine("max-per-day 1")
        appendLine("max-per-week 5")
    }

    private fun range(from: Int, to: Int): String {
        val base = kotlinx.datetime.LocalDate(2027, 1, 4).toEpochDays()
        return "${kotlinx.datetime.LocalDate.fromEpochDays(base + from)}..${kotlinx.datetime.LocalDate.fromEpochDays(base + to)}"
    }
}
