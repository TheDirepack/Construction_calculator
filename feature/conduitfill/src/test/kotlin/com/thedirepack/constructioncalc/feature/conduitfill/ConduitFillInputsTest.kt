package com.thedirepack.constructioncalc.feature.conduitfill

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.electrical.FillConductor
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConduitFillInputsTest {
    @Test fun savedInputsRoundTripAndEmptyFileGivesDefaults() {
        val custom = ConduitFillInputs(
            conduitType = ConduitType.PVC_80.code,
            tradeSize = TradeSize.S_2.label,
            nipple = true,
            groups = listOf(
                ConductorGroupInput("4", InsulationType.XHHW.code, "4/0 AWG"),
                ConductorGroupInput("1", BARE_CODE, "6 AWG"),
            ),
            fitInsulation = InsulationType.THW.code,
            fitSize = "250 kcmil",
        )
        assertEquals(custom, ToolInputs.decode<ConduitFillInputs>(ToolInputs.encode(custom)))
        assertEquals(ConduitFillInputs(), ToolInputs.decode<ConduitFillInputs>(JsonObject(emptyMap())))
    }

    @Test fun defaultsAreACompleteValidCircuit() {
        val parsed = ConduitFillInputs().toRequest()
        assertTrue(parsed.problems.isEmpty())
        val request = parsed.request!!
        assertEquals("3/4\" EMT", request.conduit.displayName)
        assertEquals(listOf(3, 1), request.groups.map { it.count })
        assertTrue(request.groups[1].spec is FillConductor.Bare)
        assertNotNull(request.fit)
    }

    @Test fun emptyAndZeroCountsAreSkippedNotErrors() {
        val inputs = ConduitFillInputs(groups = listOf(ConductorGroupInput(""), ConductorGroupInput("0"), ConductorGroupInput("2")))
        val parsed = inputs.toRequest()
        assertTrue(parsed.problems.isEmpty())
        assertEquals(listOf(2), parsed.request!!.groups.map { it.count })
    }

    @Test fun badCountIsReported() {
        val parsed = ConduitFillInputs(groups = listOf(ConductorGroupInput("2x"))).toRequest()
        assertNull(parsed.request)
        assertTrue(parsed.problems.single().startsWith("Group 1:"))
    }

    @Test fun sizeTheTablesDontListIsReportedNotInvented() {
        // THHN has no 16 AWG row in Table 5.
        val parsed = ConduitFillInputs(groups = listOf(ConductorGroupInput("3", InsulationType.THHN.code, "16 AWG"))).toRequest()
        assertNull(parsed.request)
        assertTrue("16 AWG" in parsed.problems.single())
    }

    @Test fun tradeSizeMissingFromTheTypeIsReported() {
        // Type EB PVC has no 1/2" row.
        val parsed = ConduitFillInputs(conduitType = ConduitType.PVC_EB.code, tradeSize = "1/2").toRequest()
        assertNull(parsed.request)
        assertTrue("1/2" in parsed.problems.single())
        assertNull(ConduitFillInputs(conduitType = "NOPE").toRequest().request)
    }

    @Test fun fitWireNotListedJustSkipsTheCount() {
        val parsed = ConduitFillInputs(fitInsulation = InsulationType.THHN.code, fitSize = "18 AWG").toRequest()
        assertTrue(parsed.problems.isEmpty())
        assertNull(parsed.request!!.fit)
    }

    @Test fun conductorSpecsAndSizeLists() {
        assertEquals(ConductorSize.AWG_14, sizesFor(InsulationType.THHN.code).first())
        assertEquals(ConductorSize.KCMIL_1000, sizesFor(InsulationType.THHN.code).last())
        assertEquals(ConductorSize.AWG_18, sizesFor(BARE_CODE).first())
        assertEquals(ConductorSize.KCMIL_2000, sizesFor(BARE_CODE).last())
        assertEquals(ConductorSize.AWG_14, sizeFor(InsulationType.THHN.code, ConductorSize.AWG_16))
        assertEquals(ConductorSize.AWG_16, sizeFor(InsulationType.TFN.code, ConductorSize.AWG_12))
        assertNull(conductorSpec("NOPE", "12 AWG"))
        assertNull(conductorSpec(InsulationType.THHN.code, "huge"))
    }

    // ---- view model ------------------------------------------------------------------------------------

    @Test fun switchingConduitTypeKeepsOrMovesUpTheSize() {
        val vm = ConduitFillViewModel()
        vm.setTradeSize(TradeSize.S_1)
        vm.setConduitType(ConduitType.PVC_EB) // no 1": the next larger it has is 2"
        assertEquals(ConduitType.PVC_EB.code, vm.inputs.conduitType)
        assertEquals("2", vm.inputs.tradeSize)
        vm.setConduitType(ConduitType.RMC)
        assertEquals("2", vm.inputs.tradeSize)
    }

    @Test fun groupEditing() {
        val vm = ConduitFillViewModel()
        vm.setCount(0, "1a2")
        assertEquals("12", vm.inputs.groups[0].count) // digits only
        vm.setInsulation(0, InsulationType.TFN.code) // TFN has only 18 and 16 AWG: 12 AWG moves to the nearest
        assertEquals("16 AWG", vm.inputs.groups[0].size)
        vm.setInsulation(0, BARE_CODE)
        vm.setSize(0, ConductorSize.AWG_6)
        assertEquals("6 AWG", vm.inputs.groups[0].size)

        val before = vm.inputs.groups.size
        vm.addGroup()
        assertEquals(before + 1, vm.inputs.groups.size)
        assertEquals("1", vm.inputs.groups.last().count)
        repeat(ConduitFillViewModel.MAX_GROUPS) { vm.addGroup() }
        assertEquals(ConduitFillViewModel.MAX_GROUPS, vm.inputs.groups.size)
        repeat(ConduitFillViewModel.MAX_GROUPS) { vm.removeGroup(0) }
        assertEquals(1, vm.inputs.groups.size) // the last group can't be removed
    }

    @Test fun restoreLoadsASavedItemOnce() {
        val saved = ConduitFillInputs(conduitType = ConduitType.IMC.code, tradeSize = "2", nipple = true)
        val item = ProjectItem(id = "item-1", toolId = ConduitFillTool.ID, title = "t", createdAt = 0, inputs = ToolInputs.encode(saved))
        val vm = ConduitFillViewModel()
        vm.restore(item)
        assertEquals(saved, vm.inputs)
        vm.setNipple(false)
        vm.restore(item) // the screen may call this on every recomposition
        assertEquals(false, vm.inputs.nipple)
    }

    @Test fun namedCableGroupUsesItsOwnDimensionsAndIsntDerated() {
        val cable = com.thedirepack.constructioncalc.core.electrical.NamedCable("x", "3/C #12 MC", diameterIn = 0.55)
        val inputs = ConduitFillInputs(groups = listOf(ConductorGroupInput("3"), ConductorGroupInput.of(cable, count = "2")))
        val parsed = inputs.toRequest()
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        val spec = parsed.request!!.groups[1].spec as FillConductor.Custom
        assertEquals("3/C #12 MC", spec.name)
        assertEquals(com.thedirepack.constructioncalc.core.electrical.NamedCable.circularArea(0.55), spec.dimensions.areaSqIn, 1e-12)
        assertEquals(1, inputs.deratingGroups().size) // only the THHN group
        assertEquals(inputs, ToolInputs.decode<ConduitFillInputs>(ToolInputs.encode(inputs)))
    }

    @Test fun damagedCableGroupIsAProblemNotACrash() {
        val bad = ConduitFillInputs(groups = listOf(ConductorGroupInput("1", insulation = CABLE_CODE, cableName = "Mystery")))
        assertTrue(bad.toRequest().problems.single().contains("Mystery"))
    }

    @Test fun wave2AFieldsRoundTripAndLegacyJsonDecodesCleanly() {
        val wave2A = ConduitFillInputs(
            voltageSystem = com.thedirepack.constructioncalc.feature.conduitfill.domain.VoltageSystem.WYE_480_277.name,
            customVoltageLabel = "480V",
            circuitCount = 3,
            sharedMwbc = true,
            nonlinearHarmonic = true,
            threePhaseCircuits = true,
        )
        assertEquals(wave2A, ToolInputs.decode<ConduitFillInputs>(ToolInputs.encode(wave2A)))
        // Legacy JSON missing new fields still decodes with defaults
        val legacyJson = kotlinx.serialization.json.buildJsonObject {
            put("conduitType", kotlinx.serialization.json.JsonPrimitive(ConduitType.RMC.code))
            put("tradeSize", kotlinx.serialization.json.JsonPrimitive("1"))
        }
        val decoded = ToolInputs.decode<ConduitFillInputs>(legacyJson)!!
        assertEquals(ConduitType.RMC.code, decoded.conduitType)
        assertEquals("1", decoded.tradeSize)
        assertEquals(com.thedirepack.constructioncalc.feature.conduitfill.domain.VoltageSystem.V120, decoded.voltagePreset)
        assertEquals(1, decoded.circuitCount)
    }
}
