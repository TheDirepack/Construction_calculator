package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class RunPathTest {
    private fun ft(feet: Int) = Quantity.feet(Rational.of(feet.toLong())).stored()
    private fun inches(n: Double) = Quantity.inches(Rational.of(n)).stored()
    private fun straight(feet: Int, slope: Double = 0.0) = PathSegment.Straight(ft(feet), slope)
    private fun bend(deg: Double, radiusIn: Double? = null, source: String? = null) =
        PathSegment.Bend(deg, radiusIn?.let(::inches), sourceItemId = source)

    @Test fun bendArcUsesItsRadiusOrTable2() {
        // 90° at 12": 12 × π/2.
        assertEquals(6.0 * PI, RunPath.arcInches(bend(90.0, 12.0), ConduitType.EMT, null)!!, 1e-9)
        // No radius: 1" EMT one-shot column, 5-3/4".
        assertEquals(5.75 * PI / 2, RunPath.arcInches(bend(90.0), ConduitType.EMT, TradeSize.S_1)!!, 1e-9)
        // 1" FMC uses "Other bends", 6".
        assertEquals(6.0 * PI / 2, RunPath.arcInches(bend(90.0), ConduitType.FMC, TradeSize.S_1)!!, 1e-9)
        // 3/8 has no Table 2 row: the 1/2 row (4").
        assertEquals(4.0, RunPath.radiusInches(bend(90.0), ConduitType.FMC, TradeSize.S_3_8)!!, 1e-9)
        assertNull(RunPath.arcInches(bend(90.0), ConduitType.EMT, null))
    }

    @Test fun lengthIsStraightsPlusArcs() {
        val path = listOf(straight(10), bend(90.0, 12.0), straight(20), PathSegment.PullPoint("JB-1"), straight(5))
        assertEquals(35 * 12 + 6 * PI, RunPath.lengthInches(path, ConduitType.EMT, TradeSize.S_1), 1e-9)
        assertEquals(35 * 12.0, RunPath.straightInches(path), 1e-9)
        assertEquals(6 * PI, RunPath.bendInches(path, ConduitType.EMT, TradeSize.S_1), 1e-9)
    }

    @Test fun sectionsSplitAtPullPoints() {
        val path = listOf(
            bend(90.0), straight(10), bend(90.0), bend(90.0), PathSegment.PullPoint(), bend(45.0), bend(45.0),
            PathSegment.PullPoint(), straight(3),
        )
        val sections = RunPath.sections(path)
        assertEquals(3, sections.size)
        assertEquals(listOf(270.0, 90.0, 0.0), RunPath.degreesPerSection(path))
        assertEquals(360.0, RunPath.totalDegrees(path), 0.0)
        assertTrue(RunPath.sectionsOverLimit(path).isEmpty())
        val over = listOf(bend(90.0), bend(90.0), bend(90.0), bend(90.0), bend(10.0))
        assertEquals(1, RunPath.sectionsOverLimit(over).size)
        // Exactly 360 is allowed.
        assertTrue(RunPath.sectionsOverLimit(over.dropLast(1)).isEmpty())
        assertEquals(1, RunPath.sections(emptyList()).size)
    }

    @Test fun withLengthAdjustsTheLastStraight() {
        val path = listOf(straight(10), bend(90.0, 12.0), straight(20))
        val target = Rational.of(600L)
        val adjusted = RunPath.withLength(path, target, ConduitType.EMT, null)!!
        assertEquals(600.0, RunPath.lengthInches(adjusted, ConduitType.EMT, null), 1e-6)
        assertEquals(straight(10), adjusted[0])
        // Too short for the fixed parts: refused.
        assertNull(RunPath.withLength(path, Rational.of(100L), ConduitType.EMT, null))
        // No straight: one is added.
        val bendsOnly = RunPath.withLength(listOf(bend(90.0, 12.0)), Rational.of(100L), ConduitType.EMT, null)!!
        assertEquals(2, bendsOnly.size)
        assertEquals(100.0, RunPath.lengthInches(bendsOnly, ConduitType.EMT, null), 1e-6)
    }

    @Test fun replaceSourceSwapsInPlace() {
        val path = listOf(straight(10), bend(30.0, source = "a"), bend(30.0, source = "a"), straight(5), bend(90.0))
        val replaced = RunPath.replaceSource(path, "a", listOf(bend(45.0), bend(45.0), bend(45.0)))
        assertEquals(listOf(10.0), replaced.filterIsInstance<PathSegment.Straight>().take(1).map { it.lengthInches / 12 })
        assertEquals(listOf(45.0, 45.0, 45.0, 90.0), replaced.filterIsInstance<PathSegment.Bend>().map { it.angleDegrees })
        assertTrue(replaced.filterIsInstance<PathSegment.Bend>().take(3).all { it.sourceItemId == "a" })
        assertTrue(replaced[1] is PathSegment.Bend && replaced[4] is PathSegment.Straight)
        // New source: appended.
        val appended = RunPath.replaceSource(path, "b", listOf(bend(90.0)))
        assertEquals("b", (appended.last() as PathSegment.Bend).sourceItemId)
        assertFalse(RunPath.hasSource(RunPath.removeSource(path, "a"), "a"))
    }

    @Test fun projectKeepsLengthAndPathInStep() {
        val p0 = Project.create("P", 0)
        val runId = p0.runs.first().id
        val path = listOf(straight(50), bend(90.0, 12.0), straight(25))
        val p1 = p0.withRunPath(runId, path, 1)
        val run = p1.run(runId)!!
        assertEquals(ConduitType.EMT, run.raceway!!.conduitType)
        assertEquals(75 * 12 + 6 * PI, run.raceway.length!!.toQuantity().value.toDouble(), 1e-6)
        // Editing the length moves the last straight.
        val p2 = p1.withRunLength(runId, ft(100), 2)!!
        assertEquals(1200.0, RunPath.lengthInches(p2.run(runId)!!.path, ConduitType.EMT, null), 1e-6)
        assertEquals(Rational.of(1200L), p2.run(runId)!!.raceway!!.length!!.toQuantity().value)
        assertNull(p1.withRunLength(runId, ft(10), 2))
    }

    @Test fun designUsesThePathForBendLimits() {
        val p0 = Project.create("P", 0)
        val runId = p0.runs.first().id
        val p = p0.withRunPath(runId, listOf(bend(90.0), bend(90.0), bend(90.0), PathSegment.PullPoint(), bend(90.0), bend(90.0), bend(90.0), bend(90.0), bend(45.0)), 1)
        val rd = ProjectAnalysis.analyze(p).runs.getValue(runId)
        assertEquals(listOf(270.0, 405.0), rd.sectionDegrees)
        assertEquals(675.0, rd.bendDegrees!!, 1e-9)
        val warning = rd.notes.single { it.isWarning && "pull points" in it.text }
        assertTrue(warning.text, "Pull section 2" in warning.text && "358.24(B)" in warning.text)
    }

    @Test fun removingABendingItemRemovesItsBends() {
        val p0 = Project.create("P", 0)
        val runId = p0.runs.first().id
        val item = ProjectItem(toolId = "conduit-bending", title = "Offset", createdAt = 0)
        val p1 = p0.addItem(runId, item, 0).withRunPath(runId, listOf(straight(10), bend(30.0, 12.0, item.id), bend(30.0, 12.0, item.id)), 1)
        val p2 = p1.removeItem(item.id, 2)
        assertEquals(listOf(straight(10)), p2.run(runId)!!.path)
        assertEquals(120.0, p2.run(runId)!!.raceway!!.length!!.toQuantity().value.toDouble(), 1e-9)
        // Duplicating remaps sources to the new item ids.
        val dup = p1.duplicated(now = 3)
        val newItemId = dup.runs.first().items.single().id
        assertTrue(dup.runs.first().path.filterIsInstance<PathSegment.Bend>().all { it.sourceItemId == newItemId })
    }

    @Test fun invalidSegmentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PathSegment.Bend(0.0) }
        assertThrows(IllegalArgumentException::class.java) { PathSegment.Bend(270.0) }
        assertThrows(IllegalArgumentException::class.java) { PathSegment.Bend(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PathSegment.Bend(90.0, inches(0.0)) }
        assertThrows(IllegalArgumentException::class.java) { PathSegment.Straight(ft(-1)) }
        assertThrows(IllegalArgumentException::class.java) { PathSegment.Straight(ft(1), 120.0) }
        assertThrows(IllegalArgumentException::class.java) { PathSegment.Straight(Quantity.number(Rational.ONE).stored()) }
    }

    @Test fun pathRoundTripsThroughAProjectFile() {
        val p0 = Project.create("P", 0)
        val runId = p0.runs.first().id
        val path = listOf(
            straight(10, 15.0), PathSegment.Bend(45.0, inches(24.0), BendPlane.VERTICAL_UP, "src"),
            PathSegment.PullPoint("LB"), straight(3),
        )
        val p = p0.withRunPath(runId, path, 1)
        val decoded = ProjectCodec.decode(ProjectCodec.encode(p))
        assertEquals(path, decoded.run(runId)!!.path)
        val text = ProjectCodec.encode(p)
        assertTrue(text.contains("\"type\": \"pull_point\""))
    }

    /** Malformed paths in an imported file are rejected as damaged files, never a crash. */
    @Test fun malformedPathsFailAsDamagedFiles() {
        val p0 = Project.create("P", 0)
        val runId = p0.runs.first().id
        val good = ProjectCodec.encode(p0.withRunPath(runId, listOf(straight(10), bend(90.0, 12.0), PathSegment.PullPoint()), 1))
        val bad = listOf(
            good.replace("\"angleDegrees\": 90.0", "\"angleDegrees\": -90.0"),
            good.replace("\"angleDegrees\": 90.0", "\"angleDegrees\": 1.0E9"),
            good.replace("\"angleDegrees\": 90.0", "\"angleDegrees\": \"x\""),
            good.replace("\"type\": \"bend\"", "\"type\": \"wormhole\""),
            good.replace("\"slopeDegrees\": 0.0", "\"slopeDegrees\": 400.0"),
            good.replace("\"exact\": \"120\"", "\"exact\": \"-120\""),
            good.replace("\"exact\": \"120\"", "\"exact\": \"1/0\""),
            good.replace("\"exact\": \"12\"", "\"exact\": \"0\""),
            good.replace("\"path\": [", "\"path\": [null, "),
            good.replace("\"path\": [", "\"path\": 7, \"x\": ["),
        )
        bad.forEachIndexed { i, text ->
            assertTrue("case $i should differ", text != good)
            assertThrows("case $i", ProjectFormatException::class.java) { ProjectCodec.decode(text) }
            assertThrows("case $i", ProjectFormatException::class.java) { AppFileCodec.decode(text) }
        }
    }
}

