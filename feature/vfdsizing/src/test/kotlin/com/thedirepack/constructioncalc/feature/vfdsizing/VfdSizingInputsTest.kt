package com.thedirepack.constructioncalc.feature.vfdsizing

import com.thedirepack.constructioncalc.core.electrical.AcPhases
import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.core.ui.components.NoticeLevel
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.LoadType
import com.thedirepack.constructioncalc.feature.vfdsizing.domain.RatingUnit
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VfdSizingInputsTest {
    @Test fun savedInputsRoundTripAndDefaultsDecode() {
        val custom = VfdSizingInputs(
            unit = RatingUnit.KW, hp = 7.5, kw = "5.5", volts = 230, supplyPhase = AcPhases.SINGLE,
            nameplateFla = "18.4", driveInputAmps = "22", loadType = LoadType.CONSTANT_TORQUE,
            material = "ALUMINUM", termination = 60,
        )
        assertEquals(custom, ToolInputs.decode<VfdSizingInputs>(ToolInputs.encode(custom)))
        assertEquals(VfdSizingInputs(), ToolInputs.decode<VfdSizingInputs>(JsonObject(emptyMap())))
        assertEquals(ConductorMaterial.ALUMINUM, custom.conductorMaterial)
    }

    @Test fun kilowattEntryConvertsAndNeedsAValue() {
        assertTrue(VfdSizingInputs(unit = RatingUnit.KW).parse().missing.isNotEmpty())
        val parsed = VfdSizingInputs(unit = RatingUnit.KW, kw = "7,5", volts = 460).parse()
        assertEquals(10.0, parsed.request!!.hp, 0.0)
        assertEquals(10.0, parsed.convertedHp!!, 0.0)
    }

    @Test fun badNumbersAreProblems() {
        assertTrue(VfdSizingInputs(nameplateFla = "abc").parse().problems.isNotEmpty())
        assertNull(VfdSizingInputs(unit = RatingUnit.KW, kw = "-3").parse().request)
    }

    @Test fun outputPublishesTypedResults() {
        val out = vfdSizingOutput(VfdSizingInputs(hp = 10.0, volts = 460, nameplateFla = "13"))
        val byKey = out.results.associateBy { it.key }
        assertEquals(ValueKind.CURRENT, byKey.getValue("min_drive_output").kind)
        assertEquals("13 A", byKey.getValue("min_drive_output").text)
        assertEquals(ValueKind.CURRENT, byKey.getValue("table_flc").kind)
        assertEquals("14 A", byKey.getValue("table_flc").text)
        assertEquals(ValueKind.TEXT, byKey.getValue("input_conductor").kind)
        assertEquals("14 AWG Cu", byKey.getValue("input_conductor").text)
        assertEquals("35 A", byKey.getValue("breaker_max").text)
        assertEquals("25 A", byKey.getValue("fuse_max").text)
        assertEquals(ValueKind.PERCENT, byKey.getValue("nameplate_pct_of_table").kind)
        assertNull(byKey["single_phase_derate"])
        assertTrue(out.results.all { it.kind == ValueKind.TEXT || it.exact != null })
        assertTrue(out.summary.contains("10 HP"))
        assertTrue(out.notices.any { it.text.contains("approximation") })
    }

    @Test fun singlePhaseAddsGuidanceWithAWarning() {
        val out = vfdSizingOutput(VfdSizingInputs(hp = 3.0, volts = 230, supplyPhase = AcPhases.SINGLE))
        val byKey = out.results.associateBy { it.key }
        assertNotNull(byKey["single_phase_drive_guidance"])
        assertEquals(ValueKind.PERCENT, byKey.getValue("single_phase_derate").kind)
        assertTrue(out.notices.any { it.level == NoticeLevel.WARNING && it.text.contains("not a code requirement") })
    }

    @Test fun incompleteFormHasNoResults() {
        val out = vfdSizingOutput(VfdSizingInputs(unit = RatingUnit.KW))
        assertTrue(out.results.isEmpty())
        assertEquals("", out.summary)
    }
}
