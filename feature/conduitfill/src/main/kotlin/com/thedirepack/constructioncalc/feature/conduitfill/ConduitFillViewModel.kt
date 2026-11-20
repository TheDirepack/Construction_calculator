package com.thedirepack.constructioncalc.feature.conduitfill

import com.thedirepack.constructioncalc.core.ui.tool.ToolInputsViewModel
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.TemperatureRating
import com.thedirepack.constructioncalc.core.electrical.ConduitTable
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize

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

    fun setInsulation(index: Int, code: String) = updateGroup(index) { g ->
        val size = ConductorSize.fromLabel(g.size) ?: ConductorSize.AWG_12
        // Bare conductors are grounds: never current-carrying. Switching back to insulated restores it.
        g.copy(insulation = code, size = sizeFor(code, size).label, currentCarrying = code != BARE_CODE)
    }

    fun setSize(index: Int, size: ConductorSize) = updateGroup(index) { it.copy(size = size.label) }

    fun addGroup() = update { i ->
        if (i.groups.size >= MAX_GROUPS) i else i.copy(groups = i.groups + (i.groups.lastOrNull() ?: ConductorGroupInput()).copy(count = "1"))
    }

    fun removeGroup(index: Int) = update { i ->
        if (index !in i.groups.indices || i.groups.size <= 1) i else i.copy(groups = i.groups.filterIndexed { n, _ -> n != index })
    }

    fun setFitInsulation(code: String) = update { i ->
        val size = ConductorSize.fromLabel(i.fitSize) ?: ConductorSize.AWG_12
        i.copy(fitInsulation = code, fitSize = sizeFor(code, size).label)
    }

    fun setFitSize(size: ConductorSize) = update { it.copy(fitSize = size.label) }

    fun setCurrentCarrying(index: Int, value: Boolean) = updateGroup(index) { it.copy(currentCarrying = value) }

    fun setAmbient(text: String) = update { it.copy(ambientC = text.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(6)) }

    fun setRooftop(value: Boolean) = update { it.copy(rooftop = value) }

    fun setMaterial(material: ConductorMaterial) = update { it.copy(material = material.name) }

    fun setTermination(rating: TemperatureRating) = update { it.copy(termination = rating.celsius) }

    private fun updateGroup(index: Int, change: (ConductorGroupInput) -> ConductorGroupInput) = update { i ->
        if (index !in i.groups.indices) i else i.copy(groups = i.groups.mapIndexed { n, g -> if (n == index) change(g) else g })
    }

    companion object {
        const val MAX_GROUPS = 12
    }
}
