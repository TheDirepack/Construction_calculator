package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.NamedCable
import com.thedirepack.constructioncalc.feature.conduitfill.BARE_CODE
import com.thedirepack.constructioncalc.feature.conduitfill.ConductorGroupInput
import com.thedirepack.constructioncalc.feature.conduitfill.ConduitFillInputs
import com.thedirepack.constructioncalc.feature.conduitfill.ConduitFillViewModel
import kotlinx.serialization.Serializable

/**
 * Common nominal supply voltage systems for conduit fill, circuit presets, and neutral guidance.
 */
enum class VoltageSystem(
    val label: String,
    val nominalVolts: Int?,
    val phases: Int,
    val supportsSharedMwbc: Boolean,
    val isThreePhaseWye: Boolean,
    val description: String,
) {
    V120(
        label = "120V",
        nominalVolts = 120,
        phases = 1,
        supportsSharedMwbc = false,
        isThreePhaseWye = false,
        description = "1φ 2-wire line-to-neutral (120V)",
    ),
    V240(
        label = "240V",
        nominalVolts = 240,
        phases = 1,
        supportsSharedMwbc = true,
        isThreePhaseWye = false,
        description = "1φ 240V L-L or 120/240V split-phase",
    ),
    WYE_208_120(
        label = "208Y/120V",
        nominalVolts = 208,
        phases = 3,
        supportsSharedMwbc = true,
        isThreePhaseWye = true,
        description = "3φ 4-wire wye (208V L-L / 120V L-N)",
    ),
    V277(
        label = "277V",
        nominalVolts = 277,
        phases = 1,
        supportsSharedMwbc = true,
        isThreePhaseWye = true,
        description = "1φ 277V line-to-neutral (off 480Y/277V wye)",
    ),
    WYE_480_277(
        label = "480Y/277V",
        nominalVolts = 480,
        phases = 3,
        supportsSharedMwbc = true,
        isThreePhaseWye = true,
        description = "3φ 4-wire wye (480V L-L / 277V L-N)",
    ),
    MEDIUM_VOLTAGE(
        label = "Medium Voltage",
        nominalVolts = 4160,
        phases = 3,
        supportsSharedMwbc = false,
        isThreePhaseWye = false,
        description = "> 1000V (shielded / MV conductors, Art. 311)",
    ),
    CUSTOM(
        label = "Custom",
        nominalVolts = null,
        phases = 1,
        supportsSharedMwbc = true,
        isThreePhaseWye = false,
        description = "Custom voltage / circuit topology",
    ),
    ;

    companion object {
        fun fromKey(raw: String): VoltageSystem {
            val normalized = raw.trim()
            return entries.firstOrNull {
                it.name.equals(normalized, ignoreCase = true) ||
                    it.label.equals(normalized, ignoreCase = true)
            } ?: V120
        }
    }
}

/**
 * Functional role of a conductor group for compact row labels and neutral/CCC syncing.
 */
@Serializable
enum class ConductorRole(val label: String) {
    GENERAL("Wire"),
    UNGROUNDED("Hot"),
    NEUTRAL("Neutral"),
    GROUND("Ground"),
}

/**
 * One-tap circuit and conductor presets shown in the preset strip.
 */
enum class CircuitPreset(val label: String) {
    SINGLE_PHASE_BRANCH_2W_G("+ 1φ Branch 2W+G"),
    THREE_PHASE_CIRCUIT_4W_G("+ 3φ Circuit 4W+G"),
    GROUND("+ Ground"),
    CUSTOM_WIRE("+ Custom Wire"),
    NAMED_CABLE("+ Named Cable"),
}

/**
 * Pure transformations applying 1-tap circuit presets and named cables to [ConduitFillInputs].
 */
object CircuitPresets {

