package com.thedirepack.constructioncalc.feature.conduitbending

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendType

/** What a screen reader says about the diagram. */
internal fun diagramDescription(type: BendType, backToBack: Boolean): String = when (type) {
    BendType.OFFSET ->
        "Diagram of an offset. The conduit runs along the lower line, bends up at mark 1, runs diagonally to mark 2 and continues on the higher line over an obstruction. The distance between the marks and the depth of the offset are labelled."
    BendType.ROLLING_OFFSET ->
        "Diagram of a rolling offset as a right triangle. The rise is the vertical side, the roll is the horizontal side, and the conduit's true offset is the diagonal between them."
    BendType.SADDLE_3 ->
        "Diagram of a 3-point saddle. The conduit bends up at outer mark 1, peaks at the center mark C over the obstruction, and comes back down at outer mark 3 to its original line."
    BendType.SADDLE_4 ->
        "Diagram of a 4-point saddle. The conduit bends up between marks 1 and 2, runs straight across the top of the obstruction between marks 2 and 3, and comes back down between marks 3 and 4."
    BendType.STUB_UP ->
        if (backToBack) {
            "Diagram of two 90 degree bends facing the same way, forming a U. Each stub rises from the straight run. The distance between the backs of the two bends is labelled, and the first stub's mark is set at the bender's arrow."
        } else {
            "Diagram of a 90 degree stub-up. The conduit runs horizontally and turns upward into a stub. The stub height is measured to the back of the bend, and the mark sits at the take-up distance below that height along the conduit."
        }
    BendType.KICK ->
        "Diagram of a kick. The conduit runs along the lower line, bends once at the bend mark and continues at an angle until it has risen by the kick height."
}

/** A static line drawing of a bend with its marks labelled. No animation. */
@Composable
fun BendDiagram(type: BendType, backToBack: Boolean, modifier: Modifier = Modifier) {
    val conduit = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val mark = MaterialTheme.colorScheme.tertiary
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 12.sp, color = ink)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(170.dp)
            .semantics { contentDescription = diagramDescription(type, backToBack) },
    ) {
        val d = Diagram(this, measurer, style, conduit, soft, mark, fill)
        when (type) {
            BendType.OFFSET -> d.offset()
            BendType.ROLLING_OFFSET -> d.rolling()
            BendType.SADDLE_3 -> d.saddle3()
            BendType.SADDLE_4 -> d.saddle4()
            BendType.STUB_UP -> d.stub(backToBack)
            BendType.KICK -> d.kick()
        }
    }
}

