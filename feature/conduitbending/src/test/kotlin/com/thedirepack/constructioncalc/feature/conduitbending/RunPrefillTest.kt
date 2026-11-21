package com.thedirepack.constructioncalc.feature.conduitbending

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunPrefillTest {
    @Test fun takesTheRunsConduit() {
        val run = Run(name = "R", raceway = Raceway(ConduitType.RMC, tradeSize = TradeSize.S_3_4))
        val inputs = bendingInputsForRun(Project(name = "P", createdAt = 0, runs = listOf(run)), run.id)!!
        assertEquals(ConduitType.RMC, inputs.conduit)
        assertEquals(TradeSize.S_3_4, inputs.tradeSize)
    }

    @Test fun nonBendableConduitFallsBackToEmt() {
        val run = Run(name = "R", raceway = Raceway(ConduitType.PVC_40, tradeSize = TradeSize.S_2))
        val inputs = bendingInputsForRun(Project(name = "P", createdAt = 0, runs = listOf(run)), run.id)!!
        assertEquals(ConduitType.EMT, inputs.conduit)
        assertEquals(TradeSize.S_1_1_4, inputs.tradeSize) // largest EMT size with bender values
    }

    @Test fun plainRun() {
        val run = Run(name = "R")
        assertNull(bendingInputsForRun(Project(name = "P", createdAt = 0, runs = listOf(run)), run.id))
    }
}
