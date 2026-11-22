package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Coordinates in the rendered page, independent of viewport zoom and pan. */
@Serializable
data class PlanPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite() && abs(x) <= 1e7 && abs(y) <= 1e7)
    }
}

@Serializable
enum class PlanScaleMode { UNIFORM, AXES, AFFINE, DISTORTED }

@Serializable
data class PlanReference(
    val first: PlanPoint,
    val second: PlanPoint,
    val length: StoredQuantity,
    val check: Boolean = false,
    /** Page index when stored on the document-wide calibration layer. */
    val page: Int = 0,
) {
    init {
        require(first != second && page >= 0)
        require(length.dimension == Dimension.LENGTH && length.toQuantity().value.signum > 0)
    }
}

/**
 * Document-wide calibration layer shared across pages. Any number of known dimensions on any page
 * can be added and fitted together; individual pages can also select their own distortion mode
 * (such as [PlanScaleMode.DISTORTED] for a photographed or curled sheet). Override [PlanZone]s
 * live separately and are excluded from this fit.
 */
@Serializable
data class PlanCalibrationLayer(
    /** Exact real inches per rendered page point for a document-wide preset. */
    val preset: StoredQuantity? = null,
    val references: List<PlanReference> = emptyList(),
    val mode: PlanScaleMode = PlanScaleMode.UNIFORM,
    /** Optional per-page mode overrides (e.g. a per-page 3D distortion field). */
    val pageModes: Map<Int, PlanScaleMode> = emptyMap(),
) {
    init {
        require(preset == null || preset.dimension == Dimension.LENGTH && preset.toQuantity().value.signum > 0)
        require(pageModes.keys.all { it >= 0 })
    }

    fun modeFor(page: Int): PlanScaleMode = pageModes[page] ?: mode

    /**
     * Fits calibration for [page]. When [page] uses [PlanScaleMode.DISTORTED] (or has a per-page
     * override mode and enough references on [page]), its local references determine the per-page
     * field, anchored by the overall document scale when sparse. Otherwise all document-wide
     * references are fitted together so calibrating once covers every page.
     */
    fun fit(page: Int): PlanCalibration? {
        val activeMode = modeFor(page)
        val pageAll = references.filter { it.page == page }
        val pageFitting = pageAll.filterNot { it.check }
        val allFitting = references.filterNot { it.check }

        if (allFitting.isEmpty()) {
            val scale = preset?.toQuantity()?.value?.toDouble() ?: return null
            val field = ConstantField(DistanceMetric(scale * scale, 0.0, scale * scale))
            return buildCalibration(field, references)
        }

        val field = when {
            activeMode == PlanScaleMode.DISTORTED -> {
                val base = (if (pageFitting.size >= 2) fitBestConstant(pageFitting) else null)
                    ?: fitBestConstant(allFitting)
                    ?: return null
                if (pageFitting.size >= 2) fitDistortedField(pageFitting, base)
                else ConstantField(base)
            }
            pageModes.containsKey(page) && pageFitting.isNotEmpty() -> {
                fitMetric(pageFitting, activeMode)?.let(::ConstantField)
                    ?: fitMetric(allFitting, activeMode)?.let(::ConstantField)
                    ?: return null
            }
            else -> {
                val metric = fitMetric(allFitting, activeMode) ?: return null
                ConstantField(metric)
            }
        }
        return buildCalibration(field, references)
    }

    /** Residuals for references on [page], paired with their index in [references]. */
    fun residualsForPage(page: Int): Map<Int, Double> {
        val fit = fit(page) ?: return emptyMap()
        val out = LinkedHashMap<Int, Double>()
        references.forEachIndexed { index, ref ->
            if (ref.page == page) fit.residuals.getOrNull(index)?.let { out[index] = it }
        }
        return out
    }
}

