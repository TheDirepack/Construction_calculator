package com.thedirepack.constructioncalc.feature.conduitbending

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Pipe3DPath
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickPlan
import com.thedirepack.constructioncalc.feature.conduitbending.domain.Vec3
import com.thedirepack.constructioncalc.feature.conduitbending.domain.fmt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class PreviewViewPreset(val label: String, val yawDeg: Float, val pitchDeg: Float) {
    ISO("Iso", -35f, 24f),
    TOP("Top", 0f, 89.9f),
    SIDE("Side", 0f, 0f),
}

private data class ProjectedVec3(val sx: Double, val sy: Double, val depth: Double)

private data class PipeSegment3D(
    val a: Offset,
    val b: Offset,
    val depth: Double,
    val remaining: Boolean,
)

internal fun pipe3DDescription(plan: StickPlan): String {
    if (!plan.hasResults) {
        return "3D preview of an unbent ${plan.stickLengthDouble.fmt()} inch conduit stick. Enter bend measurements to preview the bent pipe."
    }
    return "3D preview of ${plan.bendResults.count { it.hasResults }} bend group(s), " +
        "total ${plan.totalDegrees.fmt()} degrees, cut length ${plan.cutLengthDouble.fmt()} inches."
}

/**
 * Interactive 3D wireframe/tube preview of the conduit stick. Drag anywhere on the canvas to
 * orbit yaw and pitch, or tap Iso / Top / Side preset chips.
 */
