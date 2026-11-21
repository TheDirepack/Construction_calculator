package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.electrical.CableConfiguration
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.FrictionPreset
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.measure.Dimension
import kotlinx.serialization.Serializable

private fun finiteNonNegative(value: Double?) = value == null || (value.isFinite() && value >= 0.0)

/**
 * [count] identical single conductors in a pull, added by hand (circuits in the run add theirs
 * automatically, see [PullSetup.cablesFromCircuits]).
 *
 * @property weightLbPerFt catalog weight of one conductor; null uses an estimate (CableWeight).
 * @property diameterIn catalog outside diameter of one conductor; null uses NEC Chapter 9 Table 5.
 * @property bearsTension whether the pulling device is attached to these conductors (an
 *   equipment grounding conductor much smaller than the others is normally left out).
 */
@Serializable
data class PullCable(
    val count: Int = 1,
    val size: ConductorSize = ConductorSize.AWG_12,
    val insulation: InsulationType = InsulationType.THHN,
    val material: ConductorMaterial = ConductorMaterial.COPPER,
    val weightLbPerFt: Double? = null,
    val diameterIn: Double? = null,
    val bearsTension: Boolean = true,
    val label: String = "",
) {
    init {
        require(count in 1..MAX_COUNT) { "Invalid cable count: $count" }
        require(finiteNonNegative(weightLbPerFt) && (weightLbPerFt ?: 1.0) > 0.0) { "Invalid cable weight" }
        require(finiteNonNegative(diameterIn) && (diameterIn ?: 1.0) > 0.0) { "Invalid cable diameter" }
        require(label.length <= 200) { "Cable label too long" }
    }

    companion object {
        const val MAX_COUNT = 99
    }
}

/** A rated piece of pulling equipment: grip or eye, rope, swivel, puller. [pounds] null = not known yet. */
@Serializable
data class EquipmentRating(val name: String, val pounds: Double? = null) {
    init {
        require(name.length <= 200) { "Equipment name too long" }
        require(finiteNonNegative(pounds)) { "Invalid equipment rating" }
    }

    companion object {
        /** The usual chain of limiting equipment; ratings come from each maker. */
        val DEFAULTS = listOf(
            EquipmentRating("Pulling eye or grip"),
            EquipmentRating("Rope"),
            EquipmentRating("Swivel"),
            EquipmentRating("Puller"),
        )
    }
}

/**
 * A run's pull settings, saved with the run so the Wire Pull Planner, Panels and exports agree.
 *
 * @property friction coefficient-of-friction preset (guidance); [frictionOverride] replaces it.
 * @property configuration cable configuration for W and sidewall pressure; null picks it from the
 *   cable count and D/d (PullFormulas.configuration).
 * @property incomingTension pounds entering each pull section (index 0 is the first section, from
 *   the run's start), e.g. back tension from the reel; missing sections use 0.
 * @property pressureLimit the cable's maximum sidewall pressure, lb/ft; null uses the labelled
 *   building-wire preset.
 * @property slackPerEnd cable left at each end of the run for terminations.
 * @property cablesFromCircuits include the conductors of the circuits assigned to the run.
 * @property cables conductors added by hand (in addition to the circuits').
 */
@Serializable
data class PullSetup(
    val friction: FrictionPreset = FrictionPreset.LUBRICATED,
    val frictionOverride: Double? = null,
    val configuration: CableConfiguration? = null,
    val incomingTension: List<Double> = emptyList(),
    val equipment: List<EquipmentRating> = EquipmentRating.DEFAULTS,
    val pressureLimit: Double? = null,
    val slackPerEnd: StoredQuantity? = null,
    val cablesFromCircuits: Boolean = true,
    val cables: List<PullCable> = emptyList(),
) {
    init {
        require(frictionOverride == null || (frictionOverride.isFinite() && frictionOverride > 0.0 && frictionOverride <= 2.0)) { "Invalid friction" }
        require(incomingTension.size <= RunPath.MAX_SEGMENTS && incomingTension.all { it.isFinite() && it >= 0.0 }) { "Invalid incoming tension" }
        require(equipment.size <= 50 && cables.size <= 200) { "Too many entries" }
        require(pressureLimit == null || (pressureLimit.isFinite() && pressureLimit > 0.0)) { "Invalid pressure limit" }
        require(slackPerEnd == null || (slackPerEnd.dimension == Dimension.LENGTH && slackPerEnd.toQuantity().value.signum >= 0)) { "Invalid slack" }
    }

    val coefficient: Double get() = frictionOverride ?: friction.coefficient

    fun incoming(section: Int): Double = incomingTension.getOrNull(section) ?: 0.0

    fun withIncoming(section: Int, pounds: Double): PullSetup {
        val list = incomingTension.toMutableList()
        while (list.size <= section) list += 0.0
        list[section] = pounds
        return copy(incomingTension = list)
    }
}
