package com.thedirepack.constructioncalc.core.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecimalsTest {
    @Test fun parsesWhatPeopleType() {
        assertEquals(12.5, Decimals.parse(" 12,5 ")!!, 0.0)
        assertEquals(-3.0, Decimals.parse("−3")!!, 0.0)
        assertEquals(0.5, Decimals.parse(".5")!!, 0.0)
        assertEquals(5.0, Decimals.parse("5.")!!, 0.0)
        for (bad in listOf("", " ", "-", ".", "abc", "1e5", "NaN", "Infinity", "1.2.3", "--1")) assertNull(bad, Decimals.parse(bad))
    }

    @Test fun trimsAndFixes() {
        assertEquals("23.5", Decimals.trim(23.5))
        assertEquals("30", Decimals.trim(30.0))
        assertEquals("0.96", Decimals.trim(0.96))
        assertEquals("3.1", Decimals.trim(3.14159, 1))
        assertEquals("0", Decimals.trim(-0.0001, 2))
        assertEquals("32.0", Decimals.fixed(32.0, 1))
        assertEquals("0.3460", Decimals.fixed(0.346, 4))
    }
}
