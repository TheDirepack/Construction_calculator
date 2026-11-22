package com.thedirepack.constructioncalc.core.plans

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A grayscale raster, luminance 0 (black) to 255 (white), row-major. Pixel (i, j) covers the
 * square [i, i + 1) × [j, j + 1), so its center is (i + 0.5, j + 0.5).
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff

    companion object {
        /** From packed ARGB pixels (Android `Bitmap.getPixels`); transparency counts as white paper. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            require(argb.size.toLong() == width.toLong() * height)
            val out = ByteArray(argb.size)
            for (i in argb.indices) {
                val color = argb[i]
                val alpha = color ushr 24
                val luminance = ((color shr 16 and 255) * 299 + (color shr 8 and 255) * 587 + (color and 255) * 114) / 1000
                out[i] = ((luminance * alpha + 255 * (255 - alpha)) / 255).toByte()
            }
            return GrayImage(width, height, out)
        }
    }
}

/** What a snap landed on: along a line, where two lines meet, or a line's free end. */
enum class SnapKind { LINE, CORNER, END }

/**
 * Where a tap snapped, in image pixels. [angle] is the line's direction in radians (for a corner,
 * the first line's), [width] the drawn stroke width in pixels and [strength] its contrast, 0 to 1.
 */
data class LineSnap(
    val x: Double,
    val y: Double,
    val kind: SnapKind,
    val angle: Double,
    val width: Double,
    val strength: Double,
)

/**
 * Snaps a tap to the center of the nearest drawn line, by contrast alone: it works on scans and
 * photos as well as on rendered CAD sheets.
 *
 * Around the tap it compares cross-section profiles in many directions, each averaged along the
 * direction so text and specks fade and straight strokes stand out. The best one gives the line;
 * its two edges are found at half contrast and the center is halfway between them. The center is
 * then measured again at several places along the line and fitted, which corrects the direction
 * and follows the slight curve of a scanned or photographed sheet. Where two lines meet close to
 * the tap, it snaps to their intersection.
 */
object LineSnapper {
    /**
     * Returns null when nothing line-like lies within [radius] pixels of ([x], [y]), for example on
     * blank paper or inside a solid fill.
     */
    fun snap(image: GrayImage, x: Double, y: Double, radius: Double): LineSnap? {
        require(x.isFinite() && y.isFinite() && radius.isFinite() && radius in 2.0..256.0)
        val along = (radius * .4).coerceIn(4.0, 16.0)
        val field = Darkness.around(image, x, y, radius * REACH + along + 3) ?: return null
        val scan = Scan(field, x, y, radius, along)
        val first = scan.best(exclude = null) ?: return null
        val line = scan.refine(first) ?: return null
        val second = scan.best(exclude = line.angle)?.let { scan.refine(it, exclude = line.angle) }
        if (second != null) {
            val corner = line.intersect(second)
            if (corner != null &&
                hypot(corner.first - x, corner.second - y) <= radius * .6 &&
                field.at(corner.first, corner.second) >= .4 &&
                second.strength >= line.strength * .5 &&
                second.leavesFrom(corner, line.width, angleBetween(line.angle, second.angle), along, field) &&
                line.leavesFrom(corner, second.width, angleBetween(line.angle, second.angle), along, field)
            ) {
                return LineSnap(corner.first, corner.second, SnapKind.CORNER, line.angle, line.width, min(line.strength, second.strength))
            }
        }
        val foot = line.foot(x, y)
        return LineSnap(foot.first, foot.second, SnapKind.LINE, line.angle, line.width, line.strength)
    }
}

/** Directions tried, 3° apart. */
private const val ANGLES = 60

/** Profile sample spacing, pixels. */
private const val STEP = 0.5

/** Least paper-to-ink difference (of 255) worth snapping to. */
private const val MIN_CONTRAST = 32
private const val MIN_PEAK = 0.3
private const val MIN_PROMINENCE = 0.2
private const val MIN_CORNER_ANGLE = 35.0 * PI / 180

/** How far past the tap radius the cross-sections look, as a multiple of it. */
private const val REACH = 1.5

/** Ink darkness 0 (paper) to 1 (ink) at a point in image coordinates. */
interface InkField {
    fun at(px: Double, py: Double): Double
}

/**
 * Darkness over a whole image, already normalized (0 paper, 255 full ink), sampled bilinearly.
 * Pixel (i, j) is centered at (i + 0.5, j + 0.5).
 */
class InkImage(val width: Int, val height: Int, private val ink: ByteArray) : InkField {
    init {
        require(width > 0 && height > 0 && ink.size.toLong() == width.toLong() * height)
    }

    internal fun rawBytes(): ByteArray = ink

    override fun at(px: Double, py: Double): Double {
        val u = px - .5
        val v = py - .5
        val i = floor(u).toInt()
        val j = floor(v).toInt()
        val fx = u - i
        val fy = v - j
        val top = get(i, j) * (1 - fx) + get(i + 1, j) * fx
        val bottom = get(i, j + 1) * (1 - fx) + get(i + 1, j + 1) * fx
        return (top * (1 - fy) + bottom * fy) / 255
    }

    private fun get(i: Int, j: Int): Double =
        if (i < 0 || j < 0 || i >= width || j >= height) 0.0 else (ink[j * width + i].toInt() and 0xff).toDouble()
}

/** Ink darkness 0 to 1 in a window of the image, with bilinear sampling in image coordinates. */
private class Darkness(val x0: Int, val y0: Int, val w: Int, val h: Int, val values: FloatArray) : InkField {
    override fun at(px: Double, py: Double): Double {
        val u = px - .5 - x0
        val v = py - .5 - y0
        val i = floor(u).toInt()
        val j = floor(v).toInt()
        val fx = u - i
        val fy = v - j
        val top = get(i, j) * (1 - fx) + get(i + 1, j) * fx
        val bottom = get(i, j + 1) * (1 - fx) + get(i + 1, j + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    private fun get(i: Int, j: Int): Double = if (i < 0 || j < 0 || i >= w || j >= h) 0.0 else values[j * w + i].toDouble()

    companion object {
        /**
         * Paper is the window's median; ink is whichever extreme lies farther from it, so light
         * lines on a dark sheet work too. Null when the window has too little contrast.
         */
        fun around(image: GrayImage, x: Double, y: Double, reach: Double): Darkness? {
            val left = floor(x - reach).toInt().coerceIn(0, image.width - 1)
            val top = floor(y - reach).toInt().coerceIn(0, image.height - 1)
            val right = floor(x + reach).toInt().coerceIn(0, image.width - 1)
            val bottom = floor(y + reach).toInt().coerceIn(0, image.height - 1)
            val w = right - left + 1
            val h = bottom - top + 1
            val histogram = IntArray(256)
            for (j in top..bottom) for (i in left..right) histogram[image[i, j]]++
            val total = w * h
            fun level(count: Long): Int {
                var seen = 0L
                for (level in 0..255) {
                    seen += histogram[level]
                    if (seen > count) return level
                }
                return 255
            }
            // The ink level is the darkest few pixels, not a percentile: a thin line can cover
            // under 1% of the window, and a percentile would call its half-covered edges solid ink.
            val few = max(4L, total / 500L)
            val paper = level(total / 2L)
            val darkest = level(few - 1)
            val lightest = level(total - few)
            val dark = paper - darkest
            val light = lightest - paper
            if (max(dark, light) < MIN_CONTRAST) return null
            val darkInk = dark >= light * .8
            val span = (if (darkInk) dark else light).toFloat()
            val values = FloatArray(total)
            for (j in 0 until h) for (i in 0 until w) {
                val level = image[left + i, top + j]
                val ink = if (darkInk) paper - level else level - paper
                values[j * w + i] = (ink / span).coerceIn(0f, 1f)
            }
            return Darkness(left, top, w, h, values)
        }

    }
}

/** A candidate: a direction and a profile peak at offset [t] from the tap along the normal. */
private data class Candidate(val angle: Double, val t: Double, val score: Double, val peak: Double)

/** A fitted center line: through ([px], [py]) in direction [angle]. */
private class FittedLine(val px: Double, val py: Double, val angle: Double, val width: Double, val strength: Double) {
    private val ux = cos(angle)
    private val uy = sin(angle)

    fun foot(x: Double, y: Double): Pair<Double, Double> {
        val along = (x - px) * ux + (y - py) * uy
        return Pair(px + along * ux, py + along * uy)
    }

    /**
     * Whether this line really continues away from [point] (where it meets a line [crossing] pixels
     * wide) on at least one side. A single line crossed at a shallow angle looks like a second line
     * near the crossing, but it has no ink beyond it.
     */
    fun leavesFrom(point: Pair<Double, Double>, crossing: Double, angleTo: Double, length: Double, field: Darkness): Boolean {
        val start = crossing / 2 / max(sin(angleTo), .2) + width / 2 + 1.5
        return listOf(1.0, -1.0).any { side ->
            var sum = 0.0
            var count = 0
            var d = start
            while (d <= start + length) {
                sum += field.at(point.first + side * d * ux, point.second + side * d * uy)
                count++
                d += 1.0
            }
            count > 0 && sum / count >= .6
        }
    }

    fun intersect(other: FittedLine): Pair<Double, Double>? {
        val cross = ux * other.uy - uy * other.ux
        if (abs(cross) < sin(20.0 * PI / 180)) return null
        val dx = other.px - px
        val dy = other.py - py
        val a = (dx * other.uy - dy * other.ux) / cross
        return Pair(px + a * ux, py + a * uy)
    }
}

private class Scan(val field: InkField, val x: Double, val y: Double, val radius: Double, val along: Double) {
    /** Profiles reach past the radius so a wide stroke whose edge is within reach is seen whole. */
    private val reach = radius * REACH
    private val count = (2 * reach / STEP).toInt() + 1
    private val profiles = Array(ANGLES) { index -> profile(index * PI / ANGLES, -reach, count, along) }

    fun profile(angle: Double, t0: Double, size: Int, half: Double, cx: Double = x, cy: Double = y): DoubleArray =
        crossSection(field, angle, t0, size, half, cx, cy)

    /** The strongest nearby line, or with [exclude] the strongest crossing it at a clear angle. */
    fun best(exclude: Double?): Candidate? {
        var best: Candidate? = null
        for (index in 0 until ANGLES) {
            val angle = index * PI / ANGLES
            if (exclude != null && angleBetween(angle, exclude) < MIN_CORNER_ANGLE) continue
            val values = profiles[index]
            for (k in 1 until values.size - 1) {
                if (values[k] < values[k - 1] || values[k] <= values[k + 1] || values[k] < MIN_PEAK) continue
                val base = max(minWalk(values, k, -1), minWalk(values, k, 1))
                val prominence = values[k] - base
                if (prominence < MIN_PROMINENCE) continue
                // The stroke's extent at half height: a flat-topped wall's peak sample can sit at
                // its far side, so distance is measured to the nearer edge.
                val half = (values[k] + base) / 2
                var a = k
                while (a > 0 && values[a - 1] >= half) a--
                var b = k
                while (b < values.size - 1 && values[b + 1] >= half) b++
                val near = -reach + a * STEP
                val far = -reach + b * STEP
                val gap = if (near <= 0 && far >= 0) 0.0 else min(abs(near), abs(far))
                if (gap > radius) continue
                // The nearer line wins over a darker one farther away: people tap next to the line
                // they mean.
                val distance = gap / (radius * .5)
                val score = (values[k] + prominence) / 2 * exp(-distance * distance)
                if (best == null || score > best.score) best = Candidate(angle, (near + far) / 2, score, values[k])
            }
        }
        return best
    }

    /**
     * Finds the line's true direction, its edges and its center along the line. The direction is
     * the one with the narrowest cross-section through the stroke: any other direction cuts it
     * at a slant, which is wider, and blurs its near edge toward the tap.
     */
    fun refine(candidate: Candidate, exclude: Double? = null): FittedLine? {
        val px = x - candidate.t * sin(candidate.angle)
        val py = y + candidate.t * cos(candidate.angle)
        val size = (2 * reach / STEP).toInt() + 1
        fun crossSection(angle: Double): Pair<Double, Double>? {
            val values = profile(angle, -reach, size, along, px, py)
            val peak = nearestPeak(values, size / 2, (3 / STEP).toInt()) ?: return null
            if (values[peak] < MIN_PEAK * .6) return null
            val found = edges(values, peak) ?: return null
            return found.second to values[peak]
        }
        // Along the true direction the averaged cross-section keeps its full strength; on a faint,
        // noisy sheet a slanted one can look narrow by chance, so only strong ones compete.
        fun choose(trials: List<Double>): Pair<Double, Double>? {
            val sections = trials.filter { exclude == null || angleBetween(it, exclude) >= MIN_CORNER_ANGLE }
                .mapNotNull { trial -> crossSection(trial)?.let { Triple(trial, it.first, it.second) } }
            val strongest = sections.maxOfOrNull { it.third } ?: return null
            return sections.filter { it.third >= strongest * .8 }.minByOrNull { it.second }?.let { it.first to it.third }
        }
        val (coarse, _) = choose(List(ANGLES) { it * PI / ANGLES }) ?: return null
        var (angle, strength) = choose(List(13) { coarse + (it - 6) * .5 * PI / 180 }) ?: return null
        // Back to the tap: the cross-section through it, in the chosen direction.
        val guess = candidate.t * cos(angle - candidate.angle)
        val span = (reach / STEP).toInt()
        val wide = profile(angle, guess - span * STEP, 2 * span + 1, along)
        val index = nearestPeak(wide, span, (4 / STEP).toInt()) ?: return null
        val (center, width) = edges(wide, index) ?: return null
        var t = guess - span * STEP + center
        // Re-measure the center at stations along the line and fit its offset and slope, which
        // also follows a slightly curved sheet. Repeat until the direction settles.
        repeat(4) {
            val (offset, slope) = stations(angle, t, width) ?: return@repeat
            val turn = atan(slope)
            angle += turn
            t = offset * cos(turn)
            if (abs(slope) < .003) return FittedLine(x - t * sin(angle), y + t * cos(angle), normalize(angle), width, strength.coerceIn(0.0, 1.0))
        }
        return FittedLine(x - t * sin(angle), y + t * cos(angle), normalize(angle), width, strength.coerceIn(0.0, 1.0))
    }

    /** Fitted (offset at the tap, slope) of the centers measured along the line, or null. */
    private fun stations(angle: Double, t: Double, width: Double): Pair<Double, Double>? {
        val step = max(2.0, along / 4)
        val stations = floor(along / step).toInt()
        val reach = max(4.0, width * 1.5 + 3)
        val size = (2 * reach / STEP).toInt() + 1
        val samples = mutableListOf<Pair<Double, Double>>()
        for (k in -stations..stations) {
            val s = k * step
            val local = profile(angle, t - reach, size, 1.5, x + s * cos(angle), y + s * sin(angle))
            val peak = nearestPeak(local, size / 2, ((width / 2 + 2) / STEP).toInt()) ?: continue
            if (local[peak] < MIN_PEAK * .6) continue
            val found = edges(local, peak) ?: continue
            // Near a junction the cross-section runs along the other line: not this line's width.
            if (abs(found.second - width) > max(1.0, width * .5)) continue
            samples += s to (t - reach + found.first)
        }
        if (samples.size < 3) return null
        var fit = fitLine(samples)
        val tolerance = max(.75, width * .35)
        val kept = samples.filter { (s, c) -> abs(c - (fit.first + fit.second * s)) <= tolerance }
        if (kept.size < 3 || kept.size * 2 < samples.size) return null
        fit = fitLine(kept)
        return fit.takeIf { abs(it.second) < .3 }
    }

    private fun minWalk(values: DoubleArray, from: Int, direction: Int): Double {
        var lowest = values[from]
        var k = from + direction
        while (k in values.indices && values[k] <= values[from]) {
            lowest = min(lowest, values[k])
            k += direction
        }
        return lowest
    }

    private fun fitLine(points: List<Pair<Double, Double>>): Pair<Double, Double> {
        val n = points.size
        val ms = points.sumOf { it.first } / n
        val mc = points.sumOf { it.second } / n
        var ss = 0.0
        var sc = 0.0
        for ((s, c) in points) {
            ss += (s - ms) * (s - ms)
            sc += (s - ms) * (c - mc)
        }
        val slope = if (ss > 0) sc / ss else 0.0
        return Pair(mc - slope * ms, slope)
    }
}

private fun normalize(angle: Double): Double {
    var a = angle % PI
    if (a < 0) a += PI
    return a
}


/**
 * Cross-section of [field] through ([cx], [cy]) for a line in direction [angle]: samples at
 * offsets t0, t0 + STEP… along the normal, each averaged ±[half] pixels along the direction.
 */
internal fun crossSection(field: InkField, angle: Double, t0: Double, size: Int, half: Double, cx: Double, cy: Double): DoubleArray {
    val ux = cos(angle)
    val uy = sin(angle)
    val nx = -uy
    val ny = ux
    val reach = floor(half).toInt()
    return DoubleArray(size) { k ->
        val t = t0 + k * STEP
        var sum = 0.0
        for (s in -reach..reach) sum += field.at(cx + t * nx + s * ux, cy + t * ny + s * uy)
        sum / (2 * reach + 1)
    }
}

/** Sample spacing of [crossSection], pixels. */
internal const val PROFILE_STEP = STEP

/**
 * The profile peak closest to [around] within [reach] samples: of two lines a few pixels
 * apart the nearer one, not the darker. Falls back to the highest sample in reach.
 */
internal fun nearestPeak(values: DoubleArray, around: Int, reach: Int): Int? {
    val from = max(0, around - reach)
    val to = min(values.size - 1, around + reach)
    var nearest: Int? = null
    for (k in from..to) {
        val rising = k == 0 || values[k] >= values[k - 1]
        val falling = k == values.size - 1 || values[k] > values[k + 1]
        if (!rising || !falling || values[k] < MIN_PEAK * .5) continue
        // A flat top counts from its middle.
        var start = k
        while (start > 0 && values[start - 1] == values[k]) start--
        val middle = (start + k) / 2
        if (nearest == null || abs(middle - around) < abs(nearest - around)) nearest = middle
    }
    if (nearest != null) return nearest
    var best = from
    for (k in from..to) if (values[k] > values[best]) best = k
    return best.takeIf { values[it] > 0 }
}

/**
 * Edges at half height on each side of the peak; returns (center offset, width) in profile
 * units. Each side's base is the lowest point before the profile climbs into another line, so
 * ripples along the dark middle of a noisy or compressed stroke are not taken for its edges.
 */
internal fun edges(values: DoubleArray, peak: Int): Pair<Double, Double>? {
    val top = peak
    val height = values[top]
    fun base(direction: Int): Double {
        var lowest = height
        var k = top + direction
        while (k in values.indices) {
            lowest = min(lowest, values[k])
            val depth = height - lowest
            if (depth > MIN_PROMINENCE * .5 && values[k] - lowest > .25 * depth) break
            k += direction
        }
        return lowest
    }
    val leftBase = base(-1)
    val rightBase = base(1)
    if (height - max(leftBase, rightBase) < MIN_PROMINENCE * .5) return null
    val leftHalf = (height + leftBase) / 2
    val rightHalf = (height + rightBase) / 2
    var left: Double? = null
    for (k in top downTo 1) {
        if (values[k - 1] < leftHalf) {
            left = (k - 1 + (leftHalf - values[k - 1]) / (values[k] - values[k - 1])) * STEP
            break
        }
    }
    var right: Double? = null
    for (k in top until values.size - 1) {
        if (values[k + 1] < rightHalf) {
            right = (k + (values[k] - rightHalf) / (values[k] - values[k + 1])) * STEP
            break
        }
    }
    if (left == null || right == null) return null
    return Pair((left + right) / 2, right - left)
}

