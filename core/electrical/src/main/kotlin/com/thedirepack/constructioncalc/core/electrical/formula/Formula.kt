package com.thedirepack.constructioncalc.core.electrical.formula

import kotlin.math.abs
import kotlin.math.max

/** What a formula variable measures, so tools can exchange it (a current from Voltage Drop, …). */
enum class FormulaQuantity {
    VOLTAGE, CURRENT, POWER, APPARENT_POWER, REACTIVE_POWER, RESISTANCE, INDUCTANCE, CAPACITANCE,
    FREQUENCY, RATIO, PERCENT, TEMPERATURE, TIME, ENERGY, HORSEPOWER, MONEY, COEFFICIENT,
    /** Feet. */
    LENGTH,
    /** Circular mils. */
    CONDUCTOR_AREA,
}

/** A unit a variable can be shown in: value in the variable's base unit = shown value × [factor]. */
data class FormulaUnit(val label: String, val factor: Double = 1.0)

/**
 * One quantity in a [Formula]. Solving works in the base unit (factor 1 in [units]); [defaultUnit]
 * is what the field shows first.
 *
 * @property min smallest allowed value (in base units), null for none
 * @property minExclusive true when [min] itself isn't allowed (a voltage must be more than zero)
 * @property max largest allowed value, e.g. 1 for a power factor
 * @property default prefilled value in the default unit (e.g. 60 Hz), or null
 */
data class Variable(
    val id: String,
    val symbol: String,
    val name: String,
    val quantity: FormulaQuantity,
    val units: List<FormulaUnit>,
    val defaultUnit: Int = 0,
    val min: Double? = 0.0,
    val minExclusive: Boolean = true,
    val max: Double? = null,
    val default: Double? = null,
    val hint: String? = null,
) {
    init {
        require(units.any { it.factor == 1.0 }) { "$id needs a base unit (factor 1)" }
        require(defaultUnit in units.indices) { "$id default unit out of range" }
    }

    val baseUnit: FormulaUnit get() = units.first { it.factor == 1.0 }

    /** Null when [value] (base units) is allowed; otherwise why not. */
    fun rangeProblem(value: Double): String? = when {
        !value.isFinite() -> "isn't a finite number"
        min != null && (if (minExclusive) value <= min else value < min) ->
            if (min == 0.0) (if (minExclusive) "must be more than zero" else "can't be negative") else "must be more than ${fmt(min)}"
        max != null && value > max + 1e-12 -> "can't be more than ${fmt(max)}"
        else -> null
    }
}

/** The values a relation's solvers read, in base units. */
class Values internal constructor(private val map: Map<String, Double>) {
    operator fun get(id: String): Double = map.getValue(id)
}

/**
 * One equation between some of a formula's variables, with a way to compute each variable it can
 * be solved for from the others. A solver returns null (or a non-finite value) when there is no
 * real answer, e.g. a power factor above 1.
 */
class Relation(val expression: String, val variables: Set<String>, internal val solvers: Map<String, (Values) -> Double?>) {
    init {
        require(solvers.keys.all { it in variables }) { "Solver for a variable not in $expression" }
    }
}

/** Builds a [Relation]: `relation("P = V × I", "P", "V", "I") { solve("P") { it["V"] * it["I"] } … }`. */
class RelationBuilder internal constructor() {
    internal val solvers = mutableMapOf<String, (Values) -> Double?>()
    fun solve(id: String, f: (Values) -> Double?) { solvers[id] = f }
}

fun relation(expression: String, vararg variables: String, build: RelationBuilder.() -> Unit): Relation =
    Relation(expression, variables.toSet(), RelationBuilder().apply(build).solvers.toMap())

/** How a formula is grouped on the formula sheet. */
enum class FormulaGroup(val label: String) {
    BASICS("Ohm's law and power"),
    AC_POWER("AC power"),
    MOTORS_TRANSFORMERS("Motors and transformers"),
    CIRCUITS("Impedance and circuits"),
    ENERGY("Energy and efficiency"),
}

