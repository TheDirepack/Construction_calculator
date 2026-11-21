package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Coordinates in the rendered page, independent of viewport zoom and pan. */
@Serializable
data class PlanPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite() && abs(x) <= 1e7 && abs(y) <= 1e7)
    }
}

@Serializable
enum class PlanScaleMode { UNIFORM, AXES, AFFINE }

@Serializable
data class PlanReference(
    val first: PlanPoint,
    val second: PlanPoint,
    val length: StoredQuantity,
    val check: Boolean = false,
) {
    init {
        require(first != second)
        require(length.dimension == Dimension.LENGTH && length.toQuantity().value.signum > 0)
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
        require(references.size <= 100 && references.all { contains(it.first) && contains(it.second) })
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
        val metric = if (fitting.isEmpty()) {
            val scale = preset?.toQuantity()?.value?.toDouble() ?: return null
            DistanceMetric(scale * scale, 0.0, scale * scale)
        } else {
            fitMetric(fitting, mode) ?: return null
        }
        if (!metric.valid) return null
        val residuals = references.map {
            metric.distance(it.first, it.second) - it.length.toQuantity().value.toDouble()
        }
        if (residuals.any { !it.isFinite() }) return null
        return PlanCalibration(metric, residuals)
    }
}

/** A fitted metric in real square inches per square page point. */
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

class PlanCalibration internal constructor(
    private val metric: DistanceMetric,
    /** Signed estimated-minus-entered length for each reference, in inches. */
    val residuals: List<Double>,
) {
    fun distance(first: PlanPoint, second: PlanPoint): Quantity =
        Quantity.inches(Rational.of(metric.distance(first, second)))
}

private fun fitMetric(references: List<PlanReference>, mode: PlanScaleMode): DistanceMetric? {
    val size = when (mode) {
        PlanScaleMode.UNIFORM -> 1
        PlanScaleMode.AXES -> 2
        PlanScaleMode.AFFINE -> 3
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
            PlanScaleMode.AFFINE -> doubleArrayOf(x * x, 2 * x * y, y * y)
        }
        val scale = reference.length.toQuantity().value.toDouble() / length
        if (!scale.isFinite() || scale <= 0 || !((scale * scale).isFinite())) return null
        for (i in row.indices) {
            for (j in row.indices) system[i][j] += row[i] * row[j]
            system[i][size] += row[i] * scale * scale
        }
    }
    for (column in 0 until size) {
        val pivot = (column until size).maxBy { abs(system[it][column]) }
        if (abs(system[pivot][column]) < 1e-8) return null
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
    return when (mode) {
        PlanScaleMode.UNIFORM -> DistanceMetric(system[0][size], 0.0, system[0][size])
        PlanScaleMode.AXES -> DistanceMetric(system[0][size], 0.0, system[1][size])
        PlanScaleMode.AFFINE -> DistanceMetric(system[0][size], system[1][size], system[2][size])
    }
}

@Serializable
enum class PlanMarkKind { INK, HIGHLIGHT, ARROW, LINE, RECTANGLE, TEXT, SYMBOL }

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
) {
    init {
        require(page >= 0 && points.size in 1..20_000 && text.length <= 2_000)
        require(svg == null || svg.length <= 256 * 1024)
        require(pressures.isEmpty() || pressures.size == points.size && pressures.all { it.isFinite() && it >= 0f })
        require(width.isFinite() && width > 0 && width <= 1_000)
        require(color == null || color in 0..0xffff_ffffL)
    }
}

@Serializable
data class PlanMeasurement(
    val id: String = newId(),
    val page: Int,
    val zoneId: String,
    val first: PlanPoint,
    val second: PlanPoint,
) {
    init {
        require(page >= 0 && first != second)
    }
}

@Serializable
data class PlanDrawing(
    val id: String = newId(),
    val name: String,
    val sha256: String,
    val zones: List<PlanZone> = emptyList(),
    val measurements: List<PlanMeasurement> = emptyList(),
    val marks: List<PlanMark> = emptyList(),
) {
    init {
        require(name.isNotBlank() && name.length <= 240)
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    fun length(measurement: PlanMeasurement): Quantity? =
        zones.firstOrNull {
            it.id == measurement.zoneId && it.page == measurement.page &&
                it.contains(measurement.first) && it.contains(measurement.second)
        }?.fit()?.distance(measurement.first, measurement.second)
}
