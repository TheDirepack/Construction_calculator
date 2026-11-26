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
import org.junit.Assert.assertNull
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
    fun onlyNec2023HasDataAndOtherEditionsFallBackExplicitly() {
        assertTrue(CodeEdition.NEC_2023.isImplemented)
        assertFalse(CodeEdition.NEC_2026.isImplemented)
        assertFalse(CodeEdition.NEC_2020.isImplemented)
        assertTrue(CodeEdition.NEC_2026.statusNote.contains("NEC 2023 values"))

        val resolution = Code.resolution(CodeProfile(edition = CodeEdition.NEC_2026))
        assertSame(Nec2023, resolution.book)
        assertEquals(CodeEdition.NEC_2026, resolution.selectedEdition)
        assertEquals(CodeEdition.NEC_2023, resolution.dataEdition)
        assertTrue(resolution.usesFallbackEdition)
        // Never silent: the label and the notices both name the substitution.
        assertEquals("NEC 2023 values (NEC 2026 not in app)", resolution.label)
        assertTrue(resolution.notices.single().startsWith("NEC 2026 isn't in the app yet"))

        Code.setGlobalProfile(CodeProfile(edition = CodeEdition.NEC_2026))
        assertEquals("NEC 2023 values (NEC 2026 not in app)", Code.label)
        assertEquals(CodeEdition.NEC_2023, Code.edition)
    }

    @Test
    fun defaultProfileResolvesDirectlyToNec2023() {
        assertEquals(CodeProfile(CodeEdition.NEC_2023, CodeRegions.STANDARD_ID), Code.globalProfile)
        assertSame(Nec2023, Code.resolve(CodeProfile()))
        assertSame(Nec2023, Code.book)
        val resolution = Code.current
        assertFalse(resolution.usesFallbackEdition)
        assertFalse(resolution.isAmended)
        assertTrue(resolution.notices.isEmpty())
        assertEquals("NEC 2023", resolution.label)
        assertEquals("NEC 2023, as published, no local amendments", resolution.summary)
    }

    @Test
    fun jurisdictionsWithoutEnteredAmendmentsResolveToTheirBaseEditionAndSaySo() {
        assertEquals(CodeRegions.STANDARD, CodeRegions.find(CodeRegions.STANDARD_ID))
        assertEquals(CodeRegions.STANDARD, CodeRegions.getOrDefault("nonexistent-region"))
        assertFalse("Custom isn't a pickable jurisdiction", CodeRegions.CUSTOM in CodeRegions.entries)
        assertNotNull(CodeRegions.find(CodeRegions.CUSTOM_ID))

        CodeRegions.entries.filterNot { it.isNational }.forEach { region ->
            assertFalse("${region.id} must not carry unverified amendments", region.hasOverrides)
            val resolution = Code.resolution(CodeProfile(edition = region.baseEdition, regionId = region.id))
            assertSame(Nec2023, resolution.book)
            assertEquals(region, resolution.jurisdiction)
            assertEquals("${region.name} · ${region.baseEdition.label}, no local amendments entered", resolution.summary)
            assertEquals("NEC 2023 · ${region.shortLabel}", resolution.label)
            assertTrue(resolution.notices.isEmpty())
        }
    }

    @Test
    fun jurisdictionAmendmentWinsAndEverythingElseFallsThroughToItsBaseEdition() {
        val amendment = RegionalAmendment(
            regionId = "test",
            label = "Test amendment",
            citationOverrides = mapOf(CodeRule.MINIMUM_CONDUCTOR_SIZE to "Local 310.3(A)"),
            valuesTransform = { it.copy(minimumSizeCopper = ConductorSize.AWG_12) },
            valueRules = setOf(CodeRule.MINIMUM_CONDUCTOR_SIZE),
        )
        val region = CodeRegion(
            id = "test",
            name = "Testland",
            shortLabel = "TL",
            baseEdition = CodeEdition.NEC_2023,
            amendments = mapOf(CodeEdition.NEC_2023 to amendment),
        )
        assertTrue(region.hasOverrides)
        assertSame(amendment, region.amendmentFor(CodeEdition.NEC_2023))
        assertNull(region.amendmentFor(CodeEdition.NEC_2026))

        val book = AmendedElectricalCode(Nec2023, amendment)
        // Amendment first…
        assertEquals("Local 310.3(A)", book.cite(CodeRule.MINIMUM_CONDUCTOR_SIZE))
        assertEquals(ConductorSize.AWG_12, book.values.minimumSizeCopper)
        // …then the base edition for everything it doesn't touch.
        assertEquals(Nec2023.cite(CodeRule.AMPACITY_TABLE), book.cite(CodeRule.AMPACITY_TABLE))
        assertEquals(Nec2023.values.minimumSizeAluminum, book.values.minimumSizeAluminum)
        assertSame(Nec2023.ampacity, book.ampacity)
        assertSame(Nec2023.loadCalculation, book.loadCalculation)
    }

    @Test
    fun resolutionReportsWhichRulesComeFromAnAmendment() {
        val profile = CodeProfile(
            regionId = CodeRegions.CALIFORNIA_ID,
            customAmendment = RegionalAmendment(
                label = "Project spec",
                citationOverrides = mapOf(CodeRule.VOLTAGE_DROP_BRANCH to "Spec 26 05 19"),
                valuesTransform = { it.copy(voltageDropBranchPercent = 2.0) },
                valueRules = setOf(CodeRule.VOLTAGE_DROP_BRANCH),
            ),
        )
        val resolution = Code.resolution(profile)
        assertTrue(resolution.isAmended)
        assertTrue(resolution.isAmended(CodeRule.VOLTAGE_DROP_BRANCH))
        assertFalse(resolution.isAmended(CodeRule.AMPACITY_TABLE))
        assertEquals("Project spec", resolution.sourceOf(CodeRule.VOLTAGE_DROP_BRANCH))
        assertEquals("NEC 2023", resolution.sourceOf(CodeRule.AMPACITY_TABLE))
        assertEquals("Spec 26 05 19 (Project spec)", resolution.citeWithSource(CodeRule.VOLTAGE_DROP_BRANCH))
        assertEquals("Table 310.16", resolution.citeWithSource(CodeRule.AMPACITY_TABLE))
        assertEquals(2.0, resolution.book.values.voltageDropBranchPercent, 0.0)
        assertEquals("NEC 2023 · CA amended", resolution.label)
        assertEquals("California (CEC) · NEC 2023, with Project spec (1 rules)", resolution.summary)
    }

    @Test
    fun pickingAnEditionOtherThanTheAdoptedOneIsFlagged() {
        val resolution = Code.resolution(CodeProfile(edition = CodeEdition.NEC_2026, regionId = CodeRegions.WASHINGTON_ID))
        assertEquals(2, resolution.notices.size)
        assertTrue(resolution.notices.any { it.contains("adopted NEC 2023; you picked NEC 2026") })
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