class AddBendsToRunPathTest {
    private fun ft(feet: Long) = Quantity.feet(Rational.of(feet)).stored()

    @Test fun firstBendsKeepATypedLength() {
        val p0 = Project.create("P", 0)
        val runId = p0.runs.first().id
        val typed = p0.updateRun(runId, 0) { it.copy(raceway = Raceway(ConduitType.EMT, TradeSize.S_3_4, length = ft(100))) }
        val p = typed.addBendsToRunPath(runId, "item", listOf(PathSegment.Bend(30.0), PathSegment.Bend(30.0)), 1)
        val run = p.run(runId)!!
        assertEquals(3, run.path.size)
        assertEquals(1200.0, run.raceway!!.length!!.toQuantity().value.toDouble(), 1e-6)
        assertTrue(run.path.take(2).all { it is PathSegment.Bend && it.sourceItemId == "item" })
        // Updating the item swaps its bends in place.
        val again = p.addBendsToRunPath(runId, "item", listOf(PathSegment.Bend(45.0), PathSegment.Bend(45.0)), 2)
        assertEquals(listOf(45.0, 45.0), again.run(runId)!!.path.filterIsInstance<PathSegment.Bend>().map { it.angleDegrees })
    }

    @Test fun movingAnItemTakesItsBendsOutOfTheOldPath() {
        val p0 = Project.create("P", 0)
        val a = p0.runs.first().id
        val p1 = p0.addRun(Run(name = "B"), 0)
        val b = p1.runs.last().id
        val item = ProjectItem(toolId = "conduit-bending", title = "Kick", createdAt = 0)
        val p2 = p1.addItem(a, item, 0).withRunPath(a, listOf(PathSegment.Straight(ft(10)), PathSegment.Bend(30.0, sourceItemId = item.id)), 1)
        val moved = p2.moveItem(item.id, b, 0, 2)
        assertFalse(RunPath.hasSource(moved.run(a)!!.path, item.id))
        assertEquals(item.id, moved.run(b)!!.items.single().id)
    }
}

