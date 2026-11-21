package com.thedirepack.constructioncalc.feature.pullplanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.feature.pullplanner.domain.PathProfile
import kotlin.math.max

/**
 * A static side view of the route: the line climbs and drops with inclines and vertical bends,
 * dots mark horizontal bends (with their angle) and squares mark pull points. No animation.
 */
@Composable
internal fun PathDiagram(path: List<PathSegment>, type: ConduitType?, tradeSize: TradeSize?, description: String) {
    if (path.isEmpty()) return
    val profile = remember(path, type, tradeSize) { PathProfile.of(path, type, tradeSize) }
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(
        Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description },
    ) {
        val pad = 24.dp.toPx().toDouble()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val spanX = max(profile.maxX - profile.minX, 1e-6)
        val spanY = profile.maxY - profile.minY
        val flat = spanY < 1e-6
        // Same scale on both axes when it fits, so slopes look like slopes; flat routes sit mid-height.
        val scale = if (flat) w / spanX else minOf(w / spanX, h / spanY)
        val top = pad + (h - spanY * scale) / 2
        fun map(x: Double, y: Double) = Offset(
            (pad + (x - profile.minX) * scale).toFloat(),
            if (flat) size.height / 2 else (top + (profile.maxY - y) * scale).toFloat(),
        )
        val route = Path()
        profile.points.forEachIndexed { i, p ->
            val o = map(p.x, p.y)
            if (i == 0) route.moveTo(o.x, o.y) else route.lineTo(o.x, o.y)
        }
        drawPath(route, line, style = Stroke(width = 3.dp.toPx()))
        profile.markers.forEach { m ->
            val o = map(m.at.x, m.at.y)
            if (m.pullPoint) {
                val s = 12.dp.toPx()
                drawRect(accent, topLeft = Offset(o.x - s / 2, o.y - s / 2), size = Size(s, s))
            } else {
                drawCircle(accent, radius = 5.dp.toPx(), center = o)
            }
            val text = measurer.measure(m.label, labelStyle)
            val tx = (o.x - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
            val ty = (o.y - 10.dp.toPx() - text.size.height).coerceAtLeast(0f)
            drawText(text, topLeft = Offset(tx, ty))
        }
    }
}
