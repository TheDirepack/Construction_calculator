package com.thedirepack.constructioncalc.feature.conduitfill

import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.electrical.NamedCable
import com.thedirepack.constructioncalc.feature.conduitfill.domain.CircuitPreset
import com.thedirepack.constructioncalc.feature.conduitfill.domain.CircuitPresets
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ConductorRole
import com.thedirepack.constructioncalc.feature.conduitfill.domain.VoltageSystem

/** Screen state, kept across rotation. One instance per opened saved item (see the screen's key). */
class ConduitFillViewModel : ToolInputsViewModel<ConduitFillInputs>(ConduitFillInputs(), ConduitFillInputs.serializer()) {
    /** Switching type keeps the trade size when the new type has it, else the next larger one it has. */
    fun setConduitType(type: ConduitType) = update { i ->
        val current = TradeSize.fromLabel(i.tradeSize) ?: TradeSize.S_3_4
        i.copy(conduitType = type.code, tradeSize = ConduitTable.nearest(type, current).tradeSize.label)
    }

    fun setTradeSize(size: TradeSize) = update { it.copy(tradeSize = size.label) }

    fun setNipple(nipple: Boolean) = update { it.copy(nipple = nipple) }

    fun setCount(index: Int, text: String) = updateGroup(index) { it.copy(count = text.filter(Char::isDigit).take(4)) }

    /** Increments a single conductor group's count by 1 (up to 9999). */
    fun incrementCount(index: Int) = updateGroup(index) { g ->
        val next = ((g.count.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0) + 1).coerceAtMost(9999)
        g.copy(count = next.toString())
    }

    /** Decrements a conductor group's count by 1; removes the row at 0 if other rows remain, else keeps 0. */
    fun decrementCount(index: Int) = update { i ->
        if (index !in i.groups.indices) return@update i
        val current = i.groups[index].count.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (current > 1) {
            i.copy(groups = i.groups.mapIndexed { n, g -> if (n == index) g.copy(count = (current - 1).toString()) else g })
        } else if (i.groups.size > 1) {
            i.copy(groups = i.groups.filterIndexed { n, _ -> n != index })
        } else {
            i.copy(groups = listOf(i.groups[0].copy(count = "0")))
        }
    }

    fun setInsulation(index: Int, code: String) = updateGroup(index) { g ->
        val size = ConductorSize.fromLabel(g.size) ?: ConductorSize.AWG_12
        // Bare conductors are grounds: never current-carrying. Switching back to insulated restores it.
        val nextRole = when {
            code == BARE_CODE -> ConductorRole.GROUND
            g.role == ConductorRole.GROUND -> ConductorRole.GENERAL
            else -> g.role
        }
        g.copy(insulation = code, size = sizeFor(code, size).label, currentCarrying = code != BARE_CODE, role = nextRole)
    }

    fun setSize(index: Int, size: ConductorSize) = updateGroup(index) { it.copy(size = size.label) }

    fun addGroup() = update { i ->
        val template = i.groups.lastOrNull { !it.isCable } ?: ConductorGroupInput()
        if (i.groups.size >= MAX_GROUPS) i else i.copy(groups = i.groups + template.copy(count = "1"))
    }

    /** Adds a named cable as its own group (it keeps the cable's dimensions even if the cable is later deleted). */
    fun addCable(cable: NamedCable) = update { i -> CircuitPresets.addNamedCable(i, cable, MAX_GROUPS) }

    fun removeGroup(index: Int) = update { i ->
        if (index !in i.groups.indices || i.groups.size <= 1) i else i.copy(groups = i.groups.filterIndexed { n, _ -> n != index })
    }

    fun clearGroups() = update { i ->
        i.copy(
            groups = listOf(ConductorGroupInput(count = "0", role = ConductorRole.GENERAL)),
            circuitCount = 0,
        )
    }

