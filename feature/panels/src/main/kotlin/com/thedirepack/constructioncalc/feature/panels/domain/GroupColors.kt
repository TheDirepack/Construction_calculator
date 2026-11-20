package com.thedirepack.constructioncalc.feature.panels.domain

/** The colours a circuit group can take. Each has a name, so the colour is never the only cue. */
data class GroupColor(val name: String, val argb: Int)

object GroupColors {
    val palette: List<GroupColor> = listOf(
        GroupColor("Red", 0xFFC62828.toInt()),
        GroupColor("Orange", 0xFFEF6C00.toInt()),
        GroupColor("Amber", 0xFFF9A825.toInt()),
        GroupColor("Green", 0xFF2E7D32.toInt()),
        GroupColor("Teal", 0xFF00796B.toInt()),
        GroupColor("Blue", 0xFF1565C0.toInt()),
        GroupColor("Purple", 0xFF6A1B9A.toInt()),
        GroupColor("Pink", 0xFFAD1457.toInt()),
        GroupColor("Gray", 0xFF546E7A.toInt()),
    )

    /** The first palette colour not in [used], else the one used least. A colour of 0 (unset) counts as unused. */
    fun next(used: List<Int>): Int =
        palette.firstOrNull { it.argb !in used }?.argb
            ?: palette.minBy { c -> used.count { it == c.argb } }.argb

    /** The palette entry for [argb], or Gray for unset or unknown colours. */
    fun of(argb: Int): GroupColor = palette.firstOrNull { it.argb == argb } ?: palette.last()
}
