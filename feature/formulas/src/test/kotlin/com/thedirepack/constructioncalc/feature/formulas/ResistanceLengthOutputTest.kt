package com.thedirepack.constructioncalc.feature.formulas

import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorResistance
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ResistanceUnit
import com.thedirepack.constructioncalc.core.electrical.Stranding
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResistanceLengthOutputTest {
    private val perKft75 = ConductorResistance.ohmsPerKft(ConductorSize.AWG_12, ConductorMaterial.COPPER, Stranding.STRANDED)!!

    private fun wire(ohms: String, ambient: String = "75", roundTrip: Boolean = false, unit: ResistanceUnit = ResistanceUnit.OHM) =
        FormulaInputs.startResistanceLength().copy(measuredResistance = ohms, wireAmbientC = ambient, wireRoundTrip = roundTrip, resistanceUnit = unit)

    @Test fun tableResistanceAtSeventyFiveIsOneThousandFeet() {
        val out = resistanceLengthOutput(wire(perKft75.toString()))
        val length = out.results.first()
        assertEquals(ValueKind.LENGTH, length.kind)
        assertEquals(12_000.0, length.rational!!.toDouble(), 1e-6) // inches
        assertTrue(out.notices.any { it.refId == "nec-conductor-properties" })
    }

    @Test fun roundTripHalvesAndMilliohmsScale() {
        val loop = resistanceLengthOutput(wire((2 * perKft75).toString(), roundTrip = true)).results.first()
        assertEquals(12_000.0, loop.rational!!.toDouble(), 1e-6)
        val milli = resistanceLengthOutput(wire((perKft75 * 1000).toString(), unit = ResistanceUnit.MILLIOHM)).results.first()
        assertEquals(12_000.0, milli.rational!!.toDouble(), 1e-6)
    }

    @Test fun colderWireIsLongerForTheSameReading() {
        val warm = resistanceLengthOutput(wire("1", "75")).calculation!!.lengthFt
        val cold = resistanceLengthOutput(wire("1", "20")).calculation!!.lengthFt
        // R20 = R75 (1 + 0.00323 × (20 − 75)), so the same ohms is more wire.
        assertEquals(warm / (1 + 0.00323 * (20 - 75)), cold, 1e-6)
    }

    @Test fun badInputIsAnErrorAndBlankIsAHint() {
        assertNull(resistanceLengthOutput(wire("-3")).calculation)
        assertTrue(resistanceLengthOutput(wire("-3")).notices.any { it.level == NoticeLevel.ERROR })
        val blank = resistanceLengthOutput(wire(""))
        assertTrue(blank.results.isEmpty() && blank.notices.none { it.level == NoticeLevel.ERROR })
    }

    @Test fun modeSurvivesSaving() {
        val i = wire("0.5", roundTrip = true)
        assertEquals(i, ToolInputs.decode<FormulaInputs>(ToolInputs.encode(i)))
    }
}
