package com.thedirepack.constructioncalc.core.model.design

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitRef
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectAnalysisTest {

    @Test fun phasesAndPlacement() {
        assertEquals(listOf(Phase.A, Phase.A, Phase.B, Phase.B, Phase.C, Phase.C, Phase.A), (1..7).map { PanelLayout.phaseOf(it, 3) })
        assertEquals(listOf(Phase.A, Phase.B, Phase.A), listOf(1, 3, 5).map { PanelLayout.phaseOf(it, 1) })
        val panel = Panel(name = "LP", spaces = 42, circuits = listOf(Circuit(slot = 1, poles = 2)))
        assertFalse(PanelLayout.canPlace(panel, 3, 1))
        assertTrue(PanelLayout.canPlace(panel, 2, 1))
        assertFalse(PanelLayout.canPlace(panel, 41, 3)) // 41, 43, 45: past 42
        assertEquals(2, PanelLayout.firstFree(panel, 1))
        val clash = panel.copy(circuits = panel.circuits + Circuit(slot = 3))
        assertTrue(PanelLayout.conflicts(clash).single().contains("overlaps"))
    }

    @Test fun sharedRunDeratesEveryCircuitAndSizesTheConduit() {
        val run = Run(name = "Home run", raceway = Raceway(ConduitType.EMT))
        // Six 1-pole 20 A circuits with neutrals: 12 current-carrying conductors → 50%.
        val circuits = (0 until 6).map { Circuit(slot = 1 + 2 * it, breakerAmps = 20, runIds = listOf(run.id)) }
        val project = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = circuits)))
        val design = ProjectAnalysis.analyze(project)
        circuits.forEach { c ->
            val d = design.circuits.getValue(c.id)
            assertEquals(12, d.currentCarrying)
            assertEquals(ConductorSize.AWG_10, d.size) // 12 AWG: 30 × 0.5 = 15 A < 20 A
            assertEquals(ConductorSize.AWG_12, d.groundSize)
        }
        val r = design.runs.getValue(run.id)
        assertEquals(13, r.totalConductors) // 12 + one shared EGC
        assertEquals(12 * 0.0211 + 0.0133, r.totalAreaSqIn, 1e-6)
        assertEquals(TradeSize.S_1, r.smallestFitting!!.tradeSize) // 3/4" EMT 40% = 0.213, 1" = 0.346
        assertEquals(0.5, r.adjustmentFactor, 0.0)
        assertEquals(FillStatus.OK, r.fillStatus) // auto-sized
        // A chosen 3/4" EMT is over the 40% limit.
        val tight = project.copy(runs = listOf(run.copy(raceway = Raceway(ConduitType.EMT, tradeSize = TradeSize.S_3_4))))
        assertEquals(FillStatus.OVER, ProjectAnalysis.analyze(tight).runs.getValue(run.id).fillStatus)
        // Rooftop run in the sun: 35 + 33 = 68 °C → 0.58 on the 90 °C column.
        val roof = project.copy(runs = listOf(run.copy(raceway = Raceway(ConduitType.EMT, ambientC = 35.0, rooftop = true))))
        val rr = ProjectAnalysis.analyze(roof).runs.getValue(run.id)
        assertEquals(68.0, rr.ambientC, 0.0)
        assertEquals(0.58, rr.ambientFactor!!, 0.0)
    }

    @Test fun voltageDropUpsizesWireAndGround() {
        val run = Run(name = "To RTU", raceway = Raceway(ConduitType.EMT, length = Quantity.feet(Rational.of(150)).stored()))
        val c = Circuit(slot = 1, breakerAmps = 20, loadVa = 1440.0, runIds = listOf(run.id)) // 12 A at 120 V
        val project = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = listOf(c))))
        val d = ProjectAnalysis.analyze(project).circuits.getValue(c.id)
        assertEquals(12.0, d.amps!!, 1e-9)
        assertEquals(ConductorSize.AWG_12, d.minimumSize)
        // Steel conduit Z at 0.85 PF: 12 AWG 1.736 → 5.2%; 10 AWG 1.053 → 3.2%; 8 AWG 0.697 → 2.1%.
        assertEquals(ConductorSize.AWG_8, d.size)
        assertEquals(2.09, d.voltageDropPercent!!, 0.01)
        assertEquals(ConductorSize.AWG_8, d.groundSize) // 250.122(B): 12 × (16510/6530 cmil) → 8 AWG
    }

    @Test fun mixedRacewayDropAndUpsizingAreIndependentOfRunOrder() {
        val length = Quantity.feet(Rational.of(75)).stored()
        val emt = Run(name = "EMT", raceway = Raceway(ConduitType.EMT, length = length))
        val pvc = Run(name = "PVC", raceway = Raceway(ConduitType.PVC_40, length = length))
        val circuit = Circuit(slot = 1, loadVa = 1440.0, runIds = listOf(emt.id, pvc.id))
        val panel = Panel(name = "LP", circuits = listOf(circuit))
        val project = Project(name = "P", createdAt = 0, runs = listOf(emt, pvc), panels = listOf(panel))
        fun analyze(p: Project, target: Double = 2.08) = ProjectAnalysis.analyze(p.copy(
            design = p.design.copy(voltageDropTargetPercent = target),
        )).circuits.getValue(circuit.id)
        val forward = analyze(project)
        val reversed = analyze(project.copy(panels = listOf(panel.copy(circuits = listOf(circuit.copy(runIds = circuit.runIds.reversed()))))))
        assertEquals(forward.voltageDropPercent!!, reversed.voltageDropPercent!!, 1e-9)
        assertEquals(ConductorSize.AWG_6, forward.size)
        assertEquals(forward.size, reversed.size)
        assertTrue(forward.voltageDropPercent <= 2.08)
        val relaxed = analyze(project, target = 2.085)
        // Equal lengths, with 8 AWG copper resistance 0.78 and average reactance (0.052 + 0.065) / 2.
        val expectedPercent = 3.0 * (0.78 * 0.85 + 0.0585 * Math.sqrt(1 - 0.85 * 0.85))
        assertEquals(ConductorSize.AWG_8, relaxed.size)
        assertEquals(expectedPercent, relaxed.voltageDropPercent!!, 1e-9)

        val withOverride = project.copy(panels = listOf(panel.copy(circuits = listOf(circuit.copy(length = length)))))
        val withoutPvcLength = withOverride.copy(runs = listOf(emt, pvc.copy(raceway = Raceway(ConduitType.PVC_40))))
        assertNull(analyze(withoutPvcLength).voltageDropVolts)
        assertTrue(analyze(withoutPvcLength).notes.any { "mixed raceways" in it.text })
    }

    @Test fun fillIsIncompleteWhenAnAssignedCircuitCannotBeSized() {
        val shared = Run(name = "Shared", raceway = Raceway(ConduitType.EMT))
        val hot = Run(name = "Hot", raceway = Raceway(ConduitType.EMT, ambientC = 100.0))
        val circuits = listOf(
            Circuit(slot = 1, runIds = listOf(shared.id)),
            Circuit(slot = 2, poles = 3, runIds = listOf(shared.id, hot.id)),
        )
        val project = Project(name = "P", createdAt = 0, runs = listOf(shared, hot), panels = listOf(Panel(name = "LP", circuits = circuits)))
        val result = ProjectAnalysis.analyze(project).runs.getValue(shared.id)
        assertEquals(FillStatus.INCOMPLETE, result.fillStatus)
        assertEquals(3, result.totalConductors)
        assertNull(result.fillPercent)
        assertNull(result.smallestFitting)
        assertTrue(result.notes.any { "no calculated conductor size" in it.text })
    }

    @Test fun panelPhaseBalance() {
        val panel = Panel(
            name = "LP",
            system = PanelSystem.WYE_208_120,
            circuits = listOf(Circuit(slot = 1, loadVa = 1200.0), Circuit(slot = 2, poles = 3, breakerAmps = 30, loadVa = 9000.0)),
        )
        val p = ProjectAnalysis.analyze(Project(name = "P", createdAt = 0, panels = listOf(panel))).panels.single()
        assertEquals(4200.0, p.phaseVa.getValue(Phase.A), 1e-9)
        assertEquals(3000.0, p.phaseVa.getValue(Phase.B), 1e-9)
        assertEquals(10.0 + 9000.0 / (Math.sqrt(3.0) * 208), p.phaseAmps.getValue(Phase.A), 1e-9)
        assertEquals(10200.0, p.totalVa, 1e-9)
    }

    @Test fun removingARunUnassignsItFromCircuits() {
        val run = Run(name = "R")
        val c = Circuit(slot = 1, runIds = listOf(run.id))
        val p = Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = listOf(c))))
        val after = p.removeRun(run.id, 1)
        assertTrue(after.panels.single().circuits.single().runIds.isEmpty())
    }

    @Test fun rooftopAdderBelongsToTheRooftopRunOnly() {
        // Indoor run at 40 °C plus a rooftop run at 30 °C outdoors: the hottest is the roof, 30 + 33 = 63 °C,
        // not 40 + 33 = 73 °C.
        val indoor = Run(name = "Boiler room", raceway = Raceway(ConduitType.EMT, ambientC = 40.0))
        val roof = Run(name = "Roof", raceway = Raceway(ConduitType.EMT, ambientC = 30.0, rooftop = true))
        val c = Circuit(slot = 1, breakerAmps = 20, runIds = listOf(indoor.id, roof.id))
        val project = Project(name = "P", createdAt = 0, runs = listOf(indoor, roof), panels = listOf(Panel(name = "LP", circuits = listOf(c))))
        val d = ProjectAnalysis.analyze(project).circuits.getValue(c.id)
        assertEquals(63.0, d.sizing!!.effectiveAmbientC, 0.0)
        assertTrue(d.notes.any { "Roof" in it.text && "+60 °F" in it.text && !it.isWarning })
    }

    @Test fun aCoolerRunAmbientIsHonoredLikeTheRunsTabShowsIt() {
        val vault = Run(name = "Underground", raceway = Raceway(ConduitType.PVC_40, ambientC = 20.0))
        val c = Circuit(slot = 1, breakerAmps = 20, runIds = listOf(vault.id))
        val project = Project(name = "P", createdAt = 0, runs = listOf(vault), panels = listOf(Panel(name = "LP", circuits = listOf(c))))
        val design = ProjectAnalysis.analyze(project)
        assertEquals(20.0, design.circuits.getValue(c.id).sizing!!.effectiveAmbientC, 0.0)
        assertEquals(design.runs.getValue(vault.id).ambientC, design.circuits.getValue(c.id).sizing!!.effectiveAmbientC, 0.0)
    }

    @Test fun feederCarriesTheLoadOfThePanelItFeeds() {
        // MDP feeds LP through a 3-pole 100 A breaker with no load of its own.
        val feeder = Circuit(slot = 1, poles = 3, breakerAmps = 100, neutral = true)
        val mdp = Panel(name = "MDP", circuits = listOf(feeder))
        val lp = Panel(
            name = "LP",
            fedFrom = CircuitRef(mdp.id, feeder.id),
            circuits = listOf(
                Circuit(slot = 1, loadVa = 12_000.0, continuous = true),
                Circuit(slot = 2, poles = 3, breakerAmps = 50, loadVa = 18_000.0),
            ),
        )
        val design = ProjectAnalysis.analyze(Project(name = "P", createdAt = 0, panels = listOf(mdp, lp)))
        val d = design.circuits.getValue(feeder.id)
        assertEquals(30_000.0, d.loadVa!!, 1e-9)
        assertTrue(d.loadFromFedPanels)
        assertEquals(100.0 + 18_000.0 / (Math.sqrt(3.0) * 208), d.amps!!, 1e-9)
        assertTrue(d.notes.any { it.text.startsWith("Feeder load from LP") })
        // The busiest leg carries about 150 A, of which 100 A is continuous.
        assertEquals(ConductorSize.AWG_2_0, d.minimumSize)
        // The upstream panel's totals include the subpanel.
        assertEquals(30_000.0, design.panels.first { it.panel.id == mdp.id }.totalVa, 1e-9)
        // An entered load on the feeder wins (the user may have applied demand factors).
        val entered = Project(name = "P", createdAt = 0, panels = listOf(mdp.copy(circuits = listOf(feeder.copy(loadVa = 20_000.0))), lp))
        val e = ProjectAnalysis.analyze(entered).circuits.getValue(feeder.id)
        assertEquals(20_000.0, e.loadVa!!, 1e-9)
        assertFalse(e.loadFromFedPanels)
    }

    @Test fun unbalancedFeederMapsDownstreamPhaseToTheCorrespondingUpstreamPole() {
        val feeder = Circuit(slot = 3, poles = 3, breakerAmps = 60)
        val main = Panel(name = "Main", busAmps = 50, circuits = listOf(feeder))
        val downstream = Panel(
            name = "LP",
            fedFrom = CircuitRef(main.id, feeder.id),
            circuits = listOf(Circuit(slot = 1, loadVa = 7200.0)),
        )
        val project = Project(name = "P", createdAt = 0, panels = listOf(main, downstream))
        val result = ProjectAnalysis.analyze(project)
        assertEquals(60.0, result.circuits.getValue(feeder.id).amps!!, 1e-9)
        val upstream = result.panels.first()
        assertEquals(mapOf(Phase.A to 0.0, Phase.B to 60.0, Phase.C to 0.0), upstream.phaseAmps)
        assertTrue(upstream.notes.any { "exceeds the 50 A bus" in it.text && it.isWarning })
    }

    @Test fun feederTerminalLoadCanBeGovernedByADifferentPhaseThanPeakOperatingCurrent() {
        val feeder = Circuit(slot = 1, poles = 3, breakerAmps = 60)
        val main = Panel(name = "Main", circuits = listOf(feeder))
        val downstream = Panel(
            name = "LP",
            fedFrom = CircuitRef(main.id, feeder.id),
            circuits = listOf(
                Circuit(slot = 1, loadVa = 7680.0),
                Circuit(slot = 3, loadVa = 7200.0, continuous = true),
            ),
        )
        val project = Project(name = "P", createdAt = 0, panels = listOf(main, downstream))
        val result = ProjectAnalysis.analyze(project).circuits.getValue(feeder.id)
        assertEquals(64.0, result.amps!!, 1e-9)
        // Phase B requires 75 A at the terminals despite Phase A having the larger operating load.
        assertEquals(ConductorSize.AWG_4, result.minimumSize)
    }

    @Test fun feedLoopsInADamagedFileDontHang() {
        val a = Circuit(slot = 1, poles = 3, breakerAmps = 100)
        val b = Circuit(slot = 1, poles = 3, breakerAmps = 100)
        val pa = Panel(name = "A", circuits = listOf(a))
        val pb = Panel(name = "B", circuits = listOf(b), fedFrom = CircuitRef(pa.id, a.id))
        val loop = Project(name = "P", createdAt = 0, panels = listOf(pa.copy(fedFrom = CircuitRef(pb.id, b.id)), pb))
        ProjectAnalysis.analyze(loop) // must return
    }

    @Test fun runFillUsesTheSameNote7RoundingAsConduitFill() {
        // The engine's run fill must be exactly the Conduit Fill tool's check (Note 7 rounding included).
        val run = Run(name = "Home run", raceway = Raceway(ConduitType.EMT, tradeSize = TradeSize.S_1_1_4))
        val c = (0 until 15).map { Circuit(slot = 1 + 2 * it, poles = 1, breakerAmps = 20, neutral = true, runIds = listOf(run.id)) }
        val panel = Panel(name = "LP", spaces = 42, circuits = c)
        val design = ProjectAnalysis.analyze(Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(panel)))
        val r = design.runs.getValue(run.id)
        val analysis = com.thedirepack.constructioncalc.core.electrical.ConduitFill.analyze(
            r.conduit!!,
            r.entries.map { com.thedirepack.constructioncalc.core.electrical.ConductorGroup(it.count, com.thedirepack.constructioncalc.core.electrical.FillConductor.Insulated(it.insulation, it.size)) },
        )
        assertEquals(analysis.percentFill, r.fillPercent!!, 1e-9)
        assertEquals(!analysis.withinLimit, r.fillStatus == FillStatus.OVER)
    }

    @Test fun aTradeSizeTheTypeDoesntHaveIsReported() {
        val run = Run(name = "R", raceway = Raceway(ConduitType.PVC_EB, tradeSize = TradeSize.S_1_2))
        val c = Circuit(slot = 1, breakerAmps = 20, runIds = listOf(run.id))
        val r = ProjectAnalysis.analyze(Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(Panel(name = "LP", circuits = listOf(c))))).runs.getValue(run.id)
        assertTrue(r.notes.any { it.isWarning && "no 1/2\"" in it.text })
    }

    @Test fun savedBendsAddUpPerRunAndWarnPast360() {
        fun bend(deg: Double) = com.thedirepack.constructioncalc.core.model.ProjectItem(
            toolId = ProjectAnalysis.BEND_TOOL_ID, title = "Bend", createdAt = 0,
            results = listOf(com.thedirepack.constructioncalc.core.model.ResultValue.number(ProjectAnalysis.BEND_DEGREES_KEY, "Total bend", com.thedirepack.constructioncalc.core.model.ValueKind.ANGLE, deg, "$deg°")),
        )
        val run = Run(name = "R", raceway = Raceway(ConduitType.EMT), items = listOf(bend(90.0), bend(90.0), bend(60.0)))
        val ok = ProjectAnalysis.analyze(Project(name = "P", createdAt = 0, runs = listOf(run))).runs.getValue(run.id)
        assertEquals(240.0, ok.bendDegrees!!, 1e-9)
        assertFalse(ok.notes.any { "360" in it.text })
        val tooMany = run.copy(items = run.items + bend(180.0))
        val over = ProjectAnalysis.analyze(Project(name = "P", createdAt = 0, runs = listOf(tooMany))).runs.getValue(run.id)
        assertTrue(over.notes.any { it.isWarning && "360" in it.text })
    }

    @Test fun fillEntriesKnowWhichConductorsCarryCurrent() {
        val run = Run(name = "R", raceway = Raceway(ConduitType.EMT))
        // 2-pole + neutral on 120/240 V: the neutral carries only unbalanced current (310.15(E)(1)).
        val c = Circuit(slot = 1, poles = 2, breakerAmps = 30, neutral = true, runIds = listOf(run.id))
        val panel = Panel(name = "LP", system = PanelSystem.SINGLE_120_240, circuits = listOf(c))
        val r = ProjectAnalysis.analyze(Project(name = "P", createdAt = 0, runs = listOf(run), panels = listOf(panel))).runs.getValue(run.id)
        val wires = r.entries.single { !it.ground }
        assertEquals(3, wires.count)
        assertEquals(2, wires.currentCarrying)
        assertEquals(0, r.entries.single { it.ground }.currentCarrying)
    }
}
