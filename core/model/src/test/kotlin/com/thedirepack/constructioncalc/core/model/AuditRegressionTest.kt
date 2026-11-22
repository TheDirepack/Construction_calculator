package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AuditRegressionTest {
    @Test fun defaultPullSetupDoesNotClaimZeroIncomingTension() {
        val run = Run(
            name = "Feeder",
            raceway = Raceway(ConduitType.EMT, TradeSize.S_1, Quantity.feet(Rational.of(100)).stored()),
            path = listOf(PathSegment.Straight(Quantity.feet(Rational.of(100)).stored())),
        )
        val panel = Panel(
            name = "LP-1",
            circuits = listOf(Circuit(slot = 1, breakerAmps = 20, runIds = listOf(run.id))),
        )
        val project = Project.create("Job", 1L).copy(panels = listOf(panel), runs = listOf(run))
        val pull = PullAnalysis.analyze(project, run.id)!!
        assertFalse(
            "Default incoming tension is 25 lb, not 0 lb",
            pull.notes.any { it.text.contains("Incoming tension is 0") },
        )
    }

    @Test fun designModelsValidateInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { Circuit(slot = 1, breakerAmps = 0) }
        assertThrows(IllegalArgumentException::class.java) { Circuit(slot = 1, loadVa = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Circuit(slot = 1, loadVa = -10.0) }
        assertThrows(IllegalArgumentException::class.java) {
            Circuit(slot = 1, length = Quantity(Rational.of(5), Dimension.AREA).stored())
        }
        assertThrows(IllegalArgumentException::class.java) { Panel(name = "P", busAmps = 0) }
        assertThrows(IllegalArgumentException::class.java) { Panel(name = "P", mainAmps = -100) }
        assertThrows(IllegalArgumentException::class.java) { Raceway(ambientC = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { DesignDefaults(voltageDropTargetPercent = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { DesignDefaults(ambientC = Double.NaN) }
    }
}
