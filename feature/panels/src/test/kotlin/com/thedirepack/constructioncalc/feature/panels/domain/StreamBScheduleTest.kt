package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBScheduleTest {

    @Test fun scheduleTextRendersInlineConduitDetails() {
        val c = Circuit(
            slot = 1,
            breakerAmps = 20,
            description = "Office receptacles",
            loadVa = 1440.0,
            length = Quantity.feet(Rational.of(80)).stored(),
            conduitTag = "HR-4",
            conduitType = ConduitType.EMT,
            conduitSize = TradeSize.S_3_4,
        )
        val project = Project(name = "Office", createdAt = 0, panels = listOf(Panel(name = "LP-1", circuits = listOf(c))))
        val design = ProjectAnalysis.analyze(project)
        val text = ScheduleText.render(project, design, date = "Jan 1, 2026")
        assertTrue("Expected inline conduit in schedule text:\n$text", "conduit HR-4 3/4\" EMT" in text)
    }
}
