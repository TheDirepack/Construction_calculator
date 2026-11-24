package com.thedirepack.constructioncalc.core.electrical.code

import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConductorSizing
import com.thedirepack.constructioncalc.core.electrical.WireSizingRequest
import com.thedirepack.constructioncalc.core.electrical.code.nec2023.Nec2023
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class CodeProfileTest {

    @After
    fun resetGlobalProfile() {
        Code.setGlobalProfile(CodeProfile())
    }

    @Test
    fun nec2023IsActiveAndNec2026IsMarkedPending() {
        assertTrue(CodeEdition.NEC_2023.isImplemented)
        assertTrue(CodeEdition.NEC_2023.isActive)
        assertFalse(CodeEdition.NEC_2026.isImplemented)
        assertFalse(CodeEdition.NEC_2026.isActive)
        assertTrue(CodeEdition.NEC_2026.statusNote.contains("not active", ignoreCase = true))
        assertEquals(listOf(CodeEdition.NEC_2023), CodeEdition.activeEntries)

        // Resolving NEC_2026 falls back to active verified NEC_2023 base edition
        val resolved = Code.resolve(CodeProfile(edition = CodeEdition.NEC_2026))
        assertSame(Nec2023, resolved)
        assertEquals(CodeEdition.NEC_2023, resolved.edition)
    }

    @Test
    fun defaultProfileResolvesDirectlyToNec2023() {
        assertEquals(CodeProfile(CodeEdition.NEC_2023, CodeRegions.STANDARD_ID), Code.globalProfile)
        assertSame(Nec2023, Code.resolve(CodeProfile()))
        assertSame(Nec2023, Code.book)
    }

    @Test
    fun regionsRegistryHasStandardAndStructuredRegionalSlots() {
        assertEquals(CodeRegions.STANDARD, CodeRegions.find(CodeRegions.STANDARD_ID))
        assertNotNull(CodeRegions.find(CodeRegions.CALIFORNIA_ID))
        assertNotNull(CodeRegions.find(CodeRegions.MASSACHUSETTS_ID))
        assertNotNull(CodeRegions.find(CodeRegions.WASHINGTON_ID))
        assertNotNull(CodeRegions.find(CodeRegions.NEW_YORK_ID))
        assertNotNull(CodeRegions.find(CodeRegions.CUSTOM_ID))
        assertEquals(CodeRegions.STANDARD, CodeRegions.getOrDefault("nonexistent-region"))

        // Regional slots resolve cleanly without unverified state overrides
        listOf(
            CodeRegions.CALIFORNIA_ID,
            CodeRegions.MASSACHUSETTS_ID,
            CodeRegions.WASHINGTON_ID,
            CodeRegions.NEW_YORK_ID,
        ).forEach { id ->
            val book = Code.resolve(CodeProfile(regionId = id))
            assertEquals(CodeEdition.NEC_2023, book.edition)
        }
    }

    @Test
    fun amendedElectricalCodeOverridesCitationsAndValues() {
        val amendment = RegionalAmendment(
            regionId = CodeRegions.MASSACHUSETTS_ID,
            label = "Massachusetts Custom Test",
            citationOverrides = mapOf(
                CodeRule.MINIMUM_CONDUCTOR_SIZE to "527 CMR 12.00 Rule 310.3(A)",
                CodeRule.VOLTAGE_DROP_BRANCH to "Custom 2% VD Rule",
            ),
            valuesTransform = { v ->
                v.copy(
                    minimumSizeCopper = ConductorSize.AWG_12,
                    voltageDropBranchPercent = 2.0,
                )
            },
            loadCalculationTransform = { lc ->
                lc.copy(smallApplianceCircuitVa = 1800.0)
            },
        )
        val amended = AmendedElectricalCode(Nec2023, amendment)

        assertEquals(CodeEdition.NEC_2023, amended.edition)
        assertEquals("527 CMR 12.00 Rule 310.3(A)", amended.cite(CodeRule.MINIMUM_CONDUCTOR_SIZE))
        assertEquals("Custom 2% VD Rule", amended.cite(CodeRule.VOLTAGE_DROP_BRANCH))
        assertEquals(Nec2023.cite(CodeRule.AMPACITY_TABLE), amended.cite(CodeRule.AMPACITY_TABLE))
        assertEquals(ConductorSize.AWG_12, amended.values.minimumSizeCopper)
        assertEquals(2.0, amended.values.voltageDropBranchPercent, 0.0)
        assertEquals(1800.0, amended.loadCalculation.smallApplianceCircuitVa, 0.0)
        assertSame(Nec2023.ampacity, amended.ampacity)
    }

    @Test
    fun withProfileAndGlobalProfileWorkAndIsolateThreads() {
        val customProfile = CodeProfile(
            edition = CodeEdition.NEC_2023,
            regionId = CodeRegions.CUSTOM_ID,
            customAmendment = RegionalAmendment(
                regionId = CodeRegions.CUSTOM_ID,
                label = "Heavy Min Cu",
                citationOverrides = mapOf(CodeRule.AMPACITY_TABLE to "Custom Table 310.16"),
                valuesTransform = { it.copy(minimumSizeCopper = ConductorSize.AWG_10) },
            ),
        )

        // Default before withProfile
        assertEquals("Table 310.16", CodeRule.AMPACITY_TABLE.cite)
        assertEquals(ConductorSize.AWG_14, Code.book.values.minimumSizeCopper)
        assertEquals(
            ConductorSize.AWG_14,
            ConductorSizing.minimumSize(WireSizingRequest(ocpdAmps = 15, material = ConductorMaterial.COPPER, loadAmps = 10.0)).size,
        )

        // Scoped override via withProfile affects citations and downstream calculations
        Code.withProfile(customProfile) {
            assertEquals("Custom Table 310.16", CodeRule.AMPACITY_TABLE.cite)
            assertEquals(ConductorSize.AWG_10, Code.book.values.minimumSizeCopper)
            val sized = ConductorSizing.minimumSize(
                WireSizingRequest(
                    ocpdAmps = 15,
                    material = ConductorMaterial.COPPER,
                    loadAmps = 10.0,
                ),
            )
            assertEquals(ConductorSize.AWG_10, sized.size)

            // Nested override restores outer override cleanly
            val nestedProfile = CodeProfile(
                regionId = CodeRegions.CUSTOM_ID,
                customAmendment = RegionalAmendment(
                    citationOverrides = mapOf(CodeRule.AMPACITY_TABLE to "Nested Table"),
                ),
            )
            Code.withProfile(nestedProfile) {
                assertEquals("Nested Table", CodeRule.AMPACITY_TABLE.cite)
            }
            assertEquals("Custom Table 310.16", CodeRule.AMPACITY_TABLE.cite)
        }

        // Restored after withProfile exits
        assertEquals("Table 310.16", CodeRule.AMPACITY_TABLE.cite)
        assertEquals(ConductorSize.AWG_14, Code.book.values.minimumSizeCopper)

        // Restores cleanly on exception
        try {
            Code.withProfile(customProfile) {
                assertEquals("Custom Table 310.16", CodeRule.AMPACITY_TABLE.cite)
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals("Table 310.16", CodeRule.AMPACITY_TABLE.cite)

        // Global profile applies across threads unless overridden by thread-local withProfile
        val globalOverride = CodeProfile(
            regionId = CodeRegions.CALIFORNIA_ID,
            customAmendment = RegionalAmendment(
                regionId = CodeRegions.CALIFORNIA_ID,
                citationOverrides = mapOf(CodeRule.WORKING_SPACE to "CEC Table 110.26(A)(1)"),
            ),
        )
        Code.setGlobalProfile(globalOverride)
        assertEquals("CEC Table 110.26(A)(1)", CodeRule.WORKING_SPACE.cite)
        assertEquals(CodeRegions.CALIFORNIA, Code.region)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val t1 = executor.submit(Callable {
                Code.withProfile(customProfile) {
                    Thread.sleep(20)
                    CodeRule.AMPACITY_TABLE.cite to CodeRule.WORKING_SPACE.cite
                }
            })
            val t2 = executor.submit(Callable {
                Thread.sleep(20)
                CodeRule.AMPACITY_TABLE.cite to CodeRule.WORKING_SPACE.cite
            })
            assertEquals("Custom Table 310.16" to "Table 110.26(A)(1)", t1.get())
            assertEquals("Table 310.16" to "CEC Table 110.26(A)(1)", t2.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
