package com.thedirepack.constructioncalc.feature.drawings.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingHistoryTest {
    @Test fun undoAndRedoStepThroughSnapshots() {
        var history = DrawingHistory<String>()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo("v0"))
        assertNull(history.redo("v0"))

        history = history.push("v0")
        history = history.push("v1")
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        val (afterUndo1, h1) = history.undo("v2")!!
        assertEquals("v1", afterUndo1)
        assertTrue(h1.canUndo)
        assertTrue(h1.canRedo)

        val (afterUndo2, h2) = h1.undo("v1")!!
        assertEquals("v0", afterUndo2)
        assertFalse(h2.canUndo)
        assertTrue(h2.canRedo)

        val (afterRedo1, h3) = h2.redo("v0")!!
        assertEquals("v1", afterRedo1)
        assertTrue(h3.canUndo)
        assertTrue(h3.canRedo)

        val (afterRedo2, h4) = h3.redo("v1")!!
        assertEquals("v2", afterRedo2)
        assertFalse(h4.canRedo)
    }

    @Test fun pushClearsRedoBranch() {
        var history = DrawingHistory<Int>()
        history = history.push(1).push(2)
        val (_, afterUndo) = history.undo(3)!!
        assertTrue(afterUndo.canRedo)

        val branched = afterUndo.push(2)
        assertFalse(branched.canRedo)
        assertNull(branched.redo(99))
    }

    @Test fun respectsMaxEntriesLimit() {
        var history = DrawingHistory<Int>(maxEntries = 3)
        for (i in 1..5) history = history.push(i)
        assertEquals(listOf(3, 4, 5), history.undoStack)
    }
}