@Composable
fun Pipe3DPreview(
    path: Pipe3DPath,
    plan: StickPlan,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = 136.dp,
) {
    var yawDeg by rememberSaveable { mutableFloatStateOf(PreviewViewPreset.ISO.yawDeg) }
    var pitchDeg by rememberSaveable { mutableFloatStateOf(PreviewViewPreset.ISO.pitchDeg) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val variantBg = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
    val softInk = MaterialTheme.colorScheme.onSurfaceVariant
    val markColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = softInk)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = variantBg,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(canvasHeight),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            yawDeg = (yawDeg + dragAmount.x * 0.45f) % 360f
                            pitchDeg = (pitchDeg + dragAmount.y * 0.45f).coerceIn(-89.9f, 89.9f)
                        }
                    }
                    .semantics { contentDescription = pipe3DDescription(plan) },
            ) {
                val yaw = Math.toRadians(yawDeg.toDouble())
                val pitch = Math.toRadians(pitchDeg.toDouble())
                val cosY = cos(yaw)
                val sinY = sin(yaw)
                val cosP = cos(pitch)
                val sinP = sin(pitch)

                val center = (path.minBounds + path.maxBounds) * 0.5

                fun projectRaw(p: Vec3): ProjectedVec3 {
                    val q = p - center
                    val x1 = q.x * cosY - q.y * sinY
                    val y1 = q.x * sinY + q.y * cosY
                    val z1 = q.z
                    val sx = x1
                    val sy = -(y1 * sinP + z1 * cosP)
                    val depth = y1 * cosP - z1 * sinP
                    return ProjectedVec3(sx, sy, depth)
                }

                val allPoints = buildList {
                    addAll(path.points)
                    addAll(path.remainingPoints)
                    add(Vec3.ZERO)
                    add(Vec3(max(12.0, path.maxBounds.x), 0.0, 0.0))
                }
                val rawProj = allPoints.map(::projectRaw)
                val minSX = rawProj.minOf { it.sx }
                val maxSX = rawProj.maxOf { it.sx }
                val minSY = rawProj.minOf { it.sy }
                val maxSY = rawProj.maxOf { it.sy }

                val padH = 26.dp.toPx()
                val padTop = 18.dp.toPx()
                val padBottom = 32.dp.toPx()
                val availW = (size.width - padH * 2f).coerceAtLeast(24f)
                val availH = (size.height - padTop - padBottom).coerceAtLeast(24f)

                val spanX = max(12.0, maxSX - minSX)
                val spanY = max(12.0, maxSY - minSY)
                val scale = min(availW / spanX, availH / spanY).toFloat()
                val midSX = (minSX + maxSX) * 0.5
                val midSY = (minSY + maxSY) * 0.5
                val centerX = size.width * 0.5f
                val centerY = padTop + availH * 0.5f

                fun toOffset(p: Vec3): Pair<Offset, Double> {
                    val pr = projectRaw(p)
                    return Offset(
                        centerX + ((pr.sx - midSX) * scale).toFloat(),
                        centerY + ((pr.sy - midSY) * scale).toFloat(),
                    ) to pr.depth
                }

                // Subtle reference original axis along +X from End A
                val (refStart, _) = toOffset(Vec3.ZERO)
                val refLen = max(24.0, (path.maxBounds.x - path.minBounds.x).coerceAtLeast(plan.stickLengthDouble * 0.6))
                val (refEnd, _) = toOffset(Vec3(refLen, 0.0, 0.0))
                drawLine(
                    color = softInk.copy(alpha = 0.25f),
                    start = refStart,
                    end = refEnd,
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )

                // Collect segments and painter-sort back-to-front by midpoint depth
                val segments = mutableListOf<PipeSegment3D>()
                for (i in 0 until path.points.size - 1) {
                    val (aOff, aDep) = toOffset(path.points[i])
                    val (bOff, bDep) = toOffset(path.points[i + 1])
                    segments += PipeSegment3D(aOff, bOff, (aDep + bDep) * 0.5, remaining = false)
                }
                for (i in 0 until path.remainingPoints.size - 1) {
                    val (aOff, aDep) = toOffset(path.remainingPoints[i])
                    val (bOff, bDep) = toOffset(path.remainingPoints[i + 1])
                    segments += PipeSegment3D(aOff, bOff, (aDep + bDep) * 0.5, remaining = true)
                }
                segments.sortBy { it.depth }

                val tubeWidth = 6.dp.toPx()
                val outlineWidth = 8.5.dp.toPx()
                val highlightWidth = 1.6.dp.toPx()
                val bodyColor = if (plan.overflowsStick) errorColor.copy(alpha = 0.9f) else primaryColor
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

                for (seg in segments) {
                    if (seg.remaining) {
                        drawLine(
                            color = softInk.copy(alpha = 0.48f),
                            start = seg.a,
                            end = seg.b,
                            strokeWidth = tubeWidth * 0.72f,
                            cap = StrokeCap.Round,
                            pathEffect = dashEffect,
                        )
                    } else if (plan.hasResults) {
                        drawLine(surfaceColor, seg.a, seg.b, strokeWidth = outlineWidth, cap = StrokeCap.Round)
                        drawLine(bodyColor, seg.a, seg.b, strokeWidth = tubeWidth, cap = StrokeCap.Round)
                        drawLine(Color.White.copy(alpha = 0.26f), seg.a, seg.b, strokeWidth = highlightWidth, cap = StrokeCap.Round)
                    } else {
                        drawLine(
                            color = softInk.copy(alpha = 0.55f),
                            start = seg.a,
                            end = seg.b,
                            strokeWidth = tubeWidth * 0.7f,
                            cap = StrokeCap.Round,
                        )
                    }
                }

                // Draw bend mark indicators
                if (plan.hasResults) {
                    path.bends.forEach { bendArc ->
                        val (pt, _) = toOffset(bendArc.centerPoint)
                        drawCircle(surfaceColor, radius = 4.dp.toPx(), center = pt)
                        drawCircle(markColor, radius = 2.6.dp.toPx(), center = pt)
                    }
                }

                // End A and End B labels
                val startPoint = path.points.firstOrNull() ?: Vec3.ZERO
                val endPoint = path.remainingPoints.lastOrNull() ?: path.points.lastOrNull() ?: startPoint
                val (endAOff, _) = toOffset(startPoint)
                val (endBOff, _) = toOffset(endPoint)

                drawCircle(primaryColor, radius = 3.5.dp.toPx(), center = endAOff)
                val aLayout = measurer.measure("A", labelStyle.copy(color = primaryColor))
                drawText(
                    aLayout,
                    topLeft = Offset(
                        (endAOff.x - aLayout.size.width / 2f).coerceIn(4f, size.width - aLayout.size.width - 4f),
                        (endAOff.y - aLayout.size.height - 6.dp.toPx()).coerceIn(2f, size.height - aLayout.size.height - 2f),
                    ),
                )

                drawCircle(softInk, radius = 3.dp.toPx(), center = endBOff)
                val bLayout = measurer.measure("B", labelStyle)
                drawText(
                    bLayout,
                    topLeft = Offset(
                        (endBOff.x - bLayout.size.width / 2f).coerceIn(4f, size.width - bLayout.size.width - 4f),
                        (endBOff.y - bLayout.size.height - 6.dp.toPx()).coerceIn(2f, size.height - bLayout.size.height - 2f),
                    ),
                )

                // Small 3D orientation triad in bottom-left
                val triadOrigin = Offset(22.dp.toPx(), size.height - 18.dp.toPx())
                val triadLen = 12.dp.toPx()
                fun triadEnd(dir: Vec3): Offset {
                    val x1 = dir.x * cosY - dir.y * sinY
                    val y1 = dir.x * sinY + dir.y * cosY
                    val z1 = dir.z
                    return Offset(
                        triadOrigin.x + (x1 * triadLen).toFloat(),
                        triadOrigin.y - ((y1 * sinP + z1 * cosP) * triadLen).toFloat(),
                    )
                }
                val xTip = triadEnd(Vec3.UNIT_X)
                val yTip = triadEnd(Vec3.UNIT_Y)
                val zTip = triadEnd(Vec3.UNIT_Z)
                drawLine(primaryColor.copy(alpha = 0.7f), triadOrigin, xTip, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(markColor.copy(alpha = 0.7f), triadOrigin, yTip, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(softInk.copy(alpha = 0.75f), triadOrigin, zTip, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            }

            if (!plan.hasResults) {
                Text(
                    "Enter bend measurements below to preview 3D shape",
                    style = MaterialTheme.typography.labelSmall,
                    color = softInk,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            } else {
                Text(
                    "Drag to orbit",
                    style = MaterialTheme.typography.labelSmall,
                    color = softInk.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // Iso / Top / Side preset chips
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PreviewViewPreset.entries.forEach { preset ->
                    val selected = abs(yawDeg - preset.yawDeg) < 2f && abs(pitchDeg - preset.pitchDeg) < 2f
                    FilterChip(
                        selected = selected,
                        onClick = {
                            yawDeg = preset.yawDeg
                            pitchDeg = preset.pitchDeg
                        },
                        label = {
                            Text(
                                preset.label,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        ),
                        modifier = Modifier.height(26.dp),
                    )
                }
            }
        }
    }
}
