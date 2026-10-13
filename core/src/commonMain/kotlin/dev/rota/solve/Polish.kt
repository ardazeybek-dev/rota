package dev.rota.solve

import dev.rota.model.Rota

/**
 * Second pass over an optimal plan. Many plans share the optimal objective; among them we
 * prefer fewer short-rest pairs and a balanced mix of shift types per person. Neither is a
 * network-flow constraint, so this is a local search over *objective-neutral* swaps:
 * two people trade assignments inside one week, which keeps every per-person and per-week
 * count — and therefore fairness and spread — unchanged. A swap is taken only when the
 * avoid/prefer cost is also unchanged, so the plan stays exactly optimal.
 */
internal object Polish {
    private const val REST_PENALTY = 1_000_000L

    fun run(rota: Rota, slots: List<SlotInstance>, assigned: Array<MutableList<Int>>, maxRounds: Int = 50): Int {
        val minRest = rota.rules.minRestHours * 60L
        val mine = Array(rota.people.size) { pi -> slots.filter { pi in assigned[it.index] }.toMutableList() }
        val typeIndex = rota.shifts.withIndex().associate { it.value.name to it.index }

        fun score(pi: Int): Long {
            val list = mine[pi].sortedBy { Solver.startMinute(it) }
            var short = 0L
            for ((a, b) in list.zipWithNext()) if (Solver.startMinute(b) - Solver.endMinute(a) < minRest) short++
            val perType = IntArray(rota.shifts.size)
            for (s in list) perType[typeIndex.getValue(s.shift.name)]++
            return short * REST_PENALTY + perType.sumOf { it.toLong() * it }
        }

        fun worksOn(pi: Int, date: kotlinx.datetime.LocalDate, except: SlotInstance) = mine[pi].count { it.date == date && it != except }

        var swaps = 0
        val byWeek = slots.groupBy { it.week }
        repeat(maxRounds) {
            var improved = false
            for ((_, week) in byWeek) {
                for (a in week) for (b in week) {
                    if (a.index >= b.index) continue
                    for (x in assigned[a.index].toList()) for (y in assigned[b.index].toList()) {
                        if (x == y || y in assigned[a.index] || x in assigned[b.index]) continue
                        val px = rota.people[x]; val py = rota.people[y]
                        if (!py.canWork(a.shift, a.date) || !px.canWork(b.shift, b.date)) continue
                        if (worksOn(x, b.date, a) >= rota.rules.maxPerDay || worksOn(y, a.date, b) >= rota.rules.maxPerDay) continue
                        val before = Solver.assignCost(rota, px, a) + Solver.assignCost(rota, py, b)
                        val after = Solver.assignCost(rota, py, a) + Solver.assignCost(rota, px, b)
                        if (after != before) continue
                        val old = score(x) + score(y)
                        move(assigned, mine, a, b, x, y)
                        if (score(x) + score(y) < old) {
                            swaps++; improved = true
                        } else {
                            move(assigned, mine, a, b, y, x)
                        }
                    }
                }
            }
            if (!improved) return swaps
        }
        return swaps
    }

    /** x leaves a for b, y leaves b for a. */
    private fun move(assigned: Array<MutableList<Int>>, mine: Array<MutableList<SlotInstance>>, a: SlotInstance, b: SlotInstance, x: Int, y: Int) {
        assigned[a.index][assigned[a.index].indexOf(x)] = y
        assigned[b.index][assigned[b.index].indexOf(y)] = x
        mine[x].remove(a); mine[x].add(b)
        mine[y].remove(b); mine[y].add(a)
    }
}