@Serializable
data class PlanZone(
    val id: String = newId(),
    val name: String,
    val page: Int,
    val first: PlanPoint,
    val second: PlanPoint,
    /** Exact real inches per rendered page point for a preset. */
    val preset: StoredQuantity? = null,
    val references: List<PlanReference> = emptyList(),
    val mode: PlanScaleMode = PlanScaleMode.UNIFORM,
) {
    init {
        require(page >= 0 && first.x != second.x && first.y != second.y)
        require(preset == null || preset.dimension == Dimension.LENGTH && preset.toQuantity().value.signum > 0)
        require(references.all { contains(it.first) && contains(it.second) })
    }

    fun contains(point: PlanPoint): Boolean =
        point.x in minOf(first.x, second.x)..maxOf(first.x, second.x) &&
            point.y in minOf(first.y, second.y)..maxOf(first.y, second.y)

    fun overlaps(other: PlanZone): Boolean =
        page == other.page &&
            maxOf(minOf(first.x, second.x), minOf(other.first.x, other.second.x)) <
                minOf(maxOf(first.x, second.x), maxOf(other.first.x, other.second.x)) &&
            maxOf(minOf(first.y, second.y), minOf(other.first.y, other.second.y)) <
                minOf(maxOf(first.y, second.y), maxOf(other.first.y, other.second.y))

    fun fit(): PlanCalibration? {
        val fitting = references.filterNot { it.check }
        val field: ScaleField = if (fitting.isEmpty()) {
            val scale = preset?.toQuantity()?.value?.toDouble() ?: return null
            ConstantField(DistanceMetric(scale * scale, 0.0, scale * scale))
        } else if (mode == PlanScaleMode.DISTORTED) {
            val base = fitBestConstant(fitting) ?: return null
            if (fitting.size >= 2) fitDistortedField(fitting, base) else ConstantField(base)
        } else {
            val metric = fitMetric(fitting, mode) ?: return null
            ConstantField(metric)
        }
        return buildCalibration(field, references)
    }
}

private fun buildCalibration(field: ScaleField, references: List<PlanReference>): PlanCalibration? {
    if (!field.valid) return null
    val residuals = references.map {
        field.distance(it.first, it.second) - it.length.toQuantity().value.toDouble()
    }
    if (residuals.any { !it.isFinite() }) return null
    return PlanCalibration(field, residuals)
}

internal interface ScaleField {
    val valid: Boolean
    fun distance(first: PlanPoint, second: PlanPoint): Double
    fun areaScale(x: Double, y: Double): Double
}

/** A fitted constant metric in real square inches per square page point. */
internal data class DistanceMetric(val xx: Double, val xy: Double, val yy: Double) {
    val valid: Boolean
        get() = xx.isFinite() && xy.isFinite() && yy.isFinite() && xx > 0 && yy > 0 &&
            abs(xy / sqrt(xx) / sqrt(yy)) < 1.0 - 1e-10

    fun distance(first: PlanPoint, second: PlanPoint): Double {
        val dx = second.x - first.x
        val dy = second.y - first.y
        val xScale = sqrt(xx)
        val shear = xy / xScale
        return hypot(xScale * dx + shear * dy, sqrt(yy - shear * shear) * dy)
    }
}

private class ConstantField(private val metric: DistanceMetric) : ScaleField {
    override val valid: Boolean get() = metric.valid
    private val areaScale = sqrt(metric.xx) * sqrt(metric.yy - (metric.xy / sqrt(metric.xx)).let { it * it })

    override fun areaScale(x: Double, y: Double): Double = areaScale
    override fun distance(first: PlanPoint, second: PlanPoint): Double = metric.distance(first, second)
}

/**
 * Spatially varying 2D metric over a sheet (e.g., perspective tilt + smooth paper curl on a
 * photographed plan). Integrates local arc length along every measured segment via 5-point
 * Gauss-Legendre quadrature so scale changes smoothly over the length of a measurement.
 */