private class Diagram(
    val scope: DrawScope,
    val measurer: TextMeasurer,
    val style: TextStyle,
    val conduit: Color,
    val soft: Color,
    val mark: Color,
    val fill: Color,
) {
    private val w = scope.size.width
    private val h = scope.size.height
    private fun x(f: Float) = f * w
    private fun y(f: Float) = f * h
    private fun pt(fx: Float, fy: Float) = Offset(x(fx), y(fy))
    private val thin = with(scope) { 1.5.dp.toPx() }
    private val thick = with(scope) { 7.dp.toPx() }
    private val tick = with(scope) { 5.dp.toPx() }

    private fun text(s: String, center: Offset, color: Color = style.color) {
        val layout = measurer.measure(s, style.copy(color = color))
        scope.drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
    }

    private fun line(a: Offset, b: Offset, color: Color = soft, dashed: Boolean = false) = scope.drawLine(
        color, a, b, strokeWidth = thin,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
    )

    private fun pipe(pts: List<Offset>) {
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        scope.drawPath(path, conduit, style = Stroke(width = thick, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun obstruction(fx0: Float, fx1: Float, fy0: Float, fy1: Float) {
        val tl = pt(fx0, fy0)
        val size = Size(x(fx1) - x(fx0), y(fy1) - y(fy0))
        scope.drawRoundRect(fill, tl, size, CornerRadius(8f), style = Fill)
        scope.drawRoundRect(soft, tl, size, CornerRadius(8f), style = Stroke(thin))
        text("obstruction", Offset(tl.x + size.width / 2, tl.y + size.height / 2), soft)
    }

    /** A mark on the pipe at [p]: a tick through the pipe and a label below the drawing. */
    private fun markAt(p: Offset, label: String, labelY: Float = 0.93f) {
        scope.drawLine(mark, Offset(p.x, p.y - tick * 2), Offset(p.x, p.y + tick * 2), strokeWidth = thin * 2)
        line(Offset(p.x, p.y + tick * 2), Offset(p.x, y(labelY) - 10f), mark, dashed = true)
        text(label, Offset(p.x, y(labelY)), mark)
    }

    private fun vertical(fx: Float, fy0: Float, fy1: Float, label: String) {
        val a = pt(fx, fy0)
        val b = pt(fx, fy1)
        line(a, b)
        line(Offset(a.x - tick, a.y), Offset(a.x + tick, a.y))
        line(Offset(b.x - tick, b.y), Offset(b.x + tick, b.y))
        text(label, Offset(a.x, (a.y + b.y) / 2), soft)
    }

    private fun horizontal(fy: Float, fx0: Float, fx1: Float, label: String) {
        val a = pt(fx0, fy)
        val b = pt(fx1, fy)
        line(a, b)
        line(Offset(a.x, a.y - tick), Offset(a.x, a.y + tick))
        line(Offset(b.x, b.y - tick), Offset(b.x, b.y + tick))
        text(label, Offset((a.x + b.x) / 2, a.y - 10f), soft)
    }

    fun offset() {
        val low = 0.66f
        val high = 0.30f
        obstruction(0.62f, 0.84f, high + 0.12f, low + 0.10f)
        pipe(listOf(pt(0.05f, low), pt(0.30f, low), pt(0.52f, high), pt(0.95f, high)))
        markAt(pt(0.30f, low), "1")
        markAt(pt(0.52f, high), "2")
        text("end of stick", pt(0.11f, low - 0.12f), soft)
        horizontal(0.84f, 0.30f, 0.52f, "between marks")
        vertical(0.90f, low, high, "")
        text("depth", pt(0.935f, (low + high) / 2), soft)
    }

    fun rolling() {
        val a = pt(0.22f, 0.78f)
        val b = pt(0.74f, 0.78f)
        val c = pt(0.74f, 0.20f)
        line(a, b)
        line(b, c)
        text("roll", Offset((a.x + b.x) / 2, a.y + 14f), soft)
        text("rise", Offset(b.x + 22f, (b.y + c.y) / 2), soft)
        pipe(listOf(a, c))
        text("true offset", Offset((a.x + c.x) / 2 - 38f, (a.y + c.y) / 2 - 10f), conduit)
    }

    fun saddle3() {
        val low = 0.72f
        val high = 0.26f
        obstruction(0.42f, 0.58f, high + 0.14f, low + 0.08f)
        pipe(listOf(pt(0.05f, low), pt(0.20f, low), pt(0.50f, high), pt(0.80f, low), pt(0.95f, low)))
        markAt(pt(0.20f, low), "1")
        markAt(pt(0.50f, high), "C")
        markAt(pt(0.80f, low), "3")
        text("center bend 45° (outer 22.5°)", pt(0.50f, 0.07f), soft)
    }

    fun saddle4() {
        val low = 0.72f
        val high = 0.28f
        obstruction(0.36f, 0.64f, high + 0.12f, low + 0.08f)
        pipe(listOf(pt(0.05f, low), pt(0.16f, low), pt(0.34f, high), pt(0.66f, high), pt(0.84f, low), pt(0.95f, low)))
        markAt(pt(0.16f, low), "1")
        markAt(pt(0.34f, high), "2")
        markAt(pt(0.66f, high), "3")
        markAt(pt(0.84f, low), "4")
        horizontal(0.12f, 0.34f, 0.66f, "width")
    }

    fun stub(backToBack: Boolean) {
        val bottom = 0.74f
        val top = 0.12f
        val left = 0.22f
        val right = 0.80f
        if (backToBack) {
            pipe(listOf(pt(left, top), pt(left, bottom), pt(right, bottom), pt(right, top)))
            horizontal(0.90f, left, right, "back to back")
            markAt(pt(left + 0.10f, bottom), "mark", labelY = 0.60f)
            text("2nd mark from back", pt(right - 0.14f, bottom - 0.12f), mark)
        } else {
            pipe(listOf(pt(left, top), pt(left, bottom), pt(0.92f, bottom)))
            markAt(pt(left + 0.10f, bottom), "mark", labelY = 0.60f)
        }
        vertical(0.10f, top, bottom, "")
        text("stub", pt(0.10f, (top + bottom) / 2 - 0.10f), soft)
        text("height", pt(0.10f, (top + bottom) / 2 + 0.02f), soft)
        text("end", pt(left + 0.05f, top), soft)
        text("take-up", pt(left + 0.10f, 0.50f), soft)
    }

    fun kick() {
        val low = 0.72f
        val high = 0.28f
        pipe(listOf(pt(0.05f, low), pt(0.38f, low), pt(0.88f, high)))
        line(pt(0.38f, low), pt(0.95f, low), dashed = true)
        line(pt(0.88f, high), pt(0.95f, high), dashed = true)
        markAt(pt(0.38f, low), "bend")
        vertical(0.93f, low, high, "")
        text("height", pt(0.93f, (low + high) / 2 - 0.0f).copy(x = x(0.84f)), soft)
        text("angle", pt(0.48f, low - 0.07f), soft)
    }
}
