package dev.rota

import dev.rota.model.DateRange
import dev.rota.model.Person
import dev.rota.model.Preference
import dev.rota.model.Rota
import dev.rota.model.Rules
import dev.rota.model.ShiftDef
import dev.rota.model.Weights
import dev.rota.solve.SlotInstance
import dev.rota.solve.Solver
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The solver claims an exact optimum. On small random rotas we enumerate every feasible
 * assignment and check that nothing beats it — on the JVM and in JavaScript alike.
 */
class BruteForceTest {

    @Test
    fun matchesExhaustiveSearchOnRandomRotas() {
        var checked = 0
        var withGaps = 0
        for (seed in 1..INSTANCES) {
            val rota = randomRota(Random(seed))
            val slots = Solver.slots(rota)
            if (slots.isEmpty()) continue
            val plan = Solver.solve(rota, certify = seed % 10 == 0)
            val best = bruteForce(rota, slots)
            val got = plan.objective
            assertEquals(best.first, got.uncoveredUnits, "seed $seed: uncovered units")
            assertEquals(best.second, got.total, "seed $seed: objective")
            assertFeasible(rota, slots, plan.slots.map { s -> s.assigned.map { n -> rota.people.indexOfFirst { it.name == n } } })
            checked++
            if (got.uncoveredUnits > 0) withGaps++
        }
        assertTrue(checked > INSTANCES * 9 / 10, "only $checked instances were non-trivial")
        assertTrue(withGaps > INSTANCES / 20, "random rotas should include infeasible ones ($withGaps)")
    }

    private fun randomRota(r: Random): Rota {
        val start = LocalDate(2026, 11, 2).plus(r.nextInt(7), DateTimeUnit.DAY) // any weekday, so weeks split
        val days = 1 + r.nextInt(4)
        val shifts = (0 until 1 + r.nextInt(2)).map { i ->
            ShiftDef(
                name = "s$i",
                days = DayOfWeek.entries.filter { r.nextInt(4) != 0 }.toSet().ifEmpty { DayOfWeek.entries.toSet() },
                start = LocalTime(if (i == 0) 8 else 20, 0),
                end = LocalTime(if (i == 0) 16 else 4, 0),
                needs = if (r.nextInt(5) == 0) 2 else 1,
                requires = if (r.nextInt(3) == 0) setOf("k") else emptySet(),
            )
        }
        val people = (0 until 2 + r.nextInt(2)).map { i ->
            fun prefs() = if (r.nextBoolean()) Preference.NONE else Preference(setOf(DayOfWeek.entries[r.nextInt(7)]), if (r.nextBoolean()) setOf("s0") else emptySet())
            val offDay = start.plus(r.nextInt(days), DateTimeUnit.DAY)
            Person(
                name = "p$i",
                share = listOf(0.5, 1.0, 1.0, 1.5)[r.nextInt(4)],
                skills = if (r.nextInt(3) != 0) setOf("k") else emptySet(),
                off = if (r.nextInt(3) == 0) listOf(DateRange(offDay, offDay)) else emptyList(),
                avoid = prefs(),
                prefer = prefs(),
                history = r.nextInt(4),
                max = if (r.nextInt(4) == 0) 1 + r.nextInt(2) else null,
            )
        }
        return Rota(
            name = "random", start = start, end = start.plus(days - 1, DateTimeUnit.DAY), timezone = null,
            shifts = shifts, people = people, skip = emptySet(),
            rules = Rules(
                maxPerDay = 1 + r.nextInt(2),
                maxPerWeek = if (r.nextBoolean()) 1 + r.nextInt(3) else null,
                weights = Weights(fairness = 10L + r.nextInt(200), avoid = r.nextInt(300).toLong(), prefer = r.nextInt(100).toLong(), spread = r.nextInt(50).toLong()),
            ),
        )
    }

    /** Lexicographic (uncovered units, objective) minimum over every feasible assignment. */
    private fun bruteForce(rota: Rota, slots: List<SlotInstance>): Pair<Int, Long> {
        val choices = slots.map { slot ->
            val eligible = rota.people.indices.filter { rota.people[it].canWork(slot.shift, slot.date) }
            subsets(eligible).filter { it.size <= slot.shift.needs }
        }
        val current = MutableList(slots.size) { emptyList<Int>() }
        var best = Int.MAX_VALUE to Long.MAX_VALUE
        fun go(i: Int) {
            if (i == slots.size) {
                if (!feasible(rota, slots, current)) return
                val o = Solver.evaluate(rota, slots, current)
                val cand = o.uncoveredUnits to o.total
                if (cand.first < best.first || (cand.first == best.first && cand.second < best.second)) best = cand
                return
            }
            for (c in choices[i]) { current[i] = c; go(i + 1) }
        }
        go(0)
        return best
    }

    private fun subsets(items: List<Int>): List<List<Int>> =
        (0 until (1 shl items.size)).map { mask -> items.filterIndexed { i, _ -> mask and (1 shl i) != 0 } }

    private fun feasible(rota: Rota, slots: List<SlotInstance>, assigned: List<List<Int>>): Boolean {
        val perDay = HashMap<Pair<Int, LocalDate>, Int>()
        val perWeek = HashMap<Pair<Int, Int>, Int>()
        val total = IntArray(rota.people.size)
        for (slot in slots) for (pi in assigned[slot.index]) {
            if (perDay.merge(pi to slot.date, 1, Int::plus)!! > rota.rules.maxPerDay) return false
            if (perWeek.merge(pi to slot.week, 1, Int::plus)!! > (rota.rules.maxPerWeek ?: Int.MAX_VALUE)) return false
            if (++total[pi] > (rota.people[pi].max ?: Int.MAX_VALUE)) return false
        }
        return true
    }

    private fun assertFeasible(rota: Rota, slots: List<SlotInstance>, assigned: List<List<Int>>) {
        assertTrue(feasible(rota, slots, assigned), "solver plan breaks a hard rule")
        for (slot in slots) for (pi in assigned[slot.index]) assertTrue(rota.people[pi].canWork(slot.shift, slot.date))
    }

    private fun <K> HashMap<K, Int>.merge(key: K, value: Int, f: (Int, Int) -> Int): Int? {
        val v = this[key]?.let { f(it, value) } ?: value
        this[key] = v
        return v
    }

    private companion object {
        val INSTANCES = bruteForceInstances
    }
}

/** Exhaustive search is ~70x slower on Kotlin/JS (emulated Long), so JS checks fewer seeds. */
expect val bruteForceInstances: Int
