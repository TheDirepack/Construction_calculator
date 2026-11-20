package com.thedirepack.constructioncalc.core.electrical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardOcpdTest {
    @Test fun table240_6A() {
        assertEquals(38, StandardOcpd.ratings.size)
        assertEquals(10, StandardOcpd.ratings.first())
        assertEquals(6000, StandardOcpd.ratings.last())
        assertTrue(listOf(15, 20, 30, 45, 110, 175, 225, 450, 1200, 2500).all { it in StandardOcpd.ratings })
        assertTrue(listOf(12, 55, 65, 120, 601).none { it in StandardOcpd.ratings })
        assertTrue(StandardOcpd.ratings.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun nextUpRoundsToTheNextStandardSize() {
        assertEquals(35, StandardOcpd.nextUp(35.0))
        assertEquals(35, StandardOcpd.nextUp(35.0000000001))
        assertEquals(45, StandardOcpd.nextUp(42.5))
        assertEquals(90, StandardOcpd.nextUp(85.0))
        assertEquals(10, StandardOcpd.nextUp(3.0))
        assertNull(StandardOcpd.nextUp(6001.0))
    }

    @Test fun fuseOnlyRatings() {
        assertEquals(10, StandardOcpd.nextUp(3.0, fuse = false))
        assertEquals(3, StandardOcpd.nextUp(3.0, fuse = true))
        assertEquals(601, StandardOcpd.nextUp(601.0, fuse = true))
        assertEquals(700, StandardOcpd.nextUp(601.0, fuse = false))
    }

    @Test fun nextDown() {
        assertEquals(40, StandardOcpd.nextDown(44.0))
        assertEquals(45, StandardOcpd.nextDown(45.0))
        assertNull(StandardOcpd.nextDown(9.0))
    }
}
