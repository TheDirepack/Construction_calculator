package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.model.withRunPath
import org.junit.Assert.assertTrue
import org.junit.Test

/** The panel schedule report carries each run's pull and the project's material takeoff. */
class ReportExtrasTest {
    @Test fun scheduleIncludesPullsAndTakeoff() {
        val run = Run(name = "Home run", raceway = Raceway(ConduitType.EMT, TradeSize.S_3_4))
        val circuits = listOf(Circuit(slot = 1, breakerAmps = 20, loadVa = 1200.0, runIds = listOf(run.id)))
        val p0 = Project(name = "Job", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = circuits)))
        val p = p0.withRunPath(run.id, listOf(PathSegment.Straight(Quantity.feet(Rational.of(60L)).stored()), PathSegment.Bend(90.0)), 1)
        val text = ScheduleText.render(p, ProjectAnalysis.analyze(p), date = "Nov 20, 2026")
        assertTrue(text, "PULLS" in text && "Home run: Pull " in text)
        assertTrue(text, "MATERIAL TAKEOFF" in text && "Wire 12 AWG THHN Cu:" in text && "Conduit 3/4\" EMT:" in text)
        // Nothing to report: no extra sections.
        val bare = Project(name = "Job", createdAt = 0, panels = listOf(Panel(name = "LP")))
        assertTrue(ScheduleText.projectExtras(bare, ProjectAnalysis.analyze(bare)).isEmpty())
    }
}
