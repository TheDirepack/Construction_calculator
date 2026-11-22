package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.PlanSymbol
import com.thedirepack.constructioncalc.core.data.drawPlanSymbol

internal data class StarterSymbolEntry(val symbol: PlanSymbol, val set: String)

internal val starterCatalog = listOf(
    StarterSymbolEntry(electricalSymbol("Duplex receptacle", """<circle cx="12" cy="12" r="8"/><path d="M9 5v14m6-14v14"/>"""), "Power"),
    StarterSymbolEntry(electricalSymbol("GFCI receptacle", """<circle cx="12" cy="12" r="8"/><path d="M9 5v14m6-14v14M7 12h10"/>"""), "Power"),
    StarterSymbolEntry(electricalSymbol("Dedicated 240V outlet", """<circle cx="12" cy="12" r="8"/><path d="M8 7v10m4-10v10m4-10v10"/>"""), "Power"),
    StarterSymbolEntry(electricalSymbol("Floor receptacle", """<rect x="4" y="4" width="16" height="16"/><circle cx="12" cy="12" r="6"/><path d="M9 7v10m6-10v10"/>"""), "Power"),
    StarterSymbolEntry(electricalSymbol("Disconnect switch", """<rect x="4" y="5" width="16" height="14"/><path d="M4 12h5l6-5m0 5h5"/>"""), "Power"),

    StarterSymbolEntry(electricalSymbol("Switch", """<circle cx="12" cy="12" r="8"/><path d="M15 8q-7-2-6 2t6 3q1 5-6 3"/>"""), "Lighting"),
    StarterSymbolEntry(electricalSymbol("3-way switch", """<circle cx="12" cy="12" r="8"/><path d="M8 8h7l-4 4 4 4H8"/>"""), "Lighting"),
    StarterSymbolEntry(electricalSymbol("Light fixture", """<circle cx="12" cy="12" r="8"/><path d="m7 7 10 10m0-10L7 17"/>"""), "Lighting"),
    StarterSymbolEntry(electricalSymbol("Emergency light", """<rect x="6" y="10" width="12" height="8" rx="1"/><circle cx="8" cy="7" r="2.2"/><circle cx="16" cy="7" r="2.2"/>"""), "Lighting"),
    StarterSymbolEntry(electricalSymbol("Exit sign", """<rect x="3" y="7" width="18" height="10" rx="1"/><path d="m7 12 3-2.5v5zm10 0-3-2.5v5z"/>"""), "Lighting"),

    StarterSymbolEntry(electricalSymbol("Junction box", """<rect x="4" y="4" width="16" height="16"/><path d="M14 8v6q0 4-4 2"/>"""), "Boxes & Panels"),
    StarterSymbolEntry(electricalSymbol("Electrical panel", """<rect x="3" y="6" width="18" height="12"/><polygon points="3,6 21,6 21,18" fill="#146db0"/>"""), "Boxes & Panels"),
    StarterSymbolEntry(electricalSymbol("Pull box", """<rect x="4" y="5" width="16" height="14"/><path d="M7 9h6m0 0-2-2m2 2-2 2M17 15h-6m0 0 2-2m-2 2 2 2"/>"""), "Boxes & Panels"),

    StarterSymbolEntry(electricalSymbol("Data drop", """<polygon points="12,4 20,19 4,19"/>"""), "Low Voltage"),
    StarterSymbolEntry(electricalSymbol("Fire alarm device", """<polygon points="12,4 20,12 12,20 4,12"/><circle cx="12" cy="12" r="2.5"/>"""), "Low Voltage"),
)

internal val starterSymbols: List<PlanSymbol> = starterCatalog.map { it.symbol }

internal val builtInSymbolSets: Map<String, List<String>> = starterCatalog.groupBy({ it.set }, { it.symbol.id })

private fun electricalSymbol(name: String, shape: String) = PlanSymbol(
    "built-in:$name", name,
    """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><g fill="none" stroke="#146db0" stroke-width="1.8">$shape</g></svg>""",
)

@Composable
internal fun SymbolPreview(symbol: PlanSymbol) {
    Canvas(Modifier.size(32.dp)) {
        drawIntoCanvas { drawPlanSymbol(it.nativeCanvas, symbol.svg, 0f, 0f, size.width, size.height) }
    }
}
