package com.thedirepack.constructioncalc.feature.conduitfill.domain

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule

/**
 * Inputs for determining required neutral conductors and whether they count as current-carrying
 * conductors (CCC) per NEC 210.4 and 310.15(E).
 */
data class NeutralGuidanceRequest(
    val voltageSystem: VoltageSystem,
    val circuitCount: Int = 1,
    val sharedMwbc: Boolean = false,
    val nonlinearHarmonic: Boolean = false,
    /**
     * When true on a 3φ wye system ([VoltageSystem.WYE_208_120] or [VoltageSystem.WYE_480_277])
     * with [sharedMwbc] false, models 3φ 4-wire circuits (3 hots + 1 neutral each) instead of
     * dedicated 1φ L-N branch circuits (1 hot + 1 neutral each).
     */
    val threePhaseCircuits: Boolean = false,
)

/**
 * Guidance on required neutral count, NEC 210.4 MWBC simultaneous-disconnect requirements,
 * and NEC 310.15(E) current-carrying conductor (CCC) classification.
 */
data class NeutralGuidanceResult(
    val voltageSystem: VoltageSystem,
    val circuitCount: Int,
    val sharedMwbc: Boolean,
    val nonlinearHarmonic: Boolean,
    /** Number of neutrals required, or null for [VoltageSystem.CUSTOM] where topology is user-defined. */
    val requiredNeutrals: Int?,
    /** Number of required neutrals that count toward Table 310.15(C)(1) CCC adjustment, or null for Custom. */
    val neutralCccCount: Int?,
    val anyNeutralCountsAsCcc: Boolean,
    val headline: String,
    val explanation: String,
    /** Simultaneous disconnecting means / handle-tie warning per NEC 210.4(B) when MWBC applies. */
    val handleTieNote: String? = null,
    val notes: List<String> = emptyList(),
)

/**
 * Pure NEC neutral-count and CCC guidance engine.
 */
object NeutralGuidance {

    fun evaluate(
        voltageSystem: VoltageSystem,
        circuitCount: Int = 1,
        sharedMwbc: Boolean = false,
        nonlinearHarmonic: Boolean = false,
        threePhaseCircuits: Boolean = false,
    ): NeutralGuidanceResult = evaluate(
        NeutralGuidanceRequest(
            voltageSystem = voltageSystem,
            circuitCount = circuitCount,
            sharedMwbc = sharedMwbc,
            nonlinearHarmonic = nonlinearHarmonic,
            threePhaseCircuits = threePhaseCircuits,
        ),
    )

