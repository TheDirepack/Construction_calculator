package com.thedirepack.constructioncalc.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetSizeTest {
    @Test
    fun recognizesStandardSheetsInEitherOrientation() {
        assertEquals(SheetSize.ANSI_A, SheetSize.of(612.0, 792.0))
        assertEquals(SheetSize.ANSI_A, SheetSize.of(792.0, 612.0))
        assertEquals(SheetSize.ARCH_E1, SheetSize.of(2160.0, 3024.0))
        assertEquals(SheetSize.ARCH_D, SheetSize.of(2592.0, 1728.0))
        assertEquals(SheetSize.ISO_A3, SheetSize.of(841.89, 1190.55))
    }

    @Test
    fun scansAndOddPagesMatchNothing() {
        // A phone scan of a plan set: 1000 × 764 pt.
        assertNull(SheetSize.of(1000.0, 764.0))
        assertNull(SheetSize.of(960.0, 621.12))
    }
}
