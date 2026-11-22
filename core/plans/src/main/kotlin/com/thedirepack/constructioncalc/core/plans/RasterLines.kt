package com.thedirepack.constructioncalc.core.plans

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Finds the drawn lines on a scanned or photographed sheet using OpenCV's Line Segment Detector
 * and refines each stroke's exact center line, direction and width from local contrast profiles.
 *
 * The caller is responsible for having loaded OpenCV's native library (`OpenCVLoader.initLocal()`
 * on Android, `nu.pattern.OpenCV.loadLocally()` in desktop tests).
 */
object RasterLines {
    /**
     * Extracts a [LineMap] in page points from [image], where one image pixel equals
     * [pointsPerPixel] page points. Only lines at least [minimumLength] points long are kept, so
     * lettering and small symbols do not snap.
     */
    fun find(
        image: GrayImage,
        pointsPerPixel: Double,
        minimumLength: Double = LineMap.DEFAULT_MINIMUM_LENGTH,
    ): LineMap {
        require(pointsPerPixel.isFinite() && pointsPerPixel > 0)
        require(minimumLength.isFinite() && minimumLength >= 0)
        val pageWidth = image.width * pointsPerPixel
        val pageHeight = image.height * pointsPerPixel
        val ink = normalizeInk(image) ?: return LineMap(pageWidth, pageHeight, emptyList(), LineSource.RASTER, minimumLength)
        val rawSegments = detectSegments(ink)
        if (rawSegments.isEmpty()) {
            return LineMap(pageWidth, pageHeight, emptyList(), LineSource.RASTER, minimumLength)
        }
        val minLengthPx = minimumLength / pointsPerPixel
        val minCandidatePx = max(6.0, minLengthPx * .35)
        val maxStrokePx = max(20.0, MAX_STROKE_POINTS / pointsPerPixel)
        val refined = ArrayList<PlanLine>(rawSegments.size)
        for (i in rawSegments.indices step 4) {
            // OpenCV LSD reports coordinates where pixel (i, j) is centered at (i, j); InkImage
            // centers pixel (i, j) at (i + 0.5, j + 0.5).
            val x1 = rawSegments[i].toDouble() + .5
            val y1 = rawSegments[i + 1].toDouble() + .5
            val x2 = rawSegments[i + 2].toDouble() + .5
            val y2 = rawSegments[i + 3].toDouble() + .5
            val len = hypot(x2 - x1, y2 - y1)
            if (len < minCandidatePx) continue
            refineSegment(ink, x1, y1, x2, y2, len, maxStrokePx)?.let { seg ->
                refined += PlanLine(
                    seg.x1 * pointsPerPixel,
                    seg.y1 * pointsPerPixel,
                    seg.x2 * pointsPerPixel,
                    seg.y2 * pointsPerPixel,
                    seg.width * pointsPerPixel,
                )
            }
        }
        // Merge both edges of thick walls and pieces split across crossings, while keeping slight
        // curves on photographed sheets piecewise-linear.
        val merged = mergeLines(
            lines = refined,
            minimumLength = minimumLength,
            angleTolerance = 1.2 * PI / 180,
            offsetTolerance = max(.45 * pointsPerPixel, .2),
            gap = max(2.5 * pointsPerPixel, .75),
        )
        return LineMap(pageWidth, pageHeight, merged, LineSource.RASTER, minimumLength)
    }