    fun evaluate(request: NeutralGuidanceRequest): NeutralGuidanceResult {
        val circuits = request.circuitCount.coerceAtLeast(0)
        val system = request.voltageSystem
        val shared = request.sharedMwbc
        val nonlinear = request.nonlinearHarmonic

        if (circuits == 0 && system != VoltageSystem.MEDIUM_VOLTAGE && system != VoltageSystem.CUSTOM) {
            return NeutralGuidanceResult(
                voltageSystem = system,
                circuitCount = 0,
                sharedMwbc = shared,
                nonlinearHarmonic = nonlinear,
                requiredNeutrals = 0,
                neutralCccCount = 0,
                anyNeutralCountsAsCcc = false,
                headline = "0 circuits · 0 neutrals",
                explanation = "Set circuit count above 0 (or tap a circuit preset) to calculate required neutrals.",
            )
        }

        return when (system) {
            VoltageSystem.V120 -> evaluate120V(circuits, shared, nonlinear)
            VoltageSystem.V240 -> evaluate240V(circuits, shared, nonlinear)
            VoltageSystem.V277 -> evaluate277V(circuits, shared, nonlinear)
            VoltageSystem.WYE_208_120,
            VoltageSystem.WYE_480_277,
            -> evaluateThreePhaseWye(system, circuits, shared, nonlinear, request.threePhaseCircuits)
            VoltageSystem.MEDIUM_VOLTAGE -> NeutralGuidanceResult(
                voltageSystem = system,
                circuitCount = circuits,
                sharedMwbc = false,
                nonlinearHarmonic = nonlinear,
                requiredNeutrals = 0,
                neutralCccCount = 0,
                anyNeutralCountsAsCcc = false,
                headline = "0 neutrals (3φ MV; verify Art. 311)",
                explanation = "Medium voltage (> 1000 V) feeders are typically 3φ 3-wire shielded cables with no neutral (or 1 grounded neutral on a 4-wire wye system).",
                notes = listOf(
                    "Conductors > 2000 V follow NEC Article 311 / 310.60 ampacity tables rather than Table 310.16; verify cable OD and shielding with manufacturer data.",
                ),
            )

            VoltageSystem.CUSTOM -> NeutralGuidanceResult(
                voltageSystem = system,
                circuitCount = circuits,
                sharedMwbc = shared,
                nonlinearHarmonic = nonlinear,
                requiredNeutrals = null,
                neutralCccCount = null,
                anyNeutralCountsAsCcc = nonlinear,
                headline = if (nonlinear) "Custom · harmonics count neutral as CCC" else "Custom · check 310.15(E) per wire",
                explanation = "Custom voltage topology: count 1 neutral per 1φ L-N circuit (counts as CCC), or 1 shared neutral per MWBC/3φ4W circuit. " +
                    "On a 3φ 4-wire wye, 2-phase+N neutrals (${CodeRule.NEUTRAL_COUNTING.cite}(2)) and nonlinear-load neutrals (${CodeRule.NEUTRAL_COUNTING.cite}(3)) count as CCC.",
                handleTieNote = if (shared && circuits >= 2) {
                    "NEC 210.4(B): Any multi-wire branch circuit sharing a neutral requires a simultaneous disconnecting means (multipole breaker or handle tie)."
                } else {
                    null
                },
            )
        }
    }

    private fun evaluate120V(circuits: Int, shared: Boolean, nonlinear: Boolean): NeutralGuidanceResult {
        val notes = mutableListOf<String>()
        val handleTieNote = if (shared) {
            "Single-phase 120V circuits on the same leg cannot share a neutral (NEC 200.4(A) / 210.4). Use 240V (120/240V) or 208Y/120V wye for MWBCs."
        } else {
            null
        }
        if (nonlinear) {
            notes += "1φ 2-wire neutrals already carry full fundamental load current and count as CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1))."
        }
        val noun = plural(circuits, "neutral")
        return NeutralGuidanceResult(
            voltageSystem = VoltageSystem.V120,
            circuitCount = circuits,
            sharedMwbc = shared,
            nonlinearHarmonic = nonlinear,
            requiredNeutrals = circuits,
            neutralCccCount = circuits,
            anyNeutralCountsAsCcc = true,
            headline = "$circuits $noun · $circuits CCC (dedicated L-N)",
            explanation = "$circuits dedicated $noun required for $circuits × 120V 1φ circuit${if (circuits == 1) "" else "s"}. Each 2-wire neutral carries full load current and counts as a CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1)).",
            handleTieNote = handleTieNote,
            notes = notes,
        )
    }

