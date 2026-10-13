package dev.rota.solve

import dev.rota.model.Rota
import kotlinx.datetime.LocalDate

/**
 * Second pass over an optimal plan. Many plans share the optimal objective; among them we
 * prefer fewer short-rest pairs and a balanced mix of shift types per person. Neither is a
 * network-flow constraint, so this is a local search over *objective-neutral* swaps:
 * two people trade assignments inside one week, which keeps every per-person and per-week
 * count — and therefore fairness and spread — unchanged. A swap is taken only when the
 * avoid/prefer cost is also unchanged, so the plan stays exactly optimal.
 *
 * Score per person: short-rest pairs × [REST_PENALTY] + Σ (shifts of each type)².
 * Both terms are updated incrementally, so a candidate swap costs O(log n).
 */
internal object Polish {
    private const val REST_PENALTY = 1_000_000L

    fun run(rota: Rota, slots: List<SlotInstance>, assigned: Array<MutableList<Int>>, maxRounds: Int = 30): Int {
        val minRest = rota.rules.minRestHours * 60L
        val n = rota.people.size
        val type = rota.shifts.withIndex().associate { it.value.name to it.index }
        val start = LongArray(slots.size) { Solver.startMinute(slots[it]) }
        val end = LongArray(slots.size) { Solver.endMinute(slots[it]) }
        // Each person's shifts as slot indices sorted by start time.
        val timeline = Array(n) { mutableListOf<Int>() }
        val typeCount = Array(n) { IntArray(rota.shifts.size) }
        val perDay = Array(n) { HashMap<LocalDate, Int>() }
        for (s in slots) for (pi in assigned[s.index]) {
            timeline[pi] += s.index
            typeCount[pi][type.getValue(s.shift.name)]++
            perDay[pi][s.date] = (perDay[pi][s.date] ?: 0) + 1
        }
        for (t in timeline) t.sortWith(compareBy({ start[it] }, { it }))

        fun short(p: Int, q: Int): Int = if (start[q] - end[p] < minRest) 1 else 0

        fun position(t: List<Int>, s: Int): Int {
            var lo = 0; var hi = t.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val m = t[mid]
                if (start[m] < start[s] || (start[m] == start[s] && m < s)) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Removes slot s from person pi's timeline; returns the change in short-rest pairs. */
        fun remove(pi: Int, s: Int): Int {
            val t = timeline[pi]
            val i = position(t, s)
            var d = 0
            if (i > 0) d -= short(t[i - 1], s)
            if (i + 1 < t.size) d -= short(s, t[i + 1])
            if (i > 0 && i + 1 < t.size) d += short(t[i - 1], t[i + 1])
            t.removeAt(i)
            return d
        }

        fun insert(pi: Int, s: Int): Int {
            val t = timeline[pi]
            val j = position(t, s)
            var d = 0
            if (j > 0 && j < t.size) d -= short(t[j - 1], t[j])
            if (j > 0) d += short(t[j - 1], s)
            if (j < t.size) d += short(s, t[j])
            t.add(j, s)
            return d
        }

        fun typeDelta(pi: Int, from: Int, to: Int): Long {
            if (from == to) return 0
            val c = typeCount[pi]
            return 2L * (c[to] - c[from]) + 2
        }

        var swaps = 0
        val byWeek = slots.groupBy { it.week }.values
        repeat(maxRounds) {
            var improved = false
            for (week in byWeek) for (ai in week.indices) for (bi in ai + 1 until week.size) {
                val a = week[ai]; val b = week[bi]
                val ta = type.getValue(a.shift.name); val tb = type.getValue(b.shift.name)
                for (x in assigned[a.index].toList()) for (y in assigned[b.index].toList()) {
                    if (x == y || y in assigned[a.index] || x in assigned[b.index]) continue
                    val px = rota.people[x]; val py = rota.people[y]
                    if (!py.canWork(a.shift, a.date) || !px.canWork(b.shift, b.date)) continue
                    if (a.date != b.date) {
                        if ((perDay[x][b.date] ?: 0) >= rota.rules.maxPerDay || (perDay[y][a.date] ?: 0) >= rota.rules.maxPerDay) continue
                    }
                    if (Solver.assignCost(rota, py, a) + Solver.assignCost(rota, px, b) != Solver.assignCost(rota, px, a) + Solver.assignCost(rota, py, b)) continue

                    val types = typeDelta(x, ta, tb) + typeDelta(y, tb, ta)
                    val rest = remove(x, a.index) + insert(x, b.index) + remove(y, b.index) + insert(y, a.index)
                    if (rest * REST_PENALTY + types < 0) {
                        assigned[a.index][assigned[a.index].indexOf(x)] = y
                        assigned[b.index][assigned[b.index].indexOf(y)] = x
                        typeCount[x][ta]--; typeCount[x][tb]++; typeCount[y][tb]--; typeCount[y][ta]++
                        if (a.date != b.date) {
                            perDay[x][a.date] = perDay[x].getValue(a.date) - 1; perDay[x][b.date] = (perDay[x][b.date] ?: 0) + 1
                            perDay[y][b.date] = perDay[y].getValue(b.date) - 1; perDay[y][a.date] = (perDay[y][a.date] ?: 0) + 1
                        }
                        swaps++; improved = true
                    } else {
                        remove(x, b.index); insert(x, a.index); remove(y, a.index); insert(y, b.index)
                    }
                }
            }
            if (!improved) return swaps
        }
        return swaps
    }
}