    /**
     * Removes uneven lighting and normalizes ink contrast into 0 (paper) .. 255 (full ink).
     * Handles both dark lines on light paper and light lines on dark sheets. Returns null on
     * blank/low-contrast images.
     */
    internal fun normalizeInk(image: GrayImage): InkImage? {
        val w = image.width
        val h = image.height
        val histogram = IntArray(256)
        for (b in image.pixels) histogram[b.toInt() and 0xff]++
        val total = w.toLong() * h
        fun level(count: Long): Int {
            var seen = 0L
            for (v in 0..255) {
                seen += histogram[v]
                if (seen > count) return v
            }
            return 255
        }
        val few = max(16L, total / 500L)
        val paper = level(total / 2L)
        val darkest = level(few - 1)
        val lightest = level(total - few)
        val dark = paper - darkest
        val light = lightest - paper
        if (max(dark, light) < MIN_CONTRAST) return null
        val darkInk = dark >= light * .8

        val src = Mat(h, w, CvType.CV_8UC1)
        val small = Mat()
        val bgSmall = Mat()
        val bg = Mat()
        val diff = Mat()
        var kernel: Mat? = null
        try {
            src.put(0, 0, image.pixels)
            val factor = when {
                min(w, h) >= 512 -> 4
                min(w, h) >= 128 -> 2
                else -> 1
            }
            val sw = max(1, w / factor)
            val sh = max(1, h / factor)
            if (factor > 1) {
                Imgproc.resize(src, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                src.copyTo(small)
            }
            val morphSize = (if (factor >= 4) 13 else if (factor == 2) 25 else 35).toDouble()
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(morphSize, morphSize))
            val op = if (darkInk) Imgproc.MORPH_CLOSE else Imgproc.MORPH_OPEN
            Imgproc.morphologyEx(small, bgSmall, op, kernel)
            val blurSize = (if (factor >= 4) 15 else 21).toDouble()
            Imgproc.GaussianBlur(bgSmall, bgSmall, Size(blurSize, blurSize), 0.0)
            if (factor > 1) {
                Imgproc.resize(bgSmall, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            } else {
                bgSmall.copyTo(bg)
            }
            if (darkInk) Core.subtract(bg, src, diff) else Core.subtract(src, bg, diff)
            val raw = ByteArray(w * h)
            diff.get(0, 0, raw)
            val diffHist = IntArray(256)
            for (b in raw) diffHist[b.toInt() and 0xff]++
            var seen = 0L
            var peakDiff = 255
            val target = total - few
            for (v in 0..255) {
                seen += diffHist[v]
                if (seen > target) {
                    peakDiff = v
                    break
                }
            }
            if (peakDiff < MIN_CONTRAST) return null
            val scale = 255.0 / max(peakDiff, MIN_CONTRAST)
            val lut = ByteArray(256) { v -> (v * scale).toInt().coerceIn(0, 255).toByte() }
            val normalized = ByteArray(raw.size) { idx -> lut[raw[idx].toInt() and 0xff] }
            return InkImage(w, h, normalized)
        } finally {
            kernel?.release()
            diff.release()
            bg.release()
            bgSmall.release()
            small.release()
            src.release()
        }
    }

    /** Runs OpenCV's Line Segment Detector on the normalized ink raster; returns packed [x1,y1,x2,y2,...]. */
    private fun detectSegments(ink: InkImage): FloatArray {
        val mat = Mat(ink.height, ink.width, CvType.CV_8UC1)
        val lines = Mat()
        try {
            mat.put(0, 0, ink.rawBytes())
            val lsd = Imgproc.createLineSegmentDetector(Imgproc.LSD_REFINE_STD)
            lsd.detect(mat, lines)
            if (lines.empty()) return FloatArray(0)
            val count = lines.rows()
            val out = FloatArray(count * 4)
            lines.get(0, 0, out)
            return out
        } finally {
            lines.release()
            mat.release()
        }
    }

    /**
     * Refines one LSD edge segment into the underlying stroke's exact sub-pixel centerline and
     * width. Returns null when the segment is the boundary of a solid fill or lacks ink support.
     */
    private fun refineSegment(
        ink: InkImage,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        length: Double,
        maxStrokePx: Double,
    ): PlanLine? {
        var angle = atan2(y2 - y1, x2 - x1)
        if (angle < 0) angle += PI
        if (angle >= PI) angle -= PI
        val ux = cos(angle)
        val uy = sin(angle)
        val nx = -uy
        val ny = ux
        val mx = (x1 + x2) / 2
        val my = (y1 + y2) / 2

        // Initial cross-section averaged along the middle of the segment to locate the stroke peak
        // adjacent to this LSD edge.
        val reach = maxStrokePx * .75 + 4.0
        val size = (2 * reach / PROFILE_STEP).toInt() + 1
        val halfAlong = min(length * .4, 24.0).coerceAtLeast(2.0)
        val profile = crossSection(ink, angle, -reach, size, halfAlong, mx, my)
        val initial = strokeNearZero(profile, reach, maxStrokePx) ?: return null
        val t0 = initial.first
        val width0 = initial.second

        // Sample local cross-sections at stations along the segment to refine position, direction
        // and width while rejecting crossings and junctions.
        val stationCount = (length / 10.0).toInt().coerceIn(3, 15)
        val localReach = max(4.0, width0 * 1.2 + 3.5)
        val localSize = (2 * localReach / PROFILE_STEP).toInt() + 1
        val peakSearch = ((width0 / 2 + 2.5) / PROFILE_STEP).toInt()
        val samples = ArrayList<Triple<Double, Double, Double>>(stationCount)
        val span = length * .84
        for (k in 0 until stationCount) {
            val frac = if (stationCount == 1) 0.0 else k.toDouble() / (stationCount - 1) - .5
            val s = frac * span
            val sx = mx + s * ux
            val sy = my + s * uy
            val local = crossSection(ink, angle, t0 - localReach, localSize, 2.0, sx, sy)
            val peak = nearestPeak(local, localSize / 2, peakSearch) ?: continue
            if (local[peak] < MIN_STATION_PEAK) continue
            val found = edges(local, peak) ?: continue
            val w = found.second
            if (w > maxStrokePx || abs(w - width0) > max(1.5, width0 * .55)) continue
            samples += Triple(s, t0 - localReach + found.first, w)
        }
        if (samples.size < 2 || samples.size * 3 < stationCount) return null

        // Robust linear fit of center offset vs position along segment.
        val halfLen = length / 2
        val endMargin = length * .08 + if (stationCount > 1) span / (2 * (stationCount - 1)) else 0.0
        val offset: Double
        val slope: Double
        val width: Double
        val sStart: Double
        val sEnd: Double
        if (samples.size >= 3) {
            var fit = fitLine(samples)
            val tol = max(.75, width0 * .35)
            val inliers = samples.filter { (s, c, _) -> abs(c - (fit.first + fit.second * s)) <= tol }
            if (inliers.size < 2) return null
            fit = fitLine(inliers)
            val widths = inliers.map { it.third }.sorted()
            offset = fit.first
            slope = fit.second
            width = widths[widths.size / 2]
            sStart = max(-halfLen, inliers.minOf { it.first } - endMargin)
            sEnd = min(halfLen, inliers.maxOf { it.first } + endMargin)
        } else {
            offset = samples.sumOf { it.second } / samples.size
            slope = 0.0
            width = samples.sumOf { it.third } / samples.size
            sStart = -halfLen
            sEnd = halfLen
        }
        if (abs(slope) > .25 || width > maxStrokePx || sEnd <= sStart) return null

        val refinedAngle = angle + atan(slope)
        val rux = cos(refinedAngle)
        val ruy = sin(refinedAngle)
        val cx = mx + offset * nx
        val cy = my + offset * ny
        val cosTurn = cos(atan(slope))
        val pStart = sStart / cosTurn
        val pEnd = sEnd / cosTurn
        return PlanLine(
            cx + pStart * rux,
            cy + pStart * ruy,
            cx + pEnd * rux,
            cy + pEnd * ruy,
            width,
        )
    }

    /**
     * Finds the stroke peak whose ink covers or borders t = 0 (where the LSD edge lies). Returns
     * (center offset in px, stroke width in px), or null if t = 0 is on a solid fill or noise.
     */
    private fun strokeNearZero(profile: DoubleArray, reach: Double, maxStrokePx: Double): Pair<Double, Double>? {
        var bestCenter = 0.0
        var bestWidth = 0.0
        var bestDist = Double.POSITIVE_INFINITY
        for (k in 1 until profile.size - 1) {
            if (profile[k] < profile[k - 1] || profile[k] <= profile[k + 1] || profile[k] < MIN_STROKE_PEAK) continue
            // Handle flat-topped thick strokes by advancing to the middle of the plateau.
            var start = k
            while (start > 0 && profile[start - 1] == profile[k]) start--
            val peak = (start + k) / 2
            val found = edges(profile, peak) ?: continue
            val center = -reach + found.first
            val width = found.second
            if (width <= 0 || width > maxStrokePx) continue
            val left = center - width / 2
            val right = center + width / 2
            // Distance from t = 0 to the stroke's ink (0 if inside, else distance to nearer edge).
            val edgeDist = when {
                0.0 in left..right -> min(abs(left), abs(right))
                0.0 < left -> left
                else -> -right
            }
            if (edgeDist <= EDGE_MATCH_TOLERANCE && edgeDist < bestDist) {
                bestDist = edgeDist
                bestCenter = center
                bestWidth = width
            }
        }
        return if (bestDist.isFinite()) bestCenter to bestWidth else null
    }

    private fun fitLine(samples: List<Triple<Double, Double, Double>>): Pair<Double, Double> {
        val n = samples.size
        val ms = samples.sumOf { it.first } / n
        val mc = samples.sumOf { it.second } / n
        var ss = 0.0
        var sc = 0.0
        for ((s, c, _) in samples) {
            ss += (s - ms) * (s - ms)
            sc += (s - ms) * (c - mc)
        }
        val slope = if (ss > 1e-9) sc / ss else 0.0
        return (mc - slope * ms) to slope
    }

    private const val MIN_CONTRAST = 28
    private const val MIN_STROKE_PEAK = 0.25
    private const val MIN_STATION_PEAK = 0.20
    private const val EDGE_MATCH_TOLERANCE = 2.5
    private const val MAX_STROKE_POINTS = 10.0
}