    private fun evaluate240V(circuits: Int, shared: Boolean, nonlinear: Boolean): NeutralGuidanceResult {
        if (!shared) {
            return NeutralGuidanceResult(
                voltageSystem = VoltageSystem.V240,
                circuitCount = circuits,
                sharedMwbc = false,
                nonlinearHarmonic = nonlinear,
                requiredNeutrals = 0,
                neutralCccCount = 0,
                anyNeutralCountsAsCcc = false,
                headline = "0 neutrals (240V L-L)",
                explanation = "Pure 240V line-to-line circuits require 0 neutrals. Toggle Shared MWBC for 120/240V multi-wire branch circuits sharing a neutral across L1 & L2.",
                notes = listOf(
                    "If pulling a 120/240V 3-wire feeder/circuit whose neutral carries only unbalanced current, that neutral does not count as CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1)).",
                ),
            )
        }
        val mwbcPairs = circuits / 2
        val oddSingle = circuits % 2
        val neutrals = mwbcPairs + oddSingle
        val cccCount = oddSingle + if (nonlinear) mwbcPairs else 0
        val notes = mutableListOf<String>()
        if (mwbcPairs > 0 && !nonlinear) {
            notes += "Each 120/240V L1+L2+N pair neutral carries only unbalanced current and does not count as CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1))."
        }
        if (mwbcPairs > 0 && nonlinear) {
            notes += "Nonlinear loads flagged: shared 120/240V neutral counted as CCC (${CodeRule.NEUTRAL_COUNTING.cite})."
        }
        if (oddSingle > 0) {
            notes += "Odd circuit ($oddSingle unpaired 120V circuit) requires its own dedicated neutral which counts as 1 CCC."
        }
        val handleTie = if (mwbcPairs > 0) {
            "NEC 210.4(B): Each 120/240V multi-wire branch circuit requires a simultaneous disconnecting means (2-pole breaker or listed handle tie) at the panelboard."
        } else {
            null
        }
        val noun = plural(neutrals, "neutral")
        return NeutralGuidanceResult(
            voltageSystem = VoltageSystem.V240,
            circuitCount = circuits,
            sharedMwbc = true,
            nonlinearHarmonic = nonlinear,
            requiredNeutrals = neutrals,
            neutralCccCount = cccCount,
            anyNeutralCountsAsCcc = cccCount > 0,
            headline = buildString {
                append("$neutrals ")
                if (mwbcPairs > 0) append("MWBC ")
                append("$noun · $cccCount CCC")
                if (mwbcPairs > 0) append(" (NEC 210.4 handle tie)")
            },
            explanation = "$circuits circuit${if (circuits == 1) "" else "s"} on 120/240V split-phase MWBC need $neutrals $noun ($mwbcPairs shared pair${if (mwbcPairs == 1) "" else "s"}" +
                (if (oddSingle > 0) " + 1 dedicated single" else "") +
                "): $cccCount count${if (cccCount == 1) "s" else ""} as CCC.",
            handleTieNote = handleTie,
            notes = notes,
        )
    }

    private fun evaluate277V(circuits: Int, shared: Boolean, nonlinear: Boolean): NeutralGuidanceResult {
        if (!shared) {
            val noun = plural(circuits, "neutral")
            return NeutralGuidanceResult(
                voltageSystem = VoltageSystem.V277,
                circuitCount = circuits,
                sharedMwbc = false,
                nonlinearHarmonic = nonlinear,
                requiredNeutrals = circuits,
                neutralCccCount = circuits,
                anyNeutralCountsAsCcc = true,
                headline = "$circuits dedicated $noun · $circuits CCC",
                explanation = "$circuits dedicated $noun required for $circuits × 277V 1φ L-N circuit${if (circuits == 1) "" else "s"}. Each dedicated neutral carries full load current and counts as a CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1)).",
            )
        }
        return evaluateWyeMwbc(VoltageSystem.V277, circuits, nonlinear)
    }

    private fun evaluateThreePhaseWye(
        system: VoltageSystem,
        circuits: Int,
        shared: Boolean,
        nonlinear: Boolean,
        threePhaseCircuits: Boolean,
    ): NeutralGuidanceResult {
        if (shared) {
            return evaluateWyeMwbc(system, circuits, nonlinear)
        }
        if (threePhaseCircuits) {
            val ccc = if (nonlinear) circuits else 0
            val noun = plural(circuits, "neutral")
            val notes = listOf(
                if (nonlinear) {
                    "Nonlinear / harmonic loads (> 50% nonlinear) on 3φ 4-wire wye: neutral carries triplen harmonics and counts as CCC (${CodeRule.NEUTRAL_COUNTING.cite}(3))."
                } else {
                    "Linear 3φ 4-wire wye neutral carries only unbalanced current and does not count as CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1))."
                },
            )
            return NeutralGuidanceResult(
                voltageSystem = system,
                circuitCount = circuits,
                sharedMwbc = false,
                nonlinearHarmonic = nonlinear,
                requiredNeutrals = circuits,
                neutralCccCount = ccc,
                anyNeutralCountsAsCcc = ccc > 0,
                headline = "$circuits $noun (3φ 4W) · $ccc CCC" + if (nonlinear) " (310.15(E)(3))" else " (linear)",
                explanation = "$circuits × ${system.label} 3φ 4-wire circuit${if (circuits == 1) "" else "s"} require $circuits $noun; $ccc count${if (ccc == 1) "s" else ""} as CCC.",
                notes = notes,
            )
        }
        // Dedicated neutral per 1φ L-N branch circuit off the wye system
        val noun = plural(circuits, "neutral")
        return NeutralGuidanceResult(
            voltageSystem = system,
            circuitCount = circuits,
            sharedMwbc = false,
            nonlinearHarmonic = nonlinear,
            requiredNeutrals = circuits,
            neutralCccCount = circuits,
            anyNeutralCountsAsCcc = true,
            headline = "$circuits dedicated $noun · $circuits CCC",
            explanation = "$circuits dedicated $noun required for $circuits × 1φ L-N branch circuit${if (circuits == 1) "" else "s"} on ${system.label}. Dedicated 2-wire neutrals carry full load current and count as CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1)).",
            notes = if (nonlinear) {
                listOf("Dedicated 2-wire branch neutrals already count as CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1)); harmonics do not add extra neutral CCCs.")
            } else {
                emptyList()
            },
        )
    }