private class DistortedField(
    private val cx: Double,
    private val cy: Double,
    private val invRadius: Double,
    private val l00: Double,
    private val l01: Double,
    private val l11: Double,
    private val params: DoubleArray,
) : ScaleField {
    override val valid: Boolean
        get() = l00.isFinite() && l01.isFinite() && l11.isFinite() && l00 > 0 && l11 > 0 &&
            params.size == PARAM_COUNT && params.all { it.isFinite() }

    override fun distance(first: PlanPoint, second: PlanPoint): Double =
        segmentLength(first, second, cx, cy, invRadius, l00, l01, l11, params)

    override fun areaScale(x: Double, y: Double): Double {
        val u = ((x - cx) * invRadius).coerceIn(-2.0, 2.0)
        val v = ((y - cy) * invRadius).coerceIn(-2.0, 2.0)
        return l00 * l11 * exp(logAxisScale(u, v, params, 0) + logAxisScale(u, v, params, 6))
    }

    companion object {
        const val PARAM_COUNT = 15

        // 5-point Gauss-Legendre quadrature on [0, 1].
        val GL_NODES = doubleArrayOf(
            0.04691007703066802,
            0.23076534494715845,
            0.5,
            0.7692346550528415,
            0.9530899229693319,
        )
        val GL_WEIGHTS = doubleArrayOf(
            0.11846344252809454,
            0.23931433524968324,
            0.28444444444444444,
            0.23931433524968324,
            0.11846344252809454,
        )

        private fun logAxisScale(u: Double, v: Double, p: DoubleArray, offset: Int): Double =
            (p[offset] + p[offset + 1] * u + p[offset + 2] * v + p[offset + 3] * (u * u) +
                p[offset + 4] * (u * v) + p[offset + 5] * (v * v)).coerceIn(-1.5, 1.5)

        fun speedAt(uRaw: Double, vRaw: Double, bx: Double, by: Double, p: DoubleArray): Double {
            // Clamp far extrapolations gently so distant measurements stay well-behaved.
            val u = uRaw.coerceIn(-2.0, 2.0)
            val v = vRaw.coerceIn(-2.0, 2.0)
            val logSx = logAxisScale(u, v, p, 0)
            val logSy = logAxisScale(u, v, p, 6)
            val shear = (p[12] + p[13] * u + p[14] * v).coerceIn(-1.0, 1.0)
            val vx = exp(logSx) * bx + shear * by
            val vy = exp(logSy) * by
            return hypot(vx, vy)
        }

        fun segmentLength(
            first: PlanPoint,
            second: PlanPoint,
            cx: Double,
            cy: Double,
            invRadius: Double,
            l00: Double,
            l01: Double,
            l11: Double,
            p: DoubleArray,
        ): Double {
            val dx = second.x - first.x
            val dy = second.y - first.y
            val bx = l00 * dx + l01 * dy
            val by = l11 * dy
            var total = 0.0
            for (i in GL_NODES.indices) {
                val t = GL_NODES[i]
                val u = ((first.x + t * dx) - cx) * invRadius
                val v = ((first.y + t * dy) - cy) * invRadius
                total += GL_WEIGHTS[i] * speedAt(u, v, bx, by, p)
            }
            return total
        }
    }
}

class PlanCalibration internal constructor(
    private val field: ScaleField,
    /** Signed estimated-minus-entered length for each reference, in inches. */
    val residuals: List<Double>,
) {
    fun distance(first: PlanPoint, second: PlanPoint): Quantity =
        Quantity.inches(Rational.of(distanceInches(first, second)))

    internal fun distanceInches(first: PlanPoint, second: PlanPoint): Double = field.distance(first, second)

    /** Integrates the local area scale over a simple polygon using signed fan triangles. */
    internal fun area(points: List<PlanPoint>): Quantity? {
        val a = points.first()
        val nodes = DistortedField.GL_NODES
        val weights = DistortedField.GL_WEIGHTS
        var total = 0.0
        for (i in 1 until points.lastIndex) {
            val b = points[i]
            val c = points[i + 1]
            val signedDoubleArea = cross(a, b, c)
            if (signedDoubleArea == 0.0) continue
            if (field is ConstantField) {
                total += signedDoubleArea * 0.5 * field.areaScale(a.x, a.y)
            } else {
                // Map the unit square to the triangle, with Jacobian (1 - u).
                var integral = 0.0
                for (j in nodes.indices) {
                    val u = nodes[j]
                    for (k in nodes.indices) {
                        val v = (1.0 - u) * nodes[k]
                        val x = a.x + u * (b.x - a.x) + v * (c.x - a.x)
                        val y = a.y + u * (b.y - a.y) + v * (c.y - a.y)
                        integral += weights[j] * weights[k] * (1.0 - u) * field.areaScale(x, y)
                    }
                }
                total += signedDoubleArea * integral
            }
        }
        val squareInches = abs(total)
        return if (squareInches.isFinite() && squareInches > 0.0) Quantity(Rational.of(squareInches), Dimension.AREA) else null
    }
}

/** Picks the richest constant metric that the fitting references cleanly determine. */
private fun fitBestConstant(references: List<PlanReference>): DistanceMetric? =
    fitMetric(references, PlanScaleMode.AFFINE)?.takeIf { it.valid }
        ?: fitMetric(references, PlanScaleMode.AXES)?.takeIf { it.valid }
        ?: fitMetric(references, PlanScaleMode.UNIFORM)?.takeIf { it.valid }

/**
 * Fits a smooth, positive-definite spatially varying metric using tiered-ridge Levenberg-Marquardt
 * starting from [base]. Sparse references smoothly stay close to [base]; well-spread references on
 * a photographed or curled sheet recover linear perspective tilt and quadratic bend.
 */