    fun apply(
        inputs: ConduitFillInputs,
        preset: CircuitPreset,
        size: ConductorSize = ConductorSize.AWG_12,
        insulation: String = InsulationType.THHN.code,
        groundSize: ConductorSize = ConductorSize.AWG_12,
        maxGroups: Int = ConduitFillViewModel.MAX_GROUPS,
    ): ConduitFillInputs {
        val cleaned = dropEmptyPlaceholder(inputs.groups)
        return when (preset) {
            CircuitPreset.SINGLE_PHASE_BRANCH_2W_G -> {
                var groups = mergeOrAppend(
                    cleaned,
                    ConductorGroupInput("1", insulation, size.label, currentCarrying = true, role = ConductorRole.UNGROUNDED),
                    1,
                    maxGroups,
                )
                groups = mergeOrAppend(
                    groups,
                    ConductorGroupInput("1", insulation, size.label, currentCarrying = true, role = ConductorRole.NEUTRAL),
                    1,
                    maxGroups,
                )
                groups = ensureGround(groups, groundSize, maxGroups)
                inputs.copy(
                    groups = groups,
                    circuitCount = (inputs.circuitCount.coerceAtLeast(0) + 1),
                    threePhaseCircuits = false,
                )
            }

            CircuitPreset.THREE_PHASE_CIRCUIT_4W_G -> {
                var groups = mergeOrAppend(
                    cleaned,
                    ConductorGroupInput("3", insulation, size.label, currentCarrying = true, role = ConductorRole.UNGROUNDED),
                    3,
                    maxGroups,
                )
                groups = mergeOrAppend(
                    groups,
                    ConductorGroupInput(
                        count = "1",
                        insulation = insulation,
                        size = size.label,
                        currentCarrying = inputs.nonlinearHarmonic,
                        role = ConductorRole.NEUTRAL,
                    ),
                    1,
                    maxGroups,
                )
                groups = ensureGround(groups, groundSize, maxGroups)
                inputs.copy(
                    groups = groups,
                    circuitCount = (inputs.circuitCount.coerceAtLeast(0) + 1),
                    threePhaseCircuits = true,
                )
            }

            CircuitPreset.GROUND -> {
                val existingGroundIndex = cleaned.indexOfFirst {
                    it.insulation == BARE_CODE || it.role == ConductorRole.GROUND
                }
                val groups = if (existingGroundIndex >= 0) {
                    cleaned.mapIndexed { idx, g ->
                        if (idx == existingGroundIndex) {
                            val next = (g.count.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0) + 1
                            g.copy(count = next.toString(), currentCarrying = false, role = ConductorRole.GROUND)
                        } else {
                            g
                        }
                    }
                } else {
                    appendCapped(
                        cleaned,
                        ConductorGroupInput("1", BARE_CODE, groundSize.label, currentCarrying = false, role = ConductorRole.GROUND),
                        maxGroups,
                    )
                }
                inputs.copy(groups = groups)
            }

            CircuitPreset.CUSTOM_WIRE -> {
                val template = cleaned.lastOrNull { !it.isCable && it.insulation != BARE_CODE && it.role != ConductorRole.GROUND }
                val newGroup = ConductorGroupInput(
                    count = "1",
                    insulation = template?.insulation ?: insulation,
                    size = template?.size ?: size.label,
                    currentCarrying = true,
                    role = ConductorRole.GENERAL,
                )
                inputs.copy(groups = appendCapped(cleaned, newGroup, maxGroups))
            }

            CircuitPreset.NAMED_CABLE -> inputs // Handled by UI opening the Named Cable picker dialog.
        }
    }

    fun addNamedCable(
        inputs: ConduitFillInputs,
        cable: NamedCable,
        maxGroups: Int = ConduitFillViewModel.MAX_GROUPS,
    ): ConduitFillInputs {
        val cleaned = dropEmptyPlaceholder(inputs.groups)
        return inputs.copy(groups = appendCapped(cleaned, ConductorGroupInput.of(cable), maxGroups))
    }

    /**
     * Adds one shared EGC per NEC 250.122(C) when the conduit doesn't already have an active ground.
     */
    private fun ensureGround(
        groups: List<ConductorGroupInput>,
        groundSize: ConductorSize,
        maxGroups: Int,
    ): List<ConductorGroupInput> {
        val hasActiveGround = groups.any {
            (it.insulation == BARE_CODE || it.role == ConductorRole.GROUND) &&
                (it.count.trim().toIntOrNull() ?: 0) > 0
        }
        if (hasActiveGround) return groups
        val zeroGroundIndex = groups.indexOfFirst { it.insulation == BARE_CODE || it.role == ConductorRole.GROUND }
        return if (zeroGroundIndex >= 0) {
            groups.mapIndexed { idx, g ->
                if (idx == zeroGroundIndex) g.copy(count = "1", currentCarrying = false, role = ConductorRole.GROUND) else g
            }
        } else {
            appendCapped(
                groups,
                ConductorGroupInput("1", BARE_CODE, groundSize.label, currentCarrying = false, role = ConductorRole.GROUND),
                maxGroups,
            )
        }
    }

    private fun dropEmptyPlaceholder(groups: List<ConductorGroupInput>): List<ConductorGroupInput> =
        if (groups.size == 1 && (groups[0].count.trim().toIntOrNull() ?: 0) == 0 && !groups[0].isCable) {
            emptyList()
        } else {
            groups
        }

    private fun mergeOrAppend(
        groups: List<ConductorGroupInput>,
        addition: ConductorGroupInput,
        delta: Int,
        maxGroups: Int,
    ): List<ConductorGroupInput> {
        val matchIndex = groups.indexOfFirst {
            !it.isCable &&
                it.insulation == addition.insulation &&
                it.size == addition.size &&
                it.currentCarrying == addition.currentCarrying &&
                it.role == addition.role
        }
        return if (matchIndex >= 0) {
            groups.mapIndexed { idx, g ->
                if (idx == matchIndex) {
                    val current = g.count.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    g.copy(count = (current + delta).toString())
                } else {
                    g
                }
            }
        } else {
            appendCapped(groups, addition.copy(count = delta.toString()), maxGroups)
        }
    }

    private fun appendCapped(
        groups: List<ConductorGroupInput>,
        addition: ConductorGroupInput,
        maxGroups: Int,
    ): List<ConductorGroupInput> = if (groups.size >= maxGroups) groups else groups + addition
}
