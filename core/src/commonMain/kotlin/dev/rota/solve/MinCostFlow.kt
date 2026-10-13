package dev.rota.solve

/**
 * Min-cost flow by successive shortest paths with Johnson potentials.
 *
 * Arc costs must be non-negative when added, so Dijkstra is valid from the first
 * iteration and the potentials keep every residual reduced cost non-negative after it.
 * Ties are broken by node index, which makes the result identical on every platform.
 */
class MinCostFlow(val nodeCount: Int) {
    private var size = 0
    private var to = IntArray(64)
    private var cap = LongArray(64)
    private var cost = LongArray(64)
    private var next = IntArray(64)
    private val head = IntArray(nodeCount) { -1 }

    val arcCount: Int get() = size / 2

    /** Adds an arc and its residual twin; returns the arc id (the twin is `id xor 1`). */
    fun addArc(from: Int, to: Int, capacity: Long, cost: Long): Int {
        require(capacity >= 0) { "negative capacity" }
        require(cost >= 0) { "arc costs must be non-negative" }
        ensure(size + 2)
        val id = size
        put(id, to, capacity, cost, from)
        put(id + 1, from, 0, -cost, to)
        size += 2
        return id
    }

    private fun put(id: Int, target: Int, c: Long, w: Long, from: Int) {
        this.to[id] = target; this.cap[id] = c; this.cost[id] = w
        next[id] = head[from]; head[from] = id
    }

    private fun ensure(n: Int) {
        if (n <= to.size) return
        val m = maxOf(n, to.size * 2)
        to = to.copyOf(m); cap = cap.copyOf(m); cost = cost.copyOf(m); next = next.copyOf(m)
    }

    fun flow(arc: Int): Long = cap[arc xor 1]
    fun residual(arc: Int): Long = cap[arc]
    fun target(arc: Int): Int = to[arc]
    fun arcCost(arc: Int): Long = cost[arc]
    fun outArcs(node: Int): Sequence<Int> = generateSequence(head[node].takeIf { it >= 0 }) { a -> next[a].takeIf { it >= 0 } }

    data class Result(val flow: Long, val cost: Long, val augmentations: Int, val dijkstras: Int)

    private val bfsQueue = IntArray(nodeCount)

    private fun admissible(a: Int, u: Int, potential: LongArray) = cap[a] > 0 && cost[a] + potential[u] - potential[to[a]] == 0L

    /** BFS levels over admissible arcs; false when the sink is unreachable through them. */
    private fun levelGraph(source: Int, sink: Int, potential: LongArray, level: IntArray): Boolean {
        level.fill(-1)
        level[source] = 0
        val queue = bfsQueue
        var qh = 0; var qt = 0
        queue[qt++] = source
        while (qh < qt) {
            val u = queue[qh++]
            var a = head[u]
            while (a >= 0) {
                val v = to[a]
                if (level[v] < 0 && admissible(a, u, potential)) { level[v] = level[u] + 1; queue[qt++] = v }
                a = next[a]
            }
        }
        return level[sink] >= 0
    }

    /** One augmenting path along increasing levels (Dinic), with per-node arc cursors. */
    private fun augment(source: Int, sink: Int, want: Long, potential: LongArray, level: IntArray, iter: IntArray): Long {
        val pathArcs = IntArray(level[sink])
        var depth = 0
        var u = source
        while (true) {
            if (u == sink) {
                var push = want
                for (i in 0 until depth) push = minOf(push, cap[pathArcs[i]])
                for (i in 0 until depth) { val a = pathArcs[i]; cap[a] -= push; cap[a xor 1] += push }
                return push
            }
            if (iter[u] == -2) iter[u] = head[u]
            // Nodes at the sink's level (other than the sink) can only lead to longer paths.
            var a = if (depth < pathArcs.size) iter[u] else -1
            while (a >= 0 && !(level[to[a]] == level[u] + 1 && admissible(a, u, potential))) a = next[a]
            if (depth < pathArcs.size) iter[u] = a
            if (a < 0) {
                // Dead end: retreat and skip the arc that led here.
                if (depth == 0) return 0
                level[u] = -1
                depth--
                val back = pathArcs[depth]
                u = to[back xor 1]
                iter[u] = next[iter[u]]
                continue
            }
            pathArcs[depth++] = a
            u = to[a]
        }
    }

