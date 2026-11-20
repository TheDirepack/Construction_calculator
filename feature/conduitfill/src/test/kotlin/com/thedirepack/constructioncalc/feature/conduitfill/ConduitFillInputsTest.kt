package com.thedirepack.constructioncalc.feature.conduitfill

import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.model.ToolInputs
import com.thedirepack.constructioncalc.core.model.ValueKind
import com.thedirepack.constructioncalc.feature.conduitfill.domain.ConduitFillReport
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConduitFillInputsTest {

    @Test
    fun defaultInputsRoundTripAndEmptyJsonDecodes() {
        val defaults = ConduitFillInputs()
        val decoded = ToolInputs.decode<ConduitFillInputs>(ToolInputs.encode(defaults))
        assertEquals(defaults, decoded)

        val fromEmpty = ToolInputs.decode<ConduitFillInputs>(JsonObject(emptyMap()))
        assertEquals(ConduitFillInputs(), fromEmpty)
    }

    @Test
    fun validInputsProduceValidFillRequestAndReport() {
        val inputs = ConduitFillInputs(
            conduitType = ConduitType.EMT.code,
            tradeSize = TradeSize.S_3_4.label,
            nipple = false,
            groups = listOf(
                ConductorGroupInput(count = "3", insulation = "THHN", size = "12 AWG"),
                ConductorGroupInput(count = "1", insulation = BARE_CODE, size = "12 AWG"),
            ),
        )
        val parsed = inputs.toRequest()
        assertTrue(parsed.problems.isEmpty())
        assertNotNull(parsed.request)

        val report = ConduitFillReport.build(parsed.request!!)
        assertNotNull(report)
        assertEquals(4, report!!.analysis.conductorCount)
        assertTrue(report.analysis.withinLimit)
        assertTrue(report.results.any { it.key == ConduitFillReport.KEY_PERCENT })
        assertTrue(report.results.any { it.key == ConduitFillReport.KEY_CONDUCTOR_AREA })
        assertTrue(report.results.any { it.key == ConduitFillReport.KEY_ALLOWED_AREA })
        assertTrue(report.results.any { it.key == ConduitFillReport.KEY_MIN_CONDUIT })
        assertTrue(report.results.any { it.kind == ValueKind.PERCENT })
    }

    @Test
    fun corruptOrInvalidConduitTypeReportsProblem() {
        val corrupt = ConduitFillInputs(conduitType = "NON_EXISTENT_CONDUIT")
        val parsed = corrupt.toRequest()
        assertNull(parsed.request)
        assertTrue(parsed.problems.any { it.contains("NON_EXISTENT_CONDUIT") })
    }

    @Test
    fun invalidConductorCountsReportProblems() {
        val badCount = ConduitFillInputs(
            groups = listOf(
                ConductorGroupInput(count = "not-a-number"),
                ConductorGroupInput(count = "-5"),
            ),
        )
        val parsed = badCount.toRequest()
        assertNull(parsed.request)
        assertEquals(2, parsed.problems.size)
        assertTrue(parsed.problems[0].contains("isn't a whole number"))
        assertTrue(parsed.problems[1].contains("isn't a whole number"))
    }

    @Test
    fun unlistedConductorSizeForInsulationReportsProblem() {
        // 18 AWG THHN is not listed in Table 5
        val unlisted = ConduitFillInputs(
            groups = listOf(
                ConductorGroupInput(count = "1", insulation = "THHN", size = "18 AWG"),
            ),
        )
        val parsed = unlisted.toRequest()
        assertNull(parsed.request)
        assertTrue(parsed.problems.any { it.contains("isn't listed") })
    }

    @Test
    fun emptyOrZeroGroupsIgnoredGracefully() {
        val emptyGroups = ConduitFillInputs(
            groups = listOf(
                ConductorGroupInput(count = ""),
                ConductorGroupInput(count = "0"),
                ConductorGroupInput(count = "   "),
            ),
        )
        val parsed = emptyGroups.toRequest()
        assertTrue(parsed.problems.isEmpty())
        assertNotNull(parsed.request)
        // With 0 active conductors, ConduitFillReport.build returns null safely
        val report = ConduitFillReport.build(parsed.request!!)
        assertNull(report)
    }
}
