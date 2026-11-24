package com.thedirepack.constructioncalc.feature.conduitbending

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendPlanner
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendType
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BenderTable
import com.thedirepack.constructioncalc.feature.conduitbending.domain.DistanceAnchor
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickBendItem
import com.thedirepack.constructioncalc.feature.conduitbending.domain.StickPlanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConduitBendingInputsTest {
    @Test fun emptyJsonDecodesToDefaults() {
        assertEquals(ConduitBendingInputs(), ToolInputs.decode<ConduitBendingInputs>(JsonObject(emptyMap())))
    }

    @Test fun savedInputsRoundTripAndRebuildTheSamePlan() {
        val inputs = ConduitBendingInputs(
            bend = BendType.OFFSET.name,
            angle = "30",
            offsetDepth = StoredQuantity("6", Dimension.LENGTH),
            distance = StoredQuantity("20", Dimension.LENGTH),
        )
        val back = ToolInputs.decode<ConduitBendingInputs>(ToolInputs.encode(inputs))
        assertEquals(inputs, back)
        val plan = BendPlanner.plan(back!!.toRequest(BenderTable.DEFAULT))
        assertNotNull(plan.marks.firstOrNull())
        assertEquals(Rational.of(12), plan.figures.first().value)
    }

    @Test fun customAngleIsReadFromTheTextField() {
        val inputs = ConduitBendingInputs(angle = ConduitBendingInputs.CUSTOM, customAngle = "37,5")
        assertEquals(37.5, inputs.angleDegrees()!!, 1e-9)
        assertEquals(null, inputs.copy(customAngle = "").angleDegrees())
    }

    @Test fun saddleUsesItsOwnAngle() {
        val inputs = ConduitBendingInputs(bend = BendType.SADDLE_3.name, angle = "10", saddleAngle = "60")
        assertEquals(60.0, inputs.angleDegrees()!!, 1e-9)
    }

    @Test fun legacySingleBendJsonAutoMigratesIntoOneBendStick() {
        val legacyJson = Json.parseToJsonElement(
            """
            {
              "bend": "STUB_UP",
              "stubHeight": { "exact": "14", "dimension": "LENGTH" },
              "conduitType": "EMT",
              "conduitSize": "3/4",
              "backToBack": true,
              "between": { "exact": "24", "dimension": "LENGTH" },
              "secondStub": { "exact": "10", "dimension": "LENGTH" }
            }
            """.trimIndent(),
        ).jsonObject

        val decoded = ToolInputs.decode<ConduitBendingInputs>(legacyJson)!!
        assertNull(decoded.bends)
        val migrated = decoded.effectiveBends()
        assertEquals(1, migrated.size)
        assertEquals(BendType.STUB_UP, migrated.single().type)
        assertTrue(migrated.single().backToBack)

        val stickPlan = StickPlanner.plan(decoded.toStick(BenderTable.DEFAULT))
        assertTrue(stickPlan.hasResults)
        assertEquals(2, stickPlan.marks.size)
        assertEquals(Rational.of(8), stickPlan.marks[0].positionFromEndAIn) // 14 - 6 takeUp for 3/4" EMT
        assertEquals(180.0, stickPlan.totalDegrees, 1e-9)
    }

    @Test fun multiBendStickInputsRoundTripPreservingRollsAndAnchors() {
        val b1 = StickBendItem(
            id = "b1",
            type = BendType.STUB_UP,
            stubHeight = StoredQuantity("12", Dimension.LENGTH),
            axialRotationDeg = 0.0,
        )
        val b2 = StickBendItem(
            id = "b2",
            type = BendType.OFFSET,
            angle = "45",
            offsetDepth = StoredQuantity("5", Dimension.LENGTH),
            distance = StoredQuantity("18", Dimension.LENGTH),
            distanceAnchor = DistanceAnchor.FROM_PREVIOUS,
            axialRotationDeg = 135.0,
        )
        val inputs = ConduitBendingInputs(
            stickLength = StoredQuantity("144", Dimension.LENGTH),
            bends = listOf(b1, b2),
        )
        val back = ToolInputs.decode<ConduitBendingInputs>(ToolInputs.encode(inputs))!!
        assertEquals(inputs, back)
        assertEquals(Rational.of(144), back.effectiveStickLength)
        assertEquals(2, back.effectiveBends().size)
        assertEquals(135.0, back.effectiveBends()[1].axialRotationDeg, 1e-9)
        assertEquals(DistanceAnchor.FROM_PREVIOUS, back.effectiveBends()[1].distanceAnchor)
    }
}
