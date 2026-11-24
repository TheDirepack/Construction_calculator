package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.CircuitRef
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelTemplatesTest {

    // --- Wave 2B bus-locked standard panel templates ---

    @Test fun includesAllExpectedTemplatesWithAccurateDefaults() {
        assertEquals(
            listOf(
                PanelTemplates.RESIDENTIAL_120_240,
                PanelTemplates.COMMERCIAL_208Y_120,
                PanelTemplates.COMMERCIAL_480Y_277,
                PanelTemplates.DELTA_240,
                PanelTemplates.DELTA_480,
            ),
            PanelTemplates.all,
        )

        val res = PanelTemplates.RESIDENTIAL_120_240
        assertEquals("Residential 120/240V 1φ (L1/L2)", res.label)
        assertEquals(PanelSystem.SINGLE_120_240, res.system)
        assertEquals(40, res.defaultSpaces)
        assertEquals(200, res.defaultBusAmps)
        assertEquals(200, res.defaultMainAmps)
        assertEquals(4, res.spaceStep)
        assertEquals(Panel.validSpaceCounts(1), res.validSpaceCounts())
        assertTrue(res.validSpaceCounts().all { it % 4 == 0 })

        val com208 = PanelTemplates.COMMERCIAL_208Y_120
        assertEquals("Commercial 208Y/120V 3φ (A/B/C)", com208.label)
        assertEquals(PanelSystem.WYE_208_120, com208.system)
        assertEquals(42, com208.defaultSpaces)
        assertEquals(225, com208.defaultBusAmps)
        assertNull(com208.defaultMainAmps)
        assertEquals(6, com208.spaceStep)
        assertEquals(Panel.validSpaceCounts(3), com208.validSpaceCounts())
        assertTrue(com208.validSpaceCounts().all { it % 6 == 0 })

        val com480 = PanelTemplates.COMMERCIAL_480Y_277
        assertEquals("Commercial 480Y/277V 3φ (A/B/C)", com480.label)
        assertEquals(PanelSystem.WYE_480_277, com480.system)
        assertEquals(42, com480.defaultSpaces)
        assertEquals(225, com480.defaultBusAmps)
        assertNull(com480.defaultMainAmps)

        assertEquals(PanelSystem.DELTA_240, PanelTemplates.DELTA_240.system)
        assertEquals(6, PanelTemplates.DELTA_240.spaceStep)
        assertEquals(Panel.validSpaceCounts(3), PanelTemplates.DELTA_240.validSpaceCounts())

        assertEquals(PanelSystem.DELTA_480, PanelTemplates.DELTA_480.system)
        assertEquals(6, PanelTemplates.DELTA_480.spaceStep)
        assertEquals(Panel.validSpaceCounts(3), PanelTemplates.DELTA_480.validSpaceCounts())
    }

    @Test fun createPanelSnapsSpacesToBusAndNormalizesName() {
        val resPanel = PanelTemplates.RESIDENTIAL_120_240.createPanel("  MDP-1 ")
        assertEquals("MDP-1", resPanel.name)
        assertEquals(PanelSystem.SINGLE_120_240, resPanel.system)
        assertEquals(40, resPanel.spaces)
        assertEquals(200, resPanel.busAmps)
        assertEquals(200, resPanel.mainAmps)

        // Arbitrary spaces passed into createPanel snap to the template's bus step.
        val snapped1Ph = PanelTemplates.RESIDENTIAL_120_240.createPanel("", spaces = 14)
        assertEquals("Panel", snapped1Ph.name)
        assertEquals(Panel.snapSpacesToBus(14, 1), snapped1Ph.spaces)
        assertTrue(snapped1Ph.spaces in Panel.validSpaceCounts(1))

        val snapped3Ph = PanelTemplates.DELTA_480.createPanel("DP-480", spaces = 20)
        assertEquals(Panel.snapSpacesToBus(20, 3), snapped3Ph.spaces)
        assertTrue(snapped3Ph.spaces in Panel.validSpaceCounts(3))
    }

    @Test fun findAndForSystemLookup() {
        assertNotNull(PanelTemplates.find(PanelTemplates.RESIDENTIAL_120_240.id))
        assertNull(PanelTemplates.find("nonexistent"))
        assertEquals(PanelTemplates.COMMERCIAL_480Y_277, PanelTemplates.forSystem(PanelSystem.WYE_480_277))
        assertEquals(PanelTemplates.DELTA_240, PanelTemplates.forSystem(PanelSystem.DELTA_240))
    }

    // --- IMP-06 reusable starter/custom saved templates & export layouts ---

    @Test fun builtInTemplatesHaveExpectedStarterDefaultsAndZeroConflictsOrWarnings() {
        assertEquals(6, BuiltInPanelTemplates.all.size)
        assertEquals(5, BuiltInPanelTemplates.schedules.size)
        val blank = BuiltInPanelTemplates.all.first()
        assertEquals("Blank 208Y/120 V", blank.name)
        assertEquals(PanelSystem.WYE_208_120, blank.system)
        assertEquals(42, blank.spaces)
        assertEquals(225, blank.busAmps)
        assertNull(blank.mainAmps)
        assertTrue(blank.circuits.isEmpty())

        BuiltInPanelTemplates.all.forEach { template ->
            assertTrue("Expected builtIn=true for ${template.name}", template.builtIn)
            assertTrue(
                "Expected bus-locked spaces for ${template.name}",
                template.spaces in Panel.validSpaceCounts(template.system.phases),
            )
            val panel = template.instantiate("Test ${template.name}")
            assertTrue(panel.spaces in Panel.validSpaceCounts(panel.system.phases))
            val schedule = ScheduleLayout.build(panel)
            assertTrue("Unplaced circuits in ${template.name}: ${schedule.unplaced}", schedule.unplaced.isEmpty())

            val project = Project(name = "Template Check", createdAt = 0L, panels = listOf(panel))
            val design = ProjectAnalysis.analyze(project)
            val pd = design.panels.single()
            assertTrue("Panel conflicts in ${template.name}: ${pd.conflicts}", pd.conflicts.isEmpty())
            assertTrue(
                "Panel warnings in ${template.name}: ${pd.notes.filter { it.isWarning }}",
                pd.notes.none { it.isWarning },
            )
            panel.circuits.filter { it.kind == CircuitKind.LOAD }.forEach { circuit ->
                val cd = design.circuits.getValue(circuit.id)
                assertTrue(
                    "Circuit warnings in ${template.name} slot ${circuit.slot}: ${cd.notes.filter { it.isWarning }}",
                    cd.notes.none { it.isWarning },
                )
                assertTrue("Expected sized conductor for ${template.name} slot ${circuit.slot}", cd.size != null)
            }
        }
    }

    @Test fun fromPanelStripsProjectLinksAndInstantiateGeneratesFreshIds() {
        val cLoad = Circuit(
            slot = 1,
            poles = 2,
            breakerAmps = 50,
            description = "Electric Range",
            kind = CircuitKind.LOAD,
            loadVa = 9600.0,
            continuous = false,
            neutral = true,
            nonlinear = false,
            groupIds = listOf("group-kitchen"),
            runIds = listOf("run-home"),
        )
        val cSpare = Circuit(
            slot = 2,
            poles = 1,
            breakerAmps = 20,
            kind = CircuitKind.SPARE,
            groupIds = listOf("group-other"),
            runIds = listOf("run-2"),
        )
        val cSpace = Circuit(
            slot = 4,
            poles = 1,
            breakerAmps = null,
            kind = CircuitKind.SPACE,
            neutral = false,
        )
        val source = Panel(
            name = "MDP-1",
            location = "Garage",
            system = PanelSystem.SINGLE_120_240,
            spaces = 24,
            busAmps = 200,
            mainAmps = 200,
            fedFrom = CircuitRef("upstream-panel", "upstream-ckt"),
            note = "Main service panel",
            circuits = listOf(cLoad, cSpare, cSpace),
        )

        val template = SavedPanelTemplate.fromPanel(source, "  Custom Service ")
        assertEquals("Custom Service", template.name)
        assertEquals(PanelTemplateCategory.CUSTOM, template.category)
        assertFalse(template.builtIn)
        assertEquals(source.system, template.system)
        assertEquals(source.spaces, template.spaces)
        assertEquals(source.busAmps, template.busAmps)
        assertEquals(source.mainAmps, template.mainAmps)
        assertEquals(source.note, template.note)
        assertEquals(3, template.circuits.size)

        val first = template.instantiate("Service A")
        val second = template.instantiate("Service B")

        assertNotEquals(source.id, first.id)
        assertNotEquals(source.id, second.id)
        assertNotEquals(first.id, second.id)
        assertNull(first.fedFrom)
        assertNull(second.fedFrom)
        assertEquals("Service A", first.name)
        assertEquals(3, first.circuits.size)

        val originalIds = source.circuits.map { it.id }.toSet()
        val firstIds = first.circuits.map { it.id }.toSet()
        val secondIds = second.circuits.map { it.id }.toSet()
        assertTrue(firstIds.intersect(originalIds).isEmpty())
        assertTrue(secondIds.intersect(originalIds).isEmpty())
        assertTrue(firstIds.intersect(secondIds).isEmpty())

        first.circuits.forEach { c ->
            assertTrue(c.runIds.isEmpty())
            assertTrue(c.groupIds.isEmpty())
        }

        val clonedLoad = first.circuits.first { it.slot == 1 }
        assertEquals(2, clonedLoad.poles)
        assertEquals(50, clonedLoad.breakerAmps)
        assertEquals("Electric Range", clonedLoad.description)
        assertEquals(9600.0, clonedLoad.loadVa!!, 1e-9)
        assertTrue(clonedLoad.neutral)

        val clonedSpace = first.circuits.first { it.slot == 4 }
        assertEquals(CircuitKind.SPACE, clonedSpace.kind)
        assertNull(clonedSpace.breakerAmps)
        assertNull(clonedSpace.loadVa)
        assertFalse(clonedSpace.neutral)
    }

    @Test fun customSavedTemplatesSnapSpacesToPhysicalBus() {
        // Legacy non-standard 1-phase panel (14 spaces) snaps to 16 (multiple of 4) on save & instantiate.
        val legacy1Ph = Panel(name = "Old-1P", system = PanelSystem.SINGLE_120_240, spaces = 14)
        val saved1Ph = SavedPanelTemplate.fromPanel(legacy1Ph, "Legacy 1P")
        assertEquals(16, saved1Ph.spaces)
        assertEquals(4, saved1Ph.spaceStep)
        assertEquals(Panel.validSpaceCounts(1), saved1Ph.validSpaceCounts())
        assertEquals(16, saved1Ph.instantiate("New-1P").spaces)

        // Legacy non-standard 3-phase panel (20 spaces) snaps to 18 (multiple of 6) on save & instantiate.
        val legacy3Ph = Panel(name = "Old-3P", system = PanelSystem.WYE_208_120, spaces = 20)
        val saved3Ph = SavedPanelTemplate.fromPanel(legacy3Ph, "Legacy 3P")
        assertEquals(18, saved3Ph.spaces)
        assertEquals(6, saved3Ph.spaceStep)
        assertEquals(Panel.validSpaceCounts(3), saved3Ph.validSpaceCounts())
        assertEquals(18, saved3Ph.instantiate("New-3P").spaces)

        // Hand-constructed or overridden spaces also snap to physical bus counts on instantiation.
        val custom = SavedPanelTemplate(
            id = "custom-snap",
            name = "Custom",
            system = PanelSystem.WYE_480_277,
            spaces = 22,
            circuits = listOf(
                TemplateCircuit(slot = 1, poles = 3, breakerAmps = 60, description = "Feeder 1"),
                TemplateCircuit(slot = 25, poles = 3, breakerAmps = 60, description = "Feeder 2"),
            ),
        )
        assertEquals(24, custom.snappedSpaces)
        assertEquals(1, custom.clippedCircuitCount(18))
        val resized = custom.instantiate("DP-18", spaces = 20) // 20 snaps to 18 for 3φ
        assertEquals(18, resized.spaces)
        assertEquals(listOf(1), resized.circuits.map { it.slot })
    }

    @Test fun instantiateSkipsOverlappingOutOfBoundsAndIncompatibleSlots() {
        val messy = SavedPanelTemplate(
            id = "messy",
            name = "Messy",
            system = PanelSystem.SINGLE_120_240,
            spaces = 8,
            circuits = listOf(
                TemplateCircuit(slot = 1, poles = 2, breakerAmps = 30, description = "Valid 2P"), // takes 1, 3
                TemplateCircuit(slot = 3, poles = 1, breakerAmps = 20, description = "Overlaps 2P"),
                TemplateCircuit(slot = 7, poles = 2, breakerAmps = 20, description = "Runs past 8"),
                TemplateCircuit(slot = 2, poles = 3, breakerAmps = 40, description = "3P on 1-phase"),
                TemplateCircuit(slot = 2, poles = 1, breakerAmps = 20, description = "Valid 1P"),
                TemplateCircuit(slot = 9, poles = 1, breakerAmps = 20, description = "Past end"),
            ),
        )

        val panel = messy.instantiate("Sub-1")
        assertEquals(8, panel.spaces)
        assertEquals(listOf(1, 2), panel.circuits.map { it.slot })
        assertTrue(ScheduleLayout.build(panel).unplaced.isEmpty())
        assertTrue(PanelLayout.conflicts(panel).isEmpty())

        val delta = SavedPanelTemplate(
            id = "delta",
            name = "Delta",
            system = PanelSystem.DELTA_480,
            spaces = 12,
            circuits = listOf(
                TemplateCircuit(slot = 1, poles = 1, breakerAmps = 20, kind = CircuitKind.LOAD, description = "Invalid 1P load"),
                TemplateCircuit(slot = 2, poles = 3, breakerAmps = 60, kind = CircuitKind.LOAD, description = "Valid 3P motor", neutral = true),
            ),
        ).instantiate("DP-Delta")
        assertEquals(listOf(2), delta.circuits.map { it.slot })
        assertFalse(delta.circuits.single().neutral)
        assertTrue(PanelLayout.conflicts(delta).isEmpty())
    }

    @Test fun codecRoundTripsTemplatesAndFallsBackOnBlankOrInvalidJson() {
        val templates = listOf(
            SavedPanelTemplate(
                id = "custom-1",
                name = "Tenant Fit-Out",
                category = PanelTemplateCategory.CUSTOM,
                system = PanelSystem.WYE_208_120,
                spaces = 42,
                busAmps = 225,
                mainAmps = null,
                circuits = listOf(
                    TemplateCircuit(1, poles = 1, breakerAmps = 20, kind = CircuitKind.LOAD, description = "Lighting", loadVa = 1440.0, continuous = true, neutral = true, nonlinear = true),
                    TemplateCircuit(2, poles = 2, breakerAmps = 40, kind = CircuitKind.LOAD, description = "Water Heater", loadVa = 6000.0, continuous = true, neutral = false),
                    TemplateCircuit(3, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
                    TemplateCircuit(4, poles = 1, breakerAmps = 20, kind = CircuitKind.SPACE),
                ),
                note = "Standard suite",
                builtIn = false,
            ),
            BuiltInPanelTemplates.DISTRIBUTION_FEEDER.copy(id = "custom-2", builtIn = false, category = PanelTemplateCategory.CUSTOM),
        )

        val json = PanelTemplatesCodec.encode(templates)
        val decoded = PanelTemplatesCodec.decode(json)
        assertEquals(templates, decoded)

        assertEquals(emptyList<SavedPanelTemplate>(), PanelTemplatesCodec.encode(emptyList()).let(PanelTemplatesCodec::decode))
        assertEquals(emptyList<SavedPanelTemplate>(), PanelTemplatesCodec.decode(""))
        assertEquals(emptyList<SavedPanelTemplate>(), PanelTemplatesCodec.decode("   "))
        assertEquals(emptyList<SavedPanelTemplate>(), PanelTemplatesCodec.decode("{not-valid-json"))
    }

    @Test fun exportLayoutFromNameResolvesEveryValueAndFallsBack() {
        assertEquals(3, PanelExportLayout.entries.size)
        PanelExportLayout.entries.forEach { layout ->
            assertEquals(layout, PanelExportLayout.fromName(layout.name))
            assertTrue(layout.label.isNotBlank())
            assertTrue(layout.summary.isNotBlank())
        }
        assertEquals(PanelExportLayout.BRANCH_STANDARD, PanelExportLayout.fromName(null))
        assertEquals(PanelExportLayout.BRANCH_STANDARD, PanelExportLayout.fromName(""))
        assertEquals(PanelExportLayout.BRANCH_STANDARD, PanelExportLayout.fromName("NON_EXISTENT"))
    }
}
