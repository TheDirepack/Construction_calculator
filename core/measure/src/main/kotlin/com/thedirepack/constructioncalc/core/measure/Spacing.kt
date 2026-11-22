package com.thedirepack.constructioncalc.core.measure

import com.thedirepack.constructioncalc.core.math.Rational

/**
 * Repeating layout mark calculator for studs, conduit hangers, strut supports, and balusters.
 *
 * All cumulative marks are measured from zero using exact [Rational] arithmetic so marks never
 * accumulate floating-point drift across a long run.
 */
object Spacing {

    /** Safety cap on generated marks to avoid excessive allocations for tiny spacings on huge spans. */
    const val MAX_MARKS = 500

    data class Layout(
        val totalSpan: Quantity,
        val usableSpan: Quantity,
        val spacing: Quantity,
        val startOffset: Quantity,
        val endOffset: Quantity,
        /** Cumulative layout marks measured from zero (exact [Quantity] lengths). */
        val marks: List<Quantity>,
        /** Distance left between the last regular mark and `totalSpan - endOffset`. */
        val remainder: Quantity,
        /** True if the result was capped at [MAX_MARKS]. */
        val truncated: Boolean = false,
    ) {
        val markCount: Int get() = marks.size
        val spaceCount: Int get() = (marks.size - 1).coerceAtLeast(0)
    }

    /**
     * Generates cumulative marks starting at [startOffset], stepping by [onCenter], up to
     * `totalSpan - endOffset`. If [includeEnd] is true and the last mark falls short of the usable
     * end, a final mark at `totalSpan - endOffset` is appended.
     *
     * Returns null if inputs are not [Dimension.LENGTH], [totalSpan] or [onCenter] is non-positive,
     * offsets are negative, or `startOffset + endOffset > totalSpan`.
     */
    fun bySpacing(
        totalSpan: Quantity,
        onCenter: Quantity,
        startOffset: Quantity = ZERO_LENGTH,
        endOffset: Quantity = ZERO_LENGTH,
        includeEnd: Boolean = false,
    ): Layout? {
        if (!validLength(totalSpan, positive = true)) return null
        if (!validLength(onCenter, positive = true)) return null
        if (!validLength(startOffset, positive = false)) return null
        if (!validLength(endOffset, positive = false)) return null

        val usable = totalSpan.value - startOffset.value - endOffset.value
        if (usable.signum < 0) return null

        val endLimit = totalSpan.value - endOffset.value
        val marks = ArrayList<Quantity>()
        var step = 0L
        var truncated = false
        while (true) {
            val pos = startOffset.value + onCenter.value * Rational.of(step)
            if (pos > endLimit) break
            if (marks.size >= MAX_MARKS) {
                truncated = true
                break
            }
            marks.add(Quantity.inches(pos))
            step++
        }

        val lastRegular = marks.lastOrNull()?.value ?: startOffset.value
        val remainder = endLimit - lastRegular
        if (includeEnd && !truncated && remainder.signum > 0 && marks.size < MAX_MARKS) {
            marks.add(Quantity.inches(endLimit))
        }

        return Layout(
            totalSpan = totalSpan,
            usableSpan = Quantity.inches(usable),
            spacing = onCenter,
            startOffset = startOffset,
            endOffset = endOffset,
            marks = marks,
            remainder = Quantity.inches(remainder),
            truncated = truncated,
        )
    }

    /**
     * Divides the usable span (`totalSpan - startOffset - endOffset`) into [spaces] equal intervals,
     * returning `spaces + 1` cumulative marks from `startOffset` to `totalSpan - endOffset`.
     */
    fun equalSpaces(
        totalSpan: Quantity,
        spaces: Int,
        startOffset: Quantity = ZERO_LENGTH,
        endOffset: Quantity = ZERO_LENGTH,
    ): Layout? {
        if (spaces <= 0) return null
        if (!validLength(totalSpan, positive = true)) return null
        if (!validLength(startOffset, positive = false)) return null
        if (!validLength(endOffset, positive = false)) return null

        val usable = totalSpan.value - startOffset.value - endOffset.value
        if (usable.signum <= 0) return null

        val step = usable / Rational.of(spaces.toLong())
        val truncated = spaces + 1 > MAX_MARKS
        val count = minOf(spaces + 1, MAX_MARKS)
        val marks = List(count) { i ->
            Quantity.inches(startOffset.value + step * Rational.of(i.toLong()))
        }

        return Layout(
            totalSpan = totalSpan,
            usableSpan = Quantity.inches(usable),
            spacing = Quantity.inches(step),
            startOffset = startOffset,
            endOffset = endOffset,
            marks = marks,
            remainder = ZERO_LENGTH,
            truncated = truncated,
        )
    }

    /**
     * Places [count] evenly spaced marks across the usable span (`totalSpan - startOffset - endOffset`).
     * For `count == 1`, places a single centered mark; for `count >= 2`, divides into `count - 1` equal spaces.
     */
    fun equalCount(
        totalSpan: Quantity,
        count: Int,
        startOffset: Quantity = ZERO_LENGTH,
        endOffset: Quantity = ZERO_LENGTH,
    ): Layout? {
        if (count <= 0) return null
        if (count >= 2) return equalSpaces(totalSpan, count - 1, startOffset, endOffset)
        if (!validLength(totalSpan, positive = true)) return null
        if (!validLength(startOffset, positive = false)) return null
        if (!validLength(endOffset, positive = false)) return null

        val usable = totalSpan.value - startOffset.value - endOffset.value
        if (usable.signum < 0) return null
        val center = startOffset.value + usable / Rational.of(2)
        return Layout(
            totalSpan = totalSpan,
            usableSpan = Quantity.inches(usable),
            spacing = Quantity.inches(usable),
            startOffset = startOffset,
            endOffset = endOffset,
            marks = listOf(Quantity.inches(center)),
            remainder = ZERO_LENGTH,
            truncated = false,
        )
    }

    private val ZERO_LENGTH = Quantity.inches(Rational.ZERO)

    private fun validLength(q: Quantity, positive: Boolean): Boolean =
        q.dimension == Dimension.LENGTH && if (positive) q.value.signum > 0 else q.value.signum >= 0
}