    fun applyPreset(preset: CircuitPreset) = update { i -> CircuitPresets.apply(i, preset, maxGroups = MAX_GROUPS) }

    fun setVoltageSystem(system: VoltageSystem) = update { i ->
        i.copy(
            voltageSystem = system.name,
            sharedMwbc = if (system.supportsSharedMwbc) i.sharedMwbc else false,
        )
    }

    fun setCustomVoltageLabel(label: String) = update { it.copy(customVoltageLabel = label.take(24)) }

    fun setCircuitCount(count: Int) = update { it.copy(circuitCount = count.coerceIn(0, 99)) }

    fun incrementCircuitCount() = update { it.copy(circuitCount = (it.circuitCount + 1).coerceAtMost(99)) }

    fun decrementCircuitCount() = update { it.copy(circuitCount = (it.circuitCount - 1).coerceAtLeast(0)) }

    fun setSharedMwbc(shared: Boolean) = update { it.copy(sharedMwbc = shared) }

    fun setThreePhaseCircuits(threePhase: Boolean) = update { i ->
        val updatedGroups = syncWyeNeutralCcc(i.groups, i.voltagePreset, i.sharedMwbc, threePhase, i.nonlinearHarmonic, i.circuitCount)
        i.copy(threePhaseCircuits = threePhase, groups = updatedGroups)
    }

    fun setNonlinearHarmonic(nonlinear: Boolean) = update { i ->
        val updatedGroups = syncWyeNeutralCcc(i.groups, i.voltagePreset, i.sharedMwbc, i.threePhaseCircuits, nonlinear, i.circuitCount)
        i.copy(nonlinearHarmonic = nonlinear, groups = updatedGroups)
    }

    fun setFitInsulation(code: String) = update { i ->
        val size = ConductorSize.fromLabel(i.fitSize) ?: ConductorSize.AWG_12
        i.copy(fitInsulation = code, fitSize = sizeFor(code, size).label)
    }

    fun setFitSize(size: ConductorSize) = update { it.copy(fitSize = size.label) }

    fun setCurrentCarrying(index: Int, value: Boolean) = updateGroup(index) { g ->
        if (g.insulation == BARE_CODE) g.copy(currentCarrying = false) else g.copy(currentCarrying = value)
    }

    fun setAmbient(text: String) = update { it.copy(ambientC = text.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(12)) }

    fun setRooftop(value: Boolean) = update { it.copy(rooftop = value) }

    fun setMaterial(material: ConductorMaterial) = update { it.copy(material = material.name) }

    fun setTermination(rating: TemperatureRating) = update { it.copy(termination = rating.celsius) }

    private fun updateGroup(index: Int, change: (ConductorGroupInput) -> ConductorGroupInput) = update { i ->
        if (index !in i.groups.indices) i else i.copy(groups = i.groups.mapIndexed { n, g -> if (n == index) change(g) else g })
    }

    /**
     * Updates explicit [ConductorRole.NEUTRAL] groups when harmonic load changes on a 3φ 4W circuit
     * or balanced 3φ MWBC, while leaving dedicated 1φ L-N neutrals current-carrying.
     */
    private fun syncWyeNeutralCcc(
        groups: List<ConductorGroupInput>,
        system: VoltageSystem,
        sharedMwbc: Boolean,
        threePhaseCircuits: Boolean,
        nonlinear: Boolean,
        circuitCount: Int,
    ): List<ConductorGroupInput> {
        val wyeBalancedNeutral = (threePhaseCircuits && !sharedMwbc) ||
            (system.isThreePhaseWye && sharedMwbc && circuitCount >= 3 && circuitCount % 3 == 0)
        if (!wyeBalancedNeutral) return groups
        return groups.map { g ->
            if (g.role == ConductorRole.NEUTRAL && g.insulation != BARE_CODE) {
                g.copy(currentCarrying = nonlinear)
            } else {
                g
            }
        }
    }

    companion object {
        const val MAX_GROUPS = 12
    }
}