    fun solve(source: Int, sink: Int, limit: Long = Long.MAX_VALUE): Result {
        val potential = LongArray(nodeCount)
        val dist = LongArray(nodeCount)
        val via = IntArray(nodeCount)
        val heap = IndexedHeap(nodeCount)
        var flow = 0L
        var total = 0L
        var rounds = 0
        var dijkstras = 0
        val level = IntArray(nodeCount)
        val iter = IntArray(nodeCount)
        while (flow < limit) {
            dist.fill(INF); via.fill(-1)
            dist[source] = 0
            heap.push(source, 0)
            while (heap.isNotEmpty()) {
                val u = heap.pop()
                val du = dist[u]
                if (heap.lastKey > du) continue
                // Nothing popped after the sink can shorten the path to it.
                if (u == sink) { heap.clear(); break }
                var a = head[u]
                while (a >= 0) {
                    if (cap[a] > 0) {
                        val v = to[a]
                        val nd = du + cost[a] + potential[u] - potential[v]
                        if (nd < dist[v]) {
                            dist[v] = nd; via[v] = a
                            heap.push(v, nd)
                        }
                    }
                    a = next[a]
                }
            }
            if (dist[sink] == INF) break
            // Capping at dist[sink] keeps reduced costs non-negative for nodes left unsettled.
            val ds = dist[sink]
            for (v in 0 until nodeCount) potential[v] += minOf(dist[v], ds)

            // Primal-dual step: every s-t path made only of zero-reduced-cost arcs is a shortest
            // path, so push a blocking flow through all of them before the next Dijkstra.
            while (flow < limit && levelGraph(source, sink, potential, level)) {
                iter.fill(-2)
                while (flow < limit) {
                    val pushed = augment(source, sink, limit - flow, potential, level, iter)
                    if (pushed == 0L) break
                    flow += pushed
                    total += pushed * (potential[sink] - potential[source])
                    rounds++
                }
            }
            dijkstras++
        }
        return Result(flow, total, rounds, dijkstras)
    }

    /**
     * Optimality certificate, independent of how the flow was found: a flow is min-cost iff the
     * residual graph has no negative cycle (Bellman–Ford from a virtual root).
     */
    fun hasNegativeResidualCycle(): Boolean {
        val d = LongArray(nodeCount)
        for (round in 0 until nodeCount) {
            var changed = false
            for (u in 0 until nodeCount) {
                var a = head[u]
                while (a >= 0) {
                    if (cap[a] > 0 && d[u] + cost[a] < d[to[a]]) {
                        d[to[a]] = d[u] + cost[a]; changed = true
                    }
                    a = next[a]
                }
            }
            if (!changed) return false
        }
        return true
    }

    /** Nodes reachable from [start] through arcs with residual capacity, skipping [blocked] arcs. */
    fun residualReach(start: Collection<Int>, blocked: (Int) -> Boolean): BooleanArray {
        val seen = BooleanArray(nodeCount)
        val stack = ArrayDeque<Int>()
        for (s in start) if (!seen[s]) { seen[s] = true; stack.addLast(s) }
        while (stack.isNotEmpty()) {
            val u = stack.removeLast()
            var a = head[u]
            while (a >= 0) {
                if (cap[a] > 0 && !blocked(a) && !seen[to[a]]) { seen[to[a]] = true; stack.addLast(to[a]) }
                a = next[a]
            }
        }
        return seen
    }

    private companion object {
        const val INF = Long.MAX_VALUE / 4
    }
}

/** Binary heap over (key, node) with lazy deletion; ties resolve to the lower node index. */
internal class IndexedHeap(capacity: Int) {
    private var keys = LongArray(maxOf(16, capacity))
    private var nodes = IntArray(maxOf(16, capacity))
    private var n = 0

    /** Key of the entry returned by the last [pop]. */
    var lastKey = 0L
        private set

    fun isNotEmpty() = n > 0

    fun clear() { n = 0 }

    private fun less(i: Int, j: Int) = keys[i] < keys[j] || (keys[i] == keys[j] && nodes[i] < nodes[j])

    private fun swap(i: Int, j: Int) {
        val k = keys[i]; keys[i] = keys[j]; keys[j] = k
        val v = nodes[i]; nodes[i] = nodes[j]; nodes[j] = v
    }

    fun push(node: Int, key: Long) {
        if (n == keys.size) { keys = keys.copyOf(n * 2); nodes = nodes.copyOf(n * 2) }
        keys[n] = key; nodes[n] = node
        var i = n++
        while (i > 0) {
            val p = (i - 1) / 2
            if (!less(i, p)) break
            swap(i, p); i = p
        }
    }

    /** Pops the minimum node; stale entries are skipped by the caller's distance check. */
    fun pop(): Int {
        val top = nodes[0]
        lastKey = keys[0]
        n--
        if (n > 0) {
            keys[0] = keys[n]; nodes[0] = nodes[n]
            var i = 0
            while (true) {
                val l = 2 * i + 1; val r = l + 1
                var m = i
                if (l < n && less(l, m)) m = l
                if (r < n && less(r, m)) m = r
                if (m == i) break
                swap(i, m); i = m
            }
        }
        return top
    }
}