private fun fitDistortedField(references: List<PlanReference>, base: DistanceMetric): ScaleField {
    val l00 = sqrt(base.xx)
    val l01 = base.xy / l00
    val l11Sq = base.yy - l01 * l01
    if (!l11Sq.isFinite() || l11Sq <= 0) return ConstantField(base)
    val l11 = sqrt(l11Sq)

    var sumX = 0.0
    var sumY = 0.0
    for (ref in references) {
        sumX += ref.first.x + ref.second.x
        sumY += ref.first.y + ref.second.y
    }
    val cx = sumX / (2 * references.size)
    val cy = sumY / (2 * references.size)
    var radius = 1.0
    for (ref in references) {
        radius = max(radius, hypot(ref.first.x - cx, ref.first.y - cy))
        radius = max(radius, hypot(ref.second.x - cx, ref.second.y - cy))
    }
    val invRadius = 1.0 / radius
    val targets = DoubleArray(references.size) { references[it].length.toQuantity().value.toDouble() }
    val nParams = DistortedField.PARAM_COUNT
    // Tiered ridge weights: constant offsets free, linear perspective light, quadratic bend moderate.
    val reg = DoubleArray(nParams) { idx ->
        when (idx) {
            0, 6, 12 -> 1e-6
            1, 2, 7, 8, 13, 14 -> 5e-4
            else -> 5e-3
        }
    }
    var params = DoubleArray(nParams)

    fun cost(p: DoubleArray): Double {
        var total = 0.0
        for (i in references.indices) {
            val pred = DistortedField.segmentLength(references[i].first, references[i].second, cx, cy, invRadius, l00, l01, l11, p)
            val r = (pred - targets[i]) / targets[i]
            total += r * r
        }
        for (j in 0 until nParams) total += reg[j] * p[j] * p[j]
        return total
    }

    var currentCost = cost(params)
    var damping = 1e-3
    val jac = Array(references.size) { DoubleArray(nParams) }
    val res = DoubleArray(references.size)
    val eps = 1e-5

    repeat(25) {
        for (i in references.indices) {
            val basePred = DistortedField.segmentLength(references[i].first, references[i].second, cx, cy, invRadius, l00, l01, l11, params)
            res[i] = (basePred - targets[i]) / targets[i]
            for (j in 0 until nParams) {
                val orig = params[j]
                params[j] = orig + eps
                val shifted = DistortedField.segmentLength(references[i].first, references[i].second, cx, cy, invRadius, l00, l01, l11, params)
                params[j] = orig
                jac[i][j] = (shifted - basePred) / (eps * targets[i])
            }
        }
        val system = Array(nParams) { DoubleArray(nParams + 1) }
        for (j in 0 until nParams) {
            system[j][j] = reg[j] + damping
            system[j][nParams] = -reg[j] * params[j]
            for (k in j until nParams) {
                var sum = 0.0
                for (i in references.indices) sum += jac[i][j] * jac[i][k]
                system[j][k] += sum
                if (k != j) system[k][j] += sum
            }
            var rhs = 0.0
            for (i in references.indices) rhs -= jac[i][j] * res[i]
            system[j][nParams] += rhs
        }
        val step = solveLinear(system, nParams)
        if (step == null) {
            damping *= 10.0
            return@repeat
        }
        val candidate = DoubleArray(nParams) { params[it] + step[it] }
        val nextCost = cost(candidate)
        if (nextCost.isFinite() && nextCost < currentCost) {
            val improvement = currentCost - nextCost
            params = candidate
            currentCost = nextCost
            damping = max(1e-7, damping * 0.3)
            if (improvement < 1e-12) return DistortedField(cx, cy, invRadius, l00, l01, l11, params)
        } else {
            damping *= 5.0
            if (damping > 1e6) return DistortedField(cx, cy, invRadius, l00, l01, l11, params)
        }
    }
    return DistortedField(cx, cy, invRadius, l00, l01, l11, params)
}