    private fun evaluateWyeMwbc(
        system: VoltageSystem,
        circuits: Int,
        nonlinear: Boolean,
    ): NeutralGuidanceResult {
        val fullGroups = circuits / 3
        val remainder = circuits % 3
        val neutrals = fullGroups + if (remainder > 0) 1 else 0
        val fullGroupCcc = if (nonlinear) fullGroups else 0
        val remainderCcc = if (remainder > 0) 1 else 0
        val cccCount = fullGroupCcc + remainderCcc
        val notes = mutableListOf<String>()
        if (fullGroups > 0) {
            notes += if (nonlinear) {
                "3-phase wye MWBC with nonlinear/harmonic loads: neutral counts as CCC (${CodeRule.NEUTRAL_COUNTING.cite}(3))."
            } else {
                "Balanced 3-phase wye MWBC (3 phases + N): linear neutral carries only unbalanced current and does not count as CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1))."
            }
        }
        if (remainder == 2) {
            notes += "Partial MWBC (2 phases + neutral of a 3φ wye): neutral carries ~phase current and MUST count as 1 CCC (${CodeRule.NEUTRAL_COUNTING.cite}(2))."
        } else if (remainder == 1) {
            notes += "Single remaining 1φ L-N circuit: its neutral carries full current and counts as 1 CCC (${CodeRule.NEUTRAL_COUNTING.cite}(1))."
        }
        val handleTie = if (circuits >= 2) {
            "NEC 210.4(B): Each multi-wire branch circuit sharing a neutral on ${system.label} requires a simultaneous disconnecting means (2-pole/3-pole breaker or listed handle tie) at the panelboard."
        } else {
            null
        }
        val noun = plural(neutrals, "neutral")
        val headline = buildString {
            append("$neutrals shared $noun · $cccCount CCC")
            when {
                remainder == 2 -> append(" (2φ+N, 310.15(E)(2))")
                nonlinear && fullGroups > 0 -> append(" (310.15(E)(3) harmonics)")
                handleTie != null -> append(" (NEC 210.4 handle tie)")
            }
        }
        return NeutralGuidanceResult(
            voltageSystem = system,
            circuitCount = circuits,
            sharedMwbc = true,
            nonlinearHarmonic = nonlinear,
            requiredNeutrals = neutrals,
            neutralCccCount = cccCount,
            anyNeutralCountsAsCcc = cccCount > 0,
            headline = headline,
            explanation = "$circuits circuit${if (circuits == 1) "" else "s"} on ${system.label} MWBC require $neutrals shared $noun ($fullGroups full 3φ group${if (fullGroups == 1) "" else "s"}" +
                (if (remainder > 0) " + $remainder remaining circuit${if (remainder == 1) "" else "s"}" else "") +
                "); $cccCount count${if (cccCount == 1) "s" else ""} as CCC.",
            handleTieNote = handleTie,
            notes = notes,
        )
    }

    private fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"
}
