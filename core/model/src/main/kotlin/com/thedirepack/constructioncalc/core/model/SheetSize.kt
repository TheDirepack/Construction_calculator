package com.thedirepack.constructioncalc.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Standard drawing sheet sizes. A printed scale (¼″ = 1′) only holds when the sheet is printed at
 * full size, so a page that matches none of these was most likely scanned, photographed or
 * reduced. A page that does match may still be a reduced set (a 24 × 36 set printed on 11 × 17).
 */
enum class SheetSize(val label: String, val shortInches: Double, val longInches: Double) {
    ANSI_A("Letter (ANSI A)", 8.5, 11.0),
    ANSI_B("Tabloid (ANSI B)", 11.0, 17.0),
    ANSI_C("ANSI C", 17.0, 22.0),
    ANSI_D("ANSI D", 22.0, 34.0),
    ANSI_E("ANSI E", 34.0, 44.0),
    ARCH_A("ARCH A", 9.0, 12.0),
    ARCH_B("ARCH B", 12.0, 18.0),
    ARCH_C("ARCH C", 18.0, 24.0),
    ARCH_D("ARCH D", 24.0, 36.0),
    ARCH_E1("ARCH E1", 30.0, 42.0),
    ARCH_E("ARCH E", 36.0, 48.0),
    ISO_A4("A4", 210 / 25.4, 297 / 25.4),
    ISO_A3("A3", 297 / 25.4, 420 / 25.4),
    ISO_A2("A2", 420 / 25.4, 594 / 25.4),
    ISO_A1("A1", 594 / 25.4, 841 / 25.4),
    ISO_A0("A0", 841 / 25.4, 1189 / 25.4),
    ;

    companion object {
        /** The standard sheet a page of this size (in PDF points) is, within 1.5 %, or null. */
        fun of(widthPoints: Double, heightPoints: Double): SheetSize? {
            val short = min(widthPoints, heightPoints) / 72
            val long = max(widthPoints, heightPoints) / 72
            return entries.firstOrNull { abs(short / it.shortInches - 1) <= .015 && abs(long / it.longInches - 1) <= .015 }
        }
    }
}