private fun fitMetric(references: List<PlanReference>, mode: PlanScaleMode): DistanceMetric? {
    val size = when (mode) {
        PlanScaleMode.UNIFORM -> 1
        PlanScaleMode.AXES -> 2
        PlanScaleMode.AFFINE, PlanScaleMode.DISTORTED -> 3
    }
    if (references.size < size) return null
    val system = Array(size) { DoubleArray(size + 1) }
    for (reference in references) {
        val dx = reference.second.x - reference.first.x
        val dy = reference.second.y - reference.first.y
        val length = hypot(dx, dy)
        val x = dx / length
        val y = dy / length
        val row = when (mode) {
            PlanScaleMode.UNIFORM -> doubleArrayOf(1.0)
            PlanScaleMode.AXES -> doubleArrayOf(x * x, y * y)
            PlanScaleMode.AFFINE, PlanScaleMode.DISTORTED -> doubleArrayOf(x * x, 2 * x * y, y * y)
        }
        val scale = reference.length.toQuantity().value.toDouble() / length
        if (!scale.isFinite() || scale <= 0 || !((scale * scale).isFinite())) return null
        for (i in row.indices) {
            for (j in row.indices) system[i][j] += row[i] * row[j]
            system[i][size] += row[i] * scale * scale
        }
    }
    val solved = solveLinear(system, size) ?: return null
    return when (mode) {
        PlanScaleMode.UNIFORM -> DistanceMetric(solved[0], 0.0, solved[0])
        PlanScaleMode.AXES -> DistanceMetric(solved[0], 0.0, solved[1])
        PlanScaleMode.AFFINE, PlanScaleMode.DISTORTED -> DistanceMetric(solved[0], solved[1], solved[2])
    }
}

private fun solveLinear(system: Array<DoubleArray>, size: Int): DoubleArray? {
    for (column in 0 until size) {
        val pivot = (column until size).maxBy { abs(system[it][column]) }
        if (abs(system[pivot][column]) < 1e-10) return null
        val current = system[column]
        system[column] = system[pivot]
        system[pivot] = current
        val divisor = system[column][column]
        for (j in column..size) system[column][j] /= divisor
        for (i in 0 until size) {
            if (i == column) continue
            val factor = system[i][column]
            for (j in column..size) system[i][j] -= factor * system[column][j]
        }
    }
    return DoubleArray(size) { system[it][size] }
}

@Serializable
enum class PlanMarkKind {
    INK, HIGHLIGHT, ARROW, LINE, RECTANGLE, TEXT, SYMBOL, CALLOUT,
    DIMENSION, CLOUD, CLOUD_CALLOUT, PHOTO, DETAIL_LINK,
}

@Serializable
enum class PlanDashStyle { SOLID, DASHED, DOTTED }

@Serializable
enum class PlanArrowStyle { NONE, END, BOTH, FILLED_END, FILLED_BOTH }

@Serializable
data class PlanMark(
    val id: String = newId(),
    val page: Int,
    val kind: PlanMarkKind,
    val points: List<PlanPoint>,
    val text: String = "",
    val svg: String? = null,
    val pressures: List<Float> = emptyList(),
    val width: Double = 2.0,
    val color: Long? = null,
    val circuitTag: String = "",
    val opacity: Float = 1f,
    val dash: PlanDashStyle = PlanDashStyle.SOLID,
    val arrowStyle: PlanArrowStyle? = null,
    val fillColor: Long? = null,
    val fillOpacity: Float = 0f,
    val areaTag: String = "",
    val photoSha256: String? = null,
    val targetPage: Int? = null,
    val targetDrawingId: String? = null,
) {
    init {
        require(page >= 0 && points.size in 1..20_000 && text.length <= 2_000)
        require(circuitTag.length <= 80)
        require(areaTag.length <= 80)
        require(svg == null || svg.length <= 256 * 1024)
        require(pressures.isEmpty() || pressures.size == points.size && pressures.all { it.isFinite() && it >= 0f })
        require(width.isFinite() && width > 0 && width <= 1_000)
        require(color == null || color in 0..0xffff_ffffL)
        require(opacity.isFinite() && opacity in 0.05f..1f)
        require(fillColor == null || fillColor in 0..0xffff_ffffL)
        require(fillOpacity.isFinite() && fillOpacity in 0f..1f)
        require(photoSha256 == null || photoSha256.length == 64 && photoSha256.all { it in '0'..'9' || it in 'a'..'f' })
        require(targetPage == null || targetPage >= 0)
        require(targetDrawingId == null || targetDrawingId.isNotBlank() && targetDrawingId.length <= 120)
    }
}

@Serializable
data class PlanMeasurement(
    val id: String = newId(),
    val page: Int,
    /** Override zone id, or null when measured against the document-wide calibration layer. */
    val zoneId: String? = null,
    val first: PlanPoint,
    val second: PlanPoint,
    /** Optional name shown with the length ("Feeder to RTU-1", "Corridor"). */
    val label: String = "",
) {
    init {
        require(page >= 0 && first != second && label.length <= 120)
    }
}

