package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorMaterial
import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeEdition
import com.thedirepack.constructioncalc.core.electrical.code.CodeProfile
import com.thedirepack.constructioncalc.core.electrical.code.CodeRegions
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.code.RegionalAmendment
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.ConductorSpec
import com.thedirepack.constructioncalc.core.model.DesignDefaults
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.design.Phase
import com.thedirepack.constructioncalc.core.model.design.ProjectAnalysis
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitDesignCheckTest {

    @Test fun wireSummaryPillMatchesQuickEntryFormats() {
        val branch = Circuit(slot = 1, breakerAmps = 20, description = "Kitchen outlets")
        val panel = PanelTemplates.COMMERCIAL_208Y_120.createPanel("LP-1").copy(circuits = listOf(branch))
        val project = Project(name = "Job", createdAt = 0, panels = listOf(panel))
        val design = ProjectAnalysis.analyze(project)

        assertEquals(
            "#12 Cu · 1#12G · Phase A",
            circuitWireSummaryPill(panel, branch, design.circuits[branch.id]),
        )

        val twoPoleAl = Circuit(
            slot = 2,
            poles = 2,
            breakerAmps = 50,
            conductor = ConductorSpec(material = ConductorMaterial.ALUMINUM),
        )
        val threePhasePanel = panel.copy(circuits = listOf(twoPoleAl))
        val alDesign = ProjectAnalysis.analyze(project.copy(panels = listOf(threePhasePanel)))
        assertEquals(
            "#6 Al · 1#8G · Phases A/B",
            circuitWireSummaryPill(threePhasePanel, twoPoleAl, alDesign.circuits[twoPoleAl.id]),
        )

        val spare = Circuit(slot = 3, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE)
        assertEquals("Spare · 20A 1P · Phase B", circuitWireSummaryPill(panel, spare, null))

        val space = Circuit(slot = 5, poles = 1, breakerAmps = null, kind = CircuitKind.SPACE)
        assertEquals("Space · Phase C", circuitWireSummaryPill(panel, space, null))
    }

    @Test fun voltageDropUpsizeResolvesDropWarningImmediately() {
        val run = Run(
            name = "Long Home Run",
            raceway = Raceway(
                conduitType = ConduitType.EMT,
                length = Quantity.feet(Rational.of(150)).stored(),
            ),
        )
        val circuit = Circuit(
            slot = 1,
            breakerAmps = 20,
            loadVa = 1920.0, // 16 A at 120 V
            runIds = listOf(run.id),
        )
        val panel = PanelTemplates.RESIDENTIAL_120_240.createPanel("MDP").copy(circuits = listOf(circuit))
        val unUpsizedProject = Project(
            name = "VD Test",
            createdAt = 0,
            runs = listOf(run),
            panels = listOf(panel),
            design = DesignDefaults(upsizeForVoltageDrop = false),
        )

        val before = ProjectAnalysis.analyze(unUpsizedProject).circuits.getValue(circuit.id)
        val target = unUpsizedProject.design.voltageDropTargetPercent
        assertEquals(ConductorSize.AWG_12, before.size)
        assertNotNull(before.voltageDropPercent)
        assertTrue(before.voltageDropPercent!! > target)
        assertTrue(before.notes.any { it.isWarning && "Voltage drop" in it.text })

        val upsizedProject = unUpsizedProject.withUpsizeForVoltageDrop(true, now = 5L)
        val after = ProjectAnalysis.analyze(upsizedProject).circuits.getValue(circuit.id)
        assertTrue(after.size!! > before.size!!)
        assertTrue(after.voltageDropPercent!! <= target)
        assertFalse(after.notes.any { it.isWarning && "Voltage drop" in it.text })
    }

    @Test fun templatePanelsBuildBusLockedSchedulesAndRenderText() {
        val resPanel = PanelTemplates.RESIDENTIAL_120_240.createPanel("Home").copy(
            circuits = listOf(
                Circuit(slot = 1, breakerAmps = 20, description = "Lighting", loadVa = 960.0),
                Circuit(slot = 2, poles = 2, breakerAmps = 40, description = "Range", loadVa = 8000.0),
            ),
        )
        val schedule = ScheduleLayout.build(resPanel)
        assertEquals(20, schedule.rows.size)
        assertEquals(Phase.A, schedule.rows[0].phase)
        assertEquals(Phase.B, schedule.rows[1].phase)
        assertTrue(schedule.unplaced.isEmpty())

        val project = Project(name = "Residence", createdAt = 0, panels = listOf(resPanel))
        val text = ScheduleText.render(project, ProjectAnalysis.analyze(project), date = "Nov 24, 2026")
        assertTrue(text.contains("PANEL Home"))
        assertTrue(text.contains("40 spaces"))
        assertTrue(text.contains("200 A bus · 200 A main"))
    }

    @Test fun projectCodeEditionAndRegionOverrideAnalysis() {
        val originalGlobal = Code.globalProfile
        try {
            Code.setGlobalProfile(
                CodeProfile(
                    edition = CodeEdition.NEC_2023,
                    regionId = CodeRegions.CUSTOM_ID,
                    customAmendment = RegionalAmendment(
                        regionId = CodeRegions.CUSTOM_ID,
                        label = "Min #10 Cu",
                        citationOverrides = mapOf(CodeRule.MINIMUM_CONDUCTOR_SIZE to "Custom 310.3"),
                        valuesTransform = { it.copy(minimumSizeCopper = ConductorSize.AWG_10) },
                    ),
                ),
            )

            val circuit = Circuit(slot = 1, breakerAmps = 15)
            val panel = PanelTemplates.COMMERCIAL_208Y_120.createPanel("LP-1").copy(circuits = listOf(circuit))
            val inherited = Project(name = "Job", createdAt = 0, panels = listOf(panel))

            assertEquals(
                ConductorSize.AWG_10,
                ProjectAnalysis.analyze(inherited).circuits.getValue(circuit.id).minimumSize,
            )

            val overridden = inherited.copy(
                codeEdition = CodeEdition.NEC_2023.name,
                codeRegion = CodeRegions.STANDARD_ID,
            )
            assertEquals(
                ConductorSize.AWG_14,
                ProjectAnalysis.analyze(overridden).circuits.getValue(circuit.id).minimumSize,
            )

            // Unimplemented edition (NEC 2026) safely falls back to verified NEC 2023 tables.
            val plannedProfile = ProjectAnalysis.codeProfileFor(
                overridden.copy(codeEdition = CodeEdition.NEC_2026.name),
            )
            assertEquals(CodeEdition.NEC_2023, Code.resolve(plannedProfile).edition)
        } finally {
            Code.setGlobalProfile(originalGlobal)
        }
    }
}