/**
 * A set of equations a person can solve for any unknown: enter what you know, the rest is computed.
 *
 * @property id stable, saved in projects and used for links from the Reference; never change it
 * @property notes short caveats shown with the formula (assumptions, when a code table governs instead)
 * @property toolId a tool that does this job in full, if any (e.g. Voltage Drop), for "Open in …"
 */
data class Formula(
    val id: String,
    val title: String,
    val group: FormulaGroup,
    val summary: String,
    val variables: List<Variable>,
    val relations: List<Relation>,
    val notes: List<String> = emptyList(),
    val toolId: String? = null,
    val keywords: List<String> = emptyList(),
) {
    init {
        val ids = variables.map { it.id }
        require(ids.toSet().size == ids.size) { "$id has duplicate variables" }
        relations.forEach { r -> require(r.variables.all { it in ids }) { "$id: ${r.expression} uses an unknown variable" } }
    }

    fun variable(id: String): Variable = variables.first { it.id == id }
}

/** One computed value and the equation that gave it. */
data class SolveStep(val variableId: String, val expression: String, val value: Double)

/**
 * The outcome of [FormulaSolver.solve]. All values are in base units.
 *
 * @property values every known value, entered or computed
 * @property steps the computed values, in the order they were found
 * @property unknown variables nothing could determine (enter more values)
 * @property conflicts equations the entered values don't satisfy (too many values given)
 * @property problems values that are out of range, entered or computed
 */
data class FormulaSolution(
    val values: Map<String, Double>,
    val steps: List<SolveStep>,
    val unknown: List<String>,
    val conflicts: List<String>,
    val problems: List<String>,
) {
    val computed: Set<String> get() = steps.mapTo(mutableSetOf()) { it.variableId }
    val isComplete: Boolean get() = unknown.isEmpty() && conflicts.isEmpty() && problems.isEmpty()
}

/** Solves a [Formula] by repeatedly using any equation that has exactly one unknown left. */
object FormulaSolver {
    /** Relative difference above which an over-determined equation counts as a conflict. */
    const val TOLERANCE = 0.005

    fun solve(formula: Formula, given: Map<String, Double>): FormulaSolution {
        val problems = mutableListOf<String>()
        val known = linkedMapOf<String, Double>()
        for (v in formula.variables) {
            val value = given[v.id] ?: continue
            val problem = v.rangeProblem(value)
            if (problem != null) problems += "${v.name} $problem." else known[v.id] = value
        }
        val steps = mutableListOf<SolveStep>()
        var progress = true
        while (progress) {
            progress = false
            for (r in formula.relations) {
                val missing = r.variables.filter { it !in known }
                if (missing.size != 1) continue
                val target = missing.single()
                val solver = r.solvers[target] ?: continue
                val value = runCatching { solver(Values(known)) }.getOrNull()?.takeIf { it.isFinite() } ?: continue
                val v = formula.variable(target)
                val problem = v.rangeProblem(value)
                if (problem != null) {
                    problems += "${v.name} works out to ${fmt(value)}, which $problem: check the entered values."
                    known[target] = value // keep going so the other values still show
                } else {
                    known[target] = value
                }
                steps += SolveStep(target, r.expression, value)
                progress = true
            }
        }
        val conflicts = formula.relations.filter { r -> r.variables.all { it in known } && !satisfied(r, known) }
            .map { it.expression }
        val unknown = formula.variables.map { it.id }.filter { it !in known }
        return FormulaSolution(known, steps, unknown, conflicts, problems.distinct())
    }

    // Recompute each solvable variable from the others; all must agree with what's known.
    private fun satisfied(r: Relation, known: Map<String, Double>): Boolean = r.solvers.all { (id, solver) ->
        val others = Values(known)
        val expected = runCatching { solver(others) }.getOrNull()?.takeIf { it.isFinite() } ?: return@all true
        val actual = known.getValue(id)
        abs(expected - actual) <= TOLERANCE * max(max(abs(expected), abs(actual)), 1e-9)
    }
}

internal fun fmt(v: Double): String =
    if (v == kotlin.math.floor(v) && abs(v) < 1e12) v.toLong().toString() else "%.4g".format(java.util.Locale.ROOT, v)
