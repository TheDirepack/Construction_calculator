package com.thedirepack.constructioncalc.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolTargetTest {
    @Test fun roundTrips() {
        listOf(
            ToolTarget.OfProject(newId()),
            ToolTarget.OfCircuit(newId(), newId()),
            ToolTarget.OfRun(newId(), newId()),
        ).forEach { assertEquals(it, ToolTarget.decode(it.encode())) }
    }

    @Test fun rejectsAnythingElse() {
        listOf("", "project", "project|", "circuit|a", "run|a|", "pull|a|b", "project|a|b").forEach { assertNull(it, ToolTarget.decode(it)) }
    }
}
