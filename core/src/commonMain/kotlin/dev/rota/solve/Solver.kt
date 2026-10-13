package dev.rota.solve

import dev.rota.model.Person
import dev.rota.model.Rota
import dev.rota.model.ShiftDef
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.TimeSource

/** One concrete occurrence of a shift on a date. */
data class SlotInstance(val index: Int, val date: LocalDate, val shift: ShiftDef, val week: Int)

/**
 * Turns a [Rota] into a min-cost flow network and reads the plan back out.
 *
 * ```
 * source ─needs─▶ slot ─1─▶ (person, day) ─maxPerDay─▶ (person, week) ═spread═▶ person ═fairness═▶ sink
 *                  └──────────────── needs, cost UNCOVERED ──────────────────────────────────────────▲
 * ```
 * Day ⊂ week ⊂ period is a laminar family, so every hard rule is a capacity on a tree of
 * nodes and the problem stays a network flow. The convex terms (fairness, spread) are parallel
 * unit arcs with increasing costs, so the min-cost flow is the exact optimum of the objective.
 */
object Solver {
    const val UNCOVERED = 10_000_000_000_000L
    const val DEFAULT_MAX_UNITS = 25_000

    fun slots(rota: Rota): List<SlotInstance> {
        val monday = rota.start.minus(rota.start.dayOfWeek.ordinal, DateTimeUnit.DAY)
        val out = mutableListOf<SlotInstance>()
        var d = rota.start
        while (d <= rota.end) {
            if (d !in rota.skip) {
                val week = (d.toEpochDays() - monday.toEpochDays()).toInt() / 7
                for (s in rota.shifts) if (d.dayOfWeek in s.days) out += SlotInstance(out.size, d, s, week)
            }
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return out
    }

    fun solve(rota: Rota, certify: Boolean = false, maxUnits: Int = DEFAULT_MAX_UNITS): Plan {
        val clock = TimeSource.Monotonic.markNow()
        val slots = slots(rota)
        val units = slots.sumOf { it.shift.needs }
        require(units <= maxUnits) { "rota has $units shift-person units; the limit is $maxUnits" }
        val w = rota.rules.weights
        val people = rota.people

        // Lay out nodes: source, sink, slots, then (person, day), (person, week) and person nodes.
        val source = 0
        val sink = 1
        var nextNode = 2 + slots.size
        val dayNode = LinkedHashMap<Pair<Int, LocalDate>, Int>()
        val weekNode = LinkedHashMap<Pair<Int, Int>, Int>()
        val daysInWeek = HashMap<Pair<Int, Int>, Int>()
        for (slot in slots) for ((pi, p) in people.withIndex()) {
            if (!p.canWork(slot.shift, slot.date)) continue
            if (pi to slot.date !in dayNode) {
                dayNode[pi to slot.date] = nextNode++
                daysInWeek[pi to slot.week] = (daysInWeek[pi to slot.week] ?: 0) + 1
            }
            weekNode.getOrPut(pi to slot.week) { nextNode++ }
        }
        val personNode = IntArray(people.size) { nextNode++ }
        val g = MinCostFlow(nextNode)

        val assignArc = mutableListOf<Triple<Int, Int, Int>>() // (arc, slot, person)
        val uncoveredArc = IntArray(slots.size)
        for (slot in slots) {
            val node = 2 + slot.index
            g.addArc(source, node, slot.shift.needs.toLong(), 0)
            uncoveredArc[slot.index] = g.addArc(node, sink, slot.shift.needs.toLong(), UNCOVERED)
            for ((pi, p) in people.withIndex()) {
                val pd = dayNode[pi to slot.date] ?: continue
                if (!p.canWork(slot.shift, slot.date)) continue
                assignArc += Triple(g.addArc(node, pd, 1, assignCost(rota, p, slot)), slot.index, pi)
            }
        }
        val dayCap = rota.rules.maxPerDay
        val weekOfDate = slots.associate { it.date to it.week }
        val weekOf = HashMap<Int, Int>()
        for ((key, pd) in dayNode) {
            val week = weekOfDate.getValue(key.second)
            g.addArc(pd, weekNode.getValue(key.first to week), dayCap.toLong(), 0)
            weekOf[pd] = week
        }
        val personCapacity = IntArray(people.size)
        for ((key, pw) in weekNode) {
            val cap = minOf(rota.rules.maxPerWeek ?: Int.MAX_VALUE, (daysInWeek[key] ?: 0) * dayCap)
            personCapacity[key.first] += cap
            if (w.spread == 0L) {
                g.addArc(pw, personNode[key.first], cap.toLong(), 0)
            } else {
                for (j in 1..cap) g.addArc(pw, personNode[key.first], 1, w.spread * (2 * j - 1))
            }
        }
        for ((pi, p) in people.withIndex()) {
            val cap = minOf(p.max ?: Int.MAX_VALUE, personCapacity[pi], units)
            for (k in 1..cap) g.addArc(personNode[pi], sink, 1, fairnessMarginal(rota, p, p.history + k))
        }

        val result = g.solve(source, sink)
        check(result.flow == units.toLong()) { "every unit must be routed (uncovered arcs guarantee it)" }
        val certified = certify && !g.hasNegativeResidualCycle().also { check(!it) { "residual graph has a negative cycle" } }

        val assigned = Array(slots.size) { mutableListOf<Int>() }
        for ((arc, s, pi) in assignArc) if (g.flow(arc) > 0) assigned[s] += pi
        val gapsFound = explainGaps(rota, slots, assigned, g, uncoveredArc, dayNode, weekNode, personNode, weekOf, source, sink)
        val swaps = Polish.run(rota, slots, assigned)
        val millis = clock.elapsedNow().inWholeMicroseconds / 1000.0

        return buildPlan(rota, slots, assigned, gapsFound, SolveStats(slots.size, units, nextNode, g.arcCount, result.augmentations, swaps, millis, certified))
    }

    internal fun assignCost(rota: Rota, p: Person, slot: SlotInstance): Long {
        val w = rota.rules.weights
        var c = w.prefer
        if (p.avoid.matches(slot.shift, slot.date)) c += w.avoid
        if (p.prefer.matches(slot.shift, slot.date)) c -= w.prefer
        return c
    }

    /** Cost of a person's m-th shift overall: the m² / share fairness term, differenced. */
    internal fun fairnessMarginal(rota: Rota, p: Person, m: Int): Long =
        rota.rules.weights.fairness * (2L * m - 1) * 1000L / shareMilli(p)

    private fun shareMilli(p: Person): Long = kotlin.math.round(p.share * 1000).toLong().coerceAtLeast(1)

    /**
     * The objective of any assignment, computed directly from its definition. The solver's flow
     * cost must equal this; the brute-force tests use it as the ground truth.
     */
    fun evaluate(rota: Rota, slots: List<SlotInstance>, assigned: List<List<Int>>): Objective {
        val w = rota.rules.weights
        var fairness = 0L; var avoid = 0L; var prefer = 0L; var spread = 0L; var uncovered = 0
        val count = IntArray(rota.people.size)
        val perWeek = HashMap<Pair<Int, Int>, Int>()
        for (slot in slots) {
            val who = assigned[slot.index]
            uncovered += slot.shift.needs - who.size
            for (pi in who) {
                val p = rota.people[pi]
                count[pi]++
                perWeek[pi to slot.week] = (perWeek[pi to slot.week] ?: 0) + 1
                if (p.avoid.matches(slot.shift, slot.date)) avoid += w.avoid
                if (p.prefer.matches(slot.shift, slot.date)) prefer += w.prefer
            }
        }
        for ((pi, p) in rota.people.withIndex()) for (m in p.history + 1..p.history + count[pi]) fairness += fairnessMarginal(rota, p, m)
        for (n in perWeek.values) spread += w.spread * n * n
        return Objective(fairness + avoid - prefer + spread, fairness, avoid, prefer, spread, uncovered)
    }

    private fun explainGaps(
        rota: Rota,
        slots: List<SlotInstance>,
        assigned: Array<MutableList<Int>>,
        g: MinCostFlow,
        uncoveredArc: IntArray,
        dayNode: Map<Pair<Int, LocalDate>, Int>,
        weekNode: Map<Pair<Int, Int>, Int>,
        personNode: IntArray,
        weekOf: Map<Int, Int>,
        source: Int,
        sink: Int,
    ): List<Gap> {
        val gaps = mutableListOf<Gap>()
        for (slot in slots) {
            val missing = g.flow(uncoveredArc[slot.index]).toInt()
            if (missing == 0) continue
            val qualified = rota.people.indices.filter { rota.people[it].canWork(slot.shift, slot.date) }
            if (qualified.isEmpty()) {
                gaps += Gap(slot.date.toString(), slot.shift.name, missing, nobodyReason(rota, slot), emptyList(), 1)
                continue
            }
            // Everything still reachable from this slot without touching source or sink is the
            // set of shifts and people it could trade with; all their capacities are exhausted.
            val reach = g.residualReach(listOf(2 + slot.index)) { a -> g.target(a) == source || g.target(a) == sink }
            val competing = slots.count { reach[2 + it.index] }
            val blockers = qualified.filter { it !in assigned[slot.index] }.map { pi ->
                val p = rota.people[pi]
                val pd = dayNode.getValue(pi to slot.date)
                val pw = weekNode.getValue(pi to weekOf.getValue(pd))
                val reason = when {
                    !reach[pw] -> {
                        val other = slots.filter { it.date == slot.date && pi in assigned[it.index] }.joinToString { it.shift.name }
                        "already on $other that day (max-per-day ${rota.rules.maxPerDay})"
                    }
                    !reach[personNode[pi]] -> "already has ${rota.rules.maxPerWeek} shifts that week (max-per-week)"
                    p.max != null -> "reached their personal max of ${p.max}"
                    else -> "no free capacity left"
                }
                Blocker(p.name, reason)
            }
            val onIt = assigned[slot.index].size
            val reason = buildString {
                append("needs ${slot.shift.needs}, ")
                append(if (onIt > 0) "only $onIt can be placed" else "nobody can be placed")
                append(": every qualified person is at a hard limit")
                if (competing > 1) append(", even after reshuffling the $competing shifts they could swap with")
            }
            gaps += Gap(slot.date.toString(), slot.shift.name, missing, reason, blockers, competing)
        }
        return gaps
    }

    private fun nobodyReason(rota: Rota, slot: SlotInstance): String {
        val off = rota.people.filter { it.isOff(slot.date) }.map { it.name }
        val unskilled = rota.people.filter { !it.isOff(slot.date) && !it.skills.containsAll(slot.shift.requires) }.map { it.name }
        val parts = mutableListOf<String>()
        if (off.isNotEmpty()) parts += "${off.joinToString()} ${if (off.size == 1) "is" else "are"} off"
        if (unskilled.isNotEmpty()) parts += "${unskilled.joinToString()} lack${if (unskilled.size == 1) "s" else ""} '${slot.shift.requires.joinToString()}'"
        return "nobody can work it: " + parts.joinToString("; ")
    }

    private fun buildPlan(rota: Rota, slots: List<SlotInstance>, assigned: Array<MutableList<Int>>, gaps: List<Gap>, stats: SolveStats): Plan {
        val people = rota.people
        val weeks = (slots.maxOfOrNull { it.week } ?: 0) + 1
        val objective = evaluate(rota, slots, assigned.map { it })
        val totalAssigned = assigned.sumOf { it.size }
        val shareSum = people.sumOf { it.share }
        val historySum = people.sumOf { it.history }
        val periodDays = (rota.end.toEpochDays() - rota.start.toEpochDays()).toInt() + 1

        val stats2 = people.mapIndexed { pi, p ->
            val mine = slots.filter { pi in assigned[it.index] }
            val perWeek = IntArray(weeks).also { arr -> mine.forEach { arr[it.week]++ } }
            var offDays = 0
            var d = rota.start
            repeat(periodDays) { if (p.isOff(d)) offDays++; d = d.plus(1, DateTimeUnit.DAY) }
            PersonStats(
                name = p.name,
                share = p.share,
                history = p.history,
                assigned = mine.size,
                target = ((totalAssigned + historySum) * p.share / shareSum - p.history).coerceAtLeast(0.0).let { kotlin.math.round(it * 10) / 10 },
                byShift = rota.shifts.associate { s -> s.name to mine.count { it.shift == s } }.filterValues { it > 0 },
                perWeek = perWeek.toList(),
                avoidedHits = mine.count { p.avoid.matches(it.shift, it.date) },
                preferredHits = mine.count { p.prefer.matches(it.shift, it.date) },
                daysOff = offDays,
            )
        }

        val rest = mutableListOf<RestWarning>()
        for ((pi, p) in people.withIndex()) {
            val mine = slots.filter { pi in assigned[it.index] }.sortedBy { startMinute(it) }
            for ((a, b) in mine.zipWithNext()) {
                val gap = startMinute(b) - endMinute(a)
                if (gap < rota.rules.minRestHours * 60L) {
                    rest += RestWarning(p.name, "${a.date} ${a.shift.name}", "${b.date} ${b.shift.name}", gap / 60.0)
                }
            }
        }

        return Plan(
            name = rota.name,
            start = rota.start.toString(),
            end = rota.end.toString(),
            timezone = rota.timezone,
            shifts = rota.shifts.map { ShiftInfo(it.name, it.start.toString(), it.end.toString(), it.overnight, it.needs, it.requires.toList()) },
            slots = slots.map { s ->
                Slot(s.date.toString(), weekdayShort(s.date.dayOfWeek), s.week, s.shift.name, s.shift.start.toString(), s.shift.end.toString(), s.shift.needs, assigned[s.index].map { people[it].name })
            },
            people = stats2,
            gaps = gaps,
            restWarnings = rest,
            objective = objective,
            stats = stats,
        )
    }

    internal fun startMinute(s: SlotInstance): Long = s.date.toEpochDays() * 1440L + s.shift.start.hour * 60 + s.shift.start.minute
    internal fun endMinute(s: SlotInstance): Long {
        val end = s.shift.end.hour * 60L + s.shift.end.minute
        return s.date.toEpochDays() * 1440L + end + if (s.shift.overnight) 1440 else 0
    }

    fun weekdayShort(d: DayOfWeek): String = d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
}
