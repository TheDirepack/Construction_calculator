package com.thedirepack.constructioncalc.feature.pullplanner.domain

import com.thedirepack.constructioncalc.core.electrical.NamedCable
import com.thedirepack.constructioncalc.core.model.EquipmentRating
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup

/** Named cables in a pull: adding one to a [PullSetup], and saving a hand-entered cable as one. */
object NamedCables {
    /** Prefix of the equipment entry that carries a named cable's maximum pulling tension. */
    const val TENSION_PREFIX = "Cable max tension: "

    /**
     * [setup] with one [cable] added: its name, OD and weight become a [PullCable]; its maximum
     * tension becomes an equipment limit and its sidewall pressure rating tightens the pressure limit.
     */
    fun apply(setup: PullSetup, cable: NamedCable): PullSetup {
        if (setup.cables.size >= MAX_CABLES) return setup
        val pull = PullCable(
            count = 1,
            weightLbPerFt = cable.weightLbPerFt,
            diameterIn = cable.diameterIn,
            label = cable.name.take(MAX_LABEL),
        )
        val equipmentName = (TENSION_PREFIX + cable.name).take(MAX_LABEL)
        val equipment = cable.maxTensionLb?.let { lb ->
            (setup.equipment.filterNot { it.name == equipmentName } + EquipmentRating(equipmentName, lb)).takeLast(MAX_EQUIPMENT)
        } ?: setup.equipment
        val pressure = listOfNotNull(setup.pressureLimit, cable.maxSwbpLbPerFt).minOrNull()
        return setup.copy(cables = setup.cables + pull, equipment = equipment, pressureLimit = pressure)
    }

    /**
     * The last hand-entered cable with a catalog OD, as a named cable to save (its label or wire size
     * as the name; the run's sidewall pressure limit as its rating), or null when there's none.
     */
    fun fromSetup(setup: PullSetup): NamedCable? {
        val cable = setup.cables.lastOrNull { it.diameterIn != null } ?: return null
        val od = cable.diameterIn ?: return null
        return runCatching {
            NamedCable(
                id = "pull-${cable.label.ifBlank { cable.size.label }}",
                name = cable.label.ifBlank { "${cable.size.label} ${cable.insulation.code}" },
                diameterIn = od,
                weightLbPerFt = cable.weightLbPerFt,
                maxSwbpLbPerFt = setup.pressureLimit,
            )
        }.getOrNull()
    }

    /** "3 × 12 AWG THHN Cu", or "1 × 3/C #12 MC" for a cable with a name. */
    fun describe(cable: PullCable): String =
        if (cable.label.isNotBlank()) "${cable.count} × ${cable.label}"
        else "${cable.count} × ${cable.size.label} ${cable.insulation.code} ${cable.material.shortLabel}"

    private const val MAX_CABLES = 200
    private const val MAX_EQUIPMENT = 50
    private const val MAX_LABEL = 200
}

/** Reference entries the planner's notes link to, and keys of values it publishes. */
object PullRefs {
    const val KEY_CABLE_OD = "cable_od"

    /** The Reference entry a pull note is about, or null when none fits. */
    fun forNote(text: String): String? {
        val t = text.lowercase()
        return when {
            "jam" in t || "fill" in t -> "nec-fill-percent"
            "bend" in t || "radius" in t || "360" in t || "pull point" in t -> "nec-bend-radius"
            else -> null
        }
    }
}
