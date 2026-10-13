package dev.rota.solve

import kotlinx.serialization.Serializable

/** The solved rota, in a shape that serializes straight to the JSON API. */
@Serializable
data class Plan(
    val name: String,
    val start: String,
    val end: String,
    val timezone: String? = null,
    val shifts: List<ShiftInfo>,
    val slots: List<Slot>,
    val people: List<PersonStats>,
    val gaps: List<Gap>,
    val restWarnings: List<RestWarning>,
    val objective: Objective,
    val stats: SolveStats,
    val warnings: List<String> = emptyList(),
) {
    val complete: Boolean get() = gaps.isEmpty()
}

@Serializable
data class ShiftInfo(val name: String, val start: String, val end: String, val overnight: Boolean, val needs: Int, val requires: List<String>)

@Serializable
data class Slot(
    val date: String,
    val weekday: String,
    val week: Int,
    val shift: String,
    val start: String,
    val end: String,
    val needs: Int,
    val assigned: List<String>,
) {
    val open: Int get() = needs - assigned.size
}

@Serializable
data class PersonStats(
    val name: String,
    val share: Double,
    val history: Int,
    val assigned: Int,
    /** The perfectly fair number of shifts given shares and history; real plans round around it. */
    val target: Double,
    val byShift: Map<String, Int>,
    val perWeek: List<Int>,
    val avoidedHits: Int,
    val preferredHits: Int,
    val daysOff: Int,
)

/** A slot that could not be staffed, with the reason the solver can prove. */
@Serializable
data class Gap(
    val date: String,
    val shift: String,
    val missing: Int,
    val reason: String,
    val blockers: List<Blocker>,
    /** Slots competing for the same exhausted people (the min-cut / Hall's-theorem witness). */
    val competing: Int,
)

@Serializable
data class Blocker(val person: String, val reason: String)

@Serializable
data class RestWarning(val person: String, val from: String, val to: String, val restHours: Double)

@Serializable
data class Objective(
    val total: Long,
    val fairness: Long,
    val avoid: Long,
    val prefer: Long,
    val spread: Long,
    val uncoveredUnits: Int,
)

@Serializable
data class SolveStats(
    val slots: Int,
    val units: Int,
    val nodes: Int,
    val arcs: Int,
    val augmentations: Int,
    /** Objective-neutral swaps made afterwards to cut short rests and balance shift types. */
    val swaps: Int,
    val millis: Double,
    /** True when the residual graph was checked for negative cycles (an optimality proof). */
    val certified: Boolean,
)