/**
 * A route traced on a sheet and sent to a project run, kept so the run can link back to it
 * ("View on plan") and so a recalibration can show how far the plan length has moved from what
 * the run holds. [points] are in page coordinates; legs are measured with the drawing's current
 * calibration whenever they are shown.
 */
@Serializable
data class PlanRunRoute(
    val id: String = newId(),
    val runId: String,
    val page: Int,
    val points: List<PlanPoint>,
    /** The plan's horizontal length in inches when the route was sent, before vertical rises. */
    val sentInches: Double = 0.0,
    val circuitTag: String = "",
) {
    init {
        require(circuitTag.length <= 80)
        require(page >= 0 && points.size in 2..RunPath.MAX_SEGMENTS && runId.isNotBlank())
        require(sentInches.isFinite() && sentInches >= 0)
    }
}

@Serializable
data class PlanDrawing(
    val id: String = newId(),
    val name: String,
    val sha256: String,
    /** Document-wide calibration layer (shared across pages, with optional per-page distortion). */
    val calibration: PlanCalibrationLayer = PlanCalibrationLayer(),
    /** Regions with a different scale from the rest of the document (details, enlarged plans). */
    val zones: List<PlanZone> = emptyList(),
    val measurements: List<PlanMeasurement> = emptyList(),
    val marks: List<PlanMark> = emptyList(),
    /** Routes sent to project runs, one per run (the latest send replaces the earlier one). */
    val routes: List<PlanRunRoute> = emptyList(),
    val paths: List<PlanPath> = emptyList(),
    val sheetRevisions: Map<Int, SheetRevision> = emptyMap(),
) {
    init {
        require(name.isNotBlank() && name.length <= 240)
        require(sheetRevisions.keys.all { it >= 0 })
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /**
     * Resolves the override zone that contains both [first] and [second] on [page], or null if
     * both points lie outside all override zones. Throws nothing; use [crossesZoneBoundary] to
     * tell "outside every zone" from "straddles a zone boundary".
     */
    fun zoneContaining(page: Int, first: PlanPoint, second: PlanPoint): PlanZone? =
        zones.singleOrNull { it.page == page && it.contains(first) && it.contains(second) }

    fun crossesZoneBoundary(page: Int, first: PlanPoint, second: PlanPoint): Boolean {
        val a = zones.firstOrNull { it.page == page && it.contains(first) }
        val b = zones.firstOrNull { it.page == page && it.contains(second) }
        return a != b
    }

    /** Fits the calibration governing a span from [first] to [second] on [page]. */
    fun calibrationFor(page: Int, first: PlanPoint, second: PlanPoint): PlanCalibration? {
        if (crossesZoneBoundary(page, first, second)) return null
        val zone = zoneContaining(page, first, second)
        return if (zone != null) zone.fit() else calibration.fit(page)
    }

    fun length(measurement: PlanMeasurement): Quantity? {
        val zoneId = measurement.zoneId
        if (zoneId != null) {
            val zone = zones.firstOrNull {
                it.id == zoneId && it.page == measurement.page &&
                    it.contains(measurement.first) && it.contains(measurement.second)
            }
            return zone?.fit()?.distance(measurement.first, measurement.second)
        }
        return calibrationFor(measurement.page, measurement.first, measurement.second)
            ?.distance(measurement.first, measurement.second)
    }

    fun dimensionLength(mark: PlanMark): Quantity? {
        if (mark.kind != PlanMarkKind.DIMENSION || mark.points.size < 2) return null
        val first = mark.points.first()
        val second = mark.points.last()
        if (first == second) return null
        return calibrationFor(mark.page, first, second)?.distance(first, second)
    }

    fun referencedPhotoHashes(): Set<String> = marks.mapNotNullTo(mutableSetOf()) { it.photoSha256 }
}

/** The drawing and route a run was traced from, or null if it wasn't sent from a plan. */
fun Project.planRouteFor(runId: String): Pair<PlanDrawing, PlanRunRoute>? {
    for (drawing in drawings) {
        drawing.routes.firstOrNull { it.runId == runId }?.let { return drawing to it }
    }
    return null
}

/** Records [route] as the plan source of its run, replacing any earlier route for that run. */
fun PlanDrawing.withRunRoute(route: PlanRunRoute): PlanDrawing =
    copy(routes = routes.filterNot { it.runId == route.runId } + route)