class WithRacewayTest {
    @Test fun conduitEditsKeepLengthAndPathInStep() {
        val p0 = Project.create("P", 0)
        val runId = p0.runs.first().id
        val ten = Quantity.feet(Rational.of(10L)).stored()
        val p1 = p0.withRunPath(runId, listOf(PathSegment.Straight(ten), PathSegment.Bend(90.0)), 1)
            .withRaceway(runId, Raceway(ConduitType.EMT, TradeSize.S_1), 2)
        assertEquals(120 + 5.75 * Math.PI / 2, p1.run(runId)!!.raceway!!.length!!.toQuantity().value.toDouble(), 1e-6)
        // A bigger size changes the Table 2 radius and so the length.
        val p2 = p1.withRaceway(runId, p1.run(runId)!!.raceway!!.copy(tradeSize = TradeSize.S_2), 3)
        assertEquals(120 + 9.5 * Math.PI / 2, p2.run(runId)!!.raceway!!.length!!.toQuantity().value.toDouble(), 1e-6)
        // A typed length moves the straight.
        val p3 = p2.withRaceway(runId, p2.run(runId)!!.raceway!!.copy(length = Quantity.feet(Rational.of(20L)).stored()), 4)
        assertEquals(240.0, RunPath.lengthInches(p3.run(runId)!!.path, ConduitType.EMT, TradeSize.S_2), 1e-6)
        // Too short for the bends: the path wins.
        val p4 = p3.withRaceway(runId, p3.run(runId)!!.raceway!!.copy(length = Quantity.inches(Rational.of(5L)).stored()), 5)
        assertEquals(240.0, p4.run(runId)!!.raceway!!.length!!.toQuantity().value.toDouble(), 1e-6)
    }

    /** Pull sections with no entered tension start at the SIMpull workbook's 25 lb. */
    @Test fun incomingTensionDefaultsTo25Pounds() {
        val setup = PullSetup()
        org.junit.Assert.assertEquals(DEFAULT_INCOMING_LB, setup.incoming(0), 0.0)
        org.junit.Assert.assertEquals(25.0, setup.incoming(3), 0.0)
        val edited = setup.withIncoming(2, 80.0)
        org.junit.Assert.assertEquals(listOf(25.0, 25.0, 80.0), edited.incomingTension)
    }
}
