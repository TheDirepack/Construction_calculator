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

internal val starterSymbols = listOf(
    electricalSymbol("Duplex receptacle", """<circle cx="12" cy="12" r="8"/><path d="M9 5v14m6-14v14"/>"""),
    electricalSymbol("Junction box", """<rect x="4" y="4" width="16" height="16"/><path d="M14 8v6q0 4-4 2"/>"""),
    electricalSymbol("Switch", """<circle cx="12" cy="12" r="8"/><path d="M15 8q-7-2-6 2t6 3q1 5-6 3"/>"""),
    electricalSymbol("Light fixture", """<circle cx="12" cy="12" r="8"/><path d="m7 7 10 10m0-10L7 17"/>"""),
)

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
