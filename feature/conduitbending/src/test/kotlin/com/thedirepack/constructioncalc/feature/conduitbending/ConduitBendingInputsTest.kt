package com.thedirepack.constructioncalc.feature.conduitbending

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendPlanner
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BendType
import com.thedirepack.constructioncalc.feature.conduitbending.domain.BenderTable
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
