package com.thedirepack.constructioncalc.core.ui

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.ui.components.targetLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenAndLabelsTest {
    @Test fun measurementsReadAloud() {
        assertEquals("5 feet 3 and 1 over 2 inches", spokenText("5' 3 1/2\""))
        assertEquals("7 over 16 inches", spokenText("7/16\""))
        assertEquals("minus 2 inches", spokenText("−2\""))
        assertEquals("about 10 feet 4 and 3 over 16 inches", spokenText("≈ 10' 4 3/16\""))
        assertEquals("12 degrees C", spokenText("12 °C"))
        assertEquals("2#12, 1#12G", spokenText("2#12, 1#12G"))
    }

    @Test fun targetLabelsNameTheCircuitOrRun() {
        val run = Run(name = "Home run")
        val c = Circuit(slot = 5, description = "Kitchen")
        val p = Project(name = "Job", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP-1", circuits = listOf(c))))
        assertEquals("For LP-1 Circuit 5 · Kitchen", targetLabel(ToolTarget.OfCircuit(p.id, c.id), listOf(p)))
        assertEquals("For run Home run · Job", targetLabel(ToolTarget.OfRun(p.id, run.id), listOf(p)))
        assertNull(targetLabel(ToolTarget.OfRun("gone", run.id), listOf(p)))
    }
}
