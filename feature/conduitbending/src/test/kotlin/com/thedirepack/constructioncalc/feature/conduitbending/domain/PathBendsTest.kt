package com.thedirepack.constructioncalc.feature.conduitbending.domain

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.feature.conduitbending.ConduitBendingInputs
import com.thedirepack.constructioncalc.feature.conduitbending.ConduitBendingTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mapping Bending results to run-path bends (Wire Pull Planner plan, "Bends from Bending"). */
class PathBendsTest {
    @Test fun eachBendTypeMapsToItsBends() {
        assertEquals(listOf(30.0, 30.0), PathBends.angles(BendType.OFFSET, 30.0))
        assertEquals(listOf(45.0, 45.0), PathBends.angles(BendType.ROLLING_OFFSET, 45.0))
        assertEquals(listOf(22.5, 45.0, 22.5), PathBends.angles(BendType.SADDLE_3, 45.0))
        assertEquals(listOf(30.0, 60.0, 30.0), PathBends.angles(BendType.SADDLE_3, 60.0))
        assertEquals(listOf(22.5, 22.5, 22.5, 22.5), PathBends.angles(BendType.SADDLE_4, 22.5))
        assertEquals(listOf(10.0), PathBends.angles(BendType.KICK, 10.0))
        assertEquals(listOf(90.0), PathBends.angles(BendType.STUB_UP, null))
        assertEquals(listOf(90.0, 90.0), PathBends.angles(BendType.STUB_UP, null, backToBack = true))
        assertTrue(PathBends.angles(BendType.OFFSET, null).isEmpty())
        assertTrue(PathBends.angles(BendType.OFFSET, Double.NaN).isEmpty())
    }

    @Test fun aSavedItemGivesTaggableBends() {
        val inputs = ConduitBendingInputs(bend = BendType.SADDLE_3.name, saddleHeight = Quantity.inches(Rational.of(4L)).stored())
        val item = ProjectItem(toolId = ConduitBendingTool.ID, title = "Saddle", createdAt = 0, inputs = ToolInputs.encode(inputs))
        val bends = ConduitBendingTool.pathBends(item)
        assertEquals(listOf(22.5, 45.0, 22.5), bends.map { it.angleDegrees })
        assertTrue(bends.all { it.radius == null && it.sourceItemId == null })
        // No measurements yet: no result, no bends.
        val empty = item.copy(inputs = ToolInputs.encode(ConduitBendingInputs(bend = BendType.OFFSET.name)))
        assertTrue(ConduitBendingTool.pathBends(empty).isEmpty())
        // Total degrees match the bend total the plan reports.
        val request = inputs.toRequest(BenderTable.DEFAULT)
        assertEquals(BendPlanner.plan(request).totalDegrees!!, PathBends.of(request, BendPlanner.plan(request), "x").sumOf(PathSegment.Bend::angleDegrees), 1e-9)
    }
}
