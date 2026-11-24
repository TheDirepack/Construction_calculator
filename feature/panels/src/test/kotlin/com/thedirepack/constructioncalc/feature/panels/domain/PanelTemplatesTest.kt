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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelTemplatesTest {

    @Test
    fun builtInTemplatesHaveExpectedStarterDefaultsAndZeroConflictsOrWarnings() {
        assertEquals(6, BuiltInPanelTemplates.all.size)
        val blank = BuiltInPanelTemplates.all.first()
        assertEquals("Blank 208Y/120 V", blank.name)
        assertEquals(PanelSystem.WYE_208_120, blank.system)
        assertEquals(42, blank.spaces)
        assertEquals(225, blank.busAmps)
        assertNull(blank.mainAmps)
        assertTrue(blank.circuits.isEmpty())

        BuiltInPanelTemplates.all.forEach { template ->
            assertTrue("Expected builtIn=true for ${template.name}", template.builtIn)
            val panel = template.instantiate("Test ${template.name}")
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

    @Test
    fun fromPanelStripsProjectLinksAndInstantiateGeneratesFreshIds() {
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

        val template = PanelTemplate.fromPanel(source, "  Custom Service ")
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

    @Test
    fun instantiateSkipsOverlappingOutOfBoundsAndIncompatibleSlots() {
        val messy = PanelTemplate(
            id = "messy",
            name = "Messy",
            system = PanelSystem.SINGLE_120_240,
            spaces = 6,
            circuits = listOf(
                TemplateCircuit(slot = 1, poles = 2, breakerAmps = 30, description = "Valid 2P"), // takes 1, 3
                TemplateCircuit(slot = 3, poles = 1, breakerAmps = 20, description = "Overlaps 2P"),
                TemplateCircuit(slot = 5, poles = 2, breakerAmps = 20, description = "Runs past 6"),
                TemplateCircuit(slot = 2, poles = 3, breakerAmps = 40, description = "3P on 1-phase"),
                TemplateCircuit(slot = 2, poles = 1, breakerAmps = 20, description = "Valid 1P"),
                TemplateCircuit(slot = 9, poles = 1, breakerAmps = 20, description = "Past end"),
            ),
        )

        val panel = messy.instantiate("Sub-1")
        assertEquals(listOf(1, 2), panel.circuits.map { it.slot })
        assertTrue(ScheduleLayout.build(panel).unplaced.isEmpty())
        assertTrue(PanelLayout.conflicts(panel).isEmpty())

        val delta = PanelTemplate(
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

    @Test
    fun codecRoundTripsTemplatesAndFallsBackOnBlankOrInvalidJson() {
        val templates = listOf(
            PanelTemplate(
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

        assertEquals(emptyList<PanelTemplate>(), PanelTemplatesCodec.encode(emptyList()).let(PanelTemplatesCodec::decode))
        assertEquals(emptyList<PanelTemplate>(), PanelTemplatesCodec.decode(""))
        assertEquals(emptyList<PanelTemplate>(), PanelTemplatesCodec.decode("   "))
        assertEquals(emptyList<PanelTemplate>(), PanelTemplatesCodec.decode("{not-valid-json"))
    }

    @Test
    fun exportLayoutFromNameResolvesEveryValueAndFallsBack() {
        PanelExportLayout.entries.forEach { layout ->
            assertEquals(layout, PanelExportLayout.fromName(layout.name))
        }
        assertEquals(PanelExportLayout.BRANCH_STANDARD, PanelExportLayout.fromName(null))
        assertEquals(PanelExportLayout.BRANCH_STANDARD, PanelExportLayout.fromName(""))
        assertEquals(PanelExportLayout.BRANCH_STANDARD, PanelExportLayout.fromName("NON_EXISTENT"))
    }
}
