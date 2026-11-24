package com.thedirepack.constructioncalc.feature.pullplanner.domain

import com.thedirepack.constructioncalc.core.electrical.ConductorSize
import com.thedirepack.constructioncalc.core.electrical.ConduitType
import com.thedirepack.constructioncalc.core.electrical.InsulationType
import com.thedirepack.constructioncalc.core.electrical.PullFormulas
import com.thedirepack.constructioncalc.core.electrical.PullRatings
import com.thedirepack.constructioncalc.core.electrical.TradeSize
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.LengthFormat
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.UnitSystem
import com.thedirepack.constructioncalc.core.model.BendPlane
import com.thedirepack.constructioncalc.core.model.EquipmentRating
import com.thedirepack.constructioncalc.core.model.PathSegment
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import com.thedirepack.constructioncalc.core.model.Raceway
import com.thedirepack.constructioncalc.core.model.Run
import com.thedirepack.constructioncalc.core.model.design.PullAnalysis
import com.thedirepack.constructioncalc.core.model.design.PullDirection
import com.thedirepack.constructioncalc.core.model.design.PullText
import com.thedirepack.constructioncalc.core.model.stored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullReportModelTest {
    private fun ft(n: Long) = Quantity.feet(Rational.of(n)).stored()
    private fun inches(d: Double) = Quantity.inches(Rational.of(d)).stored()
    private fun lengthFormatter(units: UnitSystem): (Quantity) -> String = { q ->
        MeasureFormat.primary(q, LengthFormat(system = units))
    }

    // 1-1/4" EMT (ID 1.380 in.) with 3 x 0.5 in. cables produces jam ratio 1.05 * 1.380 / 0.5 = 2.898 (jam warning).
    private val multiSegmentPath = listOf(
        PathSegment.Straight(ft(80)),
        PathSegment.Bend(90.0, inches(18.0), BendPlane.HORIZONTAL),
        PathSegment.Straight(ft(30), 25.0),
        PathSegment.PullPoint("PB-1"),
        PathSegment.Bend(90.0, inches(24.0), BendPlane.VERTICAL_UP),
        PathSegment.Straight(ft(15), 90.0),
    )

    private fun sampleRun(
        equipment: List<EquipmentRating> = listOf(
            EquipmentRating("Rope", 800.0),
            EquipmentRating("Pulling eye or grip"),
            EquipmentRating("Swivel", 1500.0),
            EquipmentRating("Puller"),
        ),
        pressureLimit: Double? = null,
    ) = Run(
        name = "Feeder A",
        raceway = Raceway(ConduitType.EMT, TradeSize.S_1_1_4),
        path = multiSegmentPath,
        pull = PullSetup(
            cablesFromCircuits = false,
            incomingTension = listOf(50.0, 75.0),
            slackPerEnd = ft(3),
            cables = listOf(
                PullCable(
                    count = 3,
                    size = ConductorSize.AWG_2,
                    insulation = InsulationType.THHN,
                    weightLbPerFt = 0.3,
                    diameterIn = 0.5,
                ),
            ),
            equipment = equipment,
            pressureLimit = pressureLimit,
        ),
    )

    @Test fun multiSegmentPullPopulatesReportFieldsLimitsWarningsAndDirections() {
        val units = UnitSystem.IMPERIAL
        val result = PullAnalysis.analyze(sampleRun(), runDesign = null, units = units)
        val header = listOf("Acme Electric · Lic. #1234", "555-0100 · team@acme.example")
        val report = PullReportModel.build(
            result = result,
            projectName = "Main Building",
            companyHeader = header,
            date = "Nov 24, 2026",
            units = units,
            lengthText = lengthFormatter(units),
        )

        assertEquals("Wire Pull Report: Feeder A", report.title)
        assertEquals("Main Building · Nov 24, 2026", report.subtitle)
        assertEquals(header, report.companyHeader)
        assertEquals("Nov 24, 2026", report.date)
        assertEquals(PullText.summary(result, units), report.summaryLine)

        // Both directions appear with the exact recommended flag and limits status.
        assertEquals(2, report.directionSummary.size)
        assertEquals(
            listOf(PullDirection.FORWARD.label, PullDirection.REVERSE.label),
            report.directionSummary.map { it.direction },
        )
        val expectedRecommended = if (result.recommended == PullDirection.FORWARD) {
            listOf(true, false)
        } else {
            listOf(false, true)
        }
        assertEquals(expectedRecommended, report.directionSummary.map { it.recommended })
        report.directionSummary.forEach { row ->
            assertTrue(row.peakTension.endsWith(" lb"))
            assertTrue(row.peakPressure.endsWith(" lb/ft"))
            assertEquals("Within limits", row.status)
        }

        // All 6 segments appear in path order with forward & reverse tension and SWBP values.
        assertEquals(6, report.segments.size)
        assertEquals((1..6).toList(), report.segments.map { it.number })
        assertTrue(report.segments[0].description.startsWith("Straight "))
        assertEquals("—", report.segments[0].forwardSwbp)
        assertEquals("—", report.segments[0].reverseSwbp)
        assertTrue(report.segments[0].forwardOut.endsWith(" lb"))
        assertTrue(report.segments[0].reverseOut.endsWith(" lb"))

        // Horizontal bend (index 1).
        assertTrue(report.segments[1].description.contains("Bend 90°"))
        assertTrue(report.segments[1].forwardSwbp.endsWith(" lb/ft"))
        assertTrue(report.segments[1].reverseSwbp.endsWith(" lb/ft"))

        // Sloped straight (index 2).
        assertTrue(report.segments[2].description.contains("up 25°"))
        assertEquals("—", report.segments[2].forwardSwbp)
        assertEquals("—", report.segments[2].reverseSwbp)

        // Pull point (index 3): no pass steps, so all tension & SWBP values are "—".
        assertEquals("Pull point PB-1", report.segments[3].description)
        assertEquals("—", report.segments[3].forwardIn)
        assertEquals("—", report.segments[3].forwardOut)
        assertEquals("—", report.segments[3].forwardSwbp)
        assertEquals("—", report.segments[3].reverseIn)
        assertEquals("—", report.segments[3].reverseOut)
        assertEquals("—", report.segments[3].reverseSwbp)

        // Vertical sag (index 4).
        assertTrue(report.segments[4].description.contains("vertical sag"))
        assertTrue(report.segments[4].forwardSwbp.endsWith(" lb/ft"))
        assertTrue(report.segments[4].reverseSwbp.endsWith(" lb/ft"))

        // Vertical riser (index 5).
        assertTrue(report.segments[5].description.contains("up 90°"))

        // Run facts include project, conduit, lengths, bends/pull points, config & W, friction, jam ratio, and clearance.
        val facts = report.runFacts.toMap()
        assertEquals("Main Building", facts.getValue("Project"))
        assertTrue(facts.getValue("Conduit").contains("1-1/4\" EMT"))
        assertTrue(facts.getValue("Slack per end").contains("3'"))
        assertTrue(facts.getValue("Cable needed / conductor").isNotBlank() && facts.getValue("Cable needed / conductor") != "—")
        assertTrue(facts.getValue("Bends & pull points").contains("180°") && facts.getValue("Bends & pull points").contains("1 pull point"))
        assertTrue(facts.getValue("Configuration & W").contains("W ="))
        assertTrue(facts.getValue("Friction μ").contains("0.35") && facts.getValue("Friction μ").contains("Lubricated pull"))
        val jamKey = "Jam ratio (${PullFormulas.OVALITY_LABEL})"
        assertTrue(facts.containsKey(jamKey))
        assertTrue(facts.getValue(jamKey).contains("2.9") && facts.getValue(jamKey).contains("Jam risk"))
        assertTrue(facts.getValue("Clearance").contains("in."))

        // Cables, limits + sources, and unrated equipment appear.
        assertEquals(1, report.cables.size)
        assertTrue(report.cables.single().contains("3 × 2 AWG THHN"))
        val allLimitsText = report.limits.joinToString("\n") { "${it.first}: ${it.second}" }
        assertTrue(allLimitsText, "Rope" in allLimitsText && "800 lb" in allLimitsText && "Rating entered for this run" in allLimitsText)
        assertTrue(allLimitsText, "Swivel" in allLimitsText && "1,500 lb" in allLimitsText)
        assertTrue(allLimitsText, "Southwire Maximum Pulling Tension flyer" in allLimitsText)
        assertTrue(allLimitsText, "1,000 lb/ft" in allLimitsText && PullRatings.BUILDING_WIRE_PRESET_LABEL in allLimitsText)
        assertEquals(listOf("Pulling eye or grip", "Puller"), report.unratedEquipment)

        // Warnings & info notes are partitioned and preserved.
        val (expectedWarnings, expectedInfos) = result.notes.partition { it.isWarning }
        assertEquals(expectedWarnings.map { it.text }, report.warnings)
        assertEquals(expectedInfos.map { it.text }, report.infoNotes)
        assertTrue(report.warnings.any { "Jam ratio" in it })
        assertTrue(report.infoNotes.any { "Friction" in it })
    }

    @Test fun metricUnitSystemFormatsForcesPressuresAndLengthsInMetric() {
        val units = UnitSystem.METRIC
        val result = PullAnalysis.analyze(sampleRun(), runDesign = null, units = units)
        val report = PullReportModel.build(
            result = result,
            projectName = "Metric Job",
            companyHeader = emptyList(),
            date = "Nov 24, 2026",
            units = units,
            lengthText = lengthFormatter(units),
        )

        assertTrue(report.summaryLine.contains(" N "))
        assertTrue(report.summaryLine.contains(" kN/m"))
        assertFalse(report.summaryLine.contains(" lb"))

        report.directionSummary.forEach { row ->
            assertTrue(row.peakTension.endsWith(" N"))
            assertTrue(row.peakPressure.endsWith(" kN/m"))
        }

        val bendRow = report.segments[1]
        assertTrue(bendRow.forwardIn.endsWith(" N"))
        assertTrue(bendRow.forwardOut.endsWith(" N"))
        assertTrue(bendRow.forwardSwbp.endsWith(" kN/m"))
        assertTrue(bendRow.reverseIn.endsWith(" N"))
        assertTrue(bendRow.reverseOut.endsWith(" N"))
        assertTrue(bendRow.reverseSwbp.endsWith(" kN/m"))
        assertTrue(bendRow.description.contains(" mm") || bendRow.description.contains(" m"))

        val facts = report.runFacts.toMap()
        assertTrue(facts.getValue("Conduit").contains("(35)") && facts.getValue("Conduit").contains(" mm"))
        assertTrue(facts.getValue("Routed length").endsWith(" m"))
        assertTrue(facts.getValue("Cable needed / conductor").endsWith(" m"))
        assertTrue(facts.getValue("Clearance").contains(" mm"))
        assertTrue(report.cables.single().contains(" mm") && report.cables.single().contains(" kg/m"))

        val limitValues = report.limits.map { it.second }.joinToString("\n")
        assertTrue(limitValues, " N" in limitValues)
        assertTrue(limitValues, " kN/m" in limitValues)
    }

    @Test fun exceedsLimitsMarksDirectionStatusAndSupportsStandaloneRun() {
        val units = UnitSystem.IMPERIAL
        val run = sampleRun(
            equipment = listOf(EquipmentRating("Rope", 100.0)),
            pressureLimit = 50.0,
        )
        val result = PullAnalysis.analyze(run, runDesign = null, units = units)
        val report = PullReportModel.build(
            result = result,
            projectName = null,
            companyHeader = emptyList(),
            date = "Nov 24, 2026",
            units = units,
            lengthText = lengthFormatter(units),
        )

        assertTrue(report.subtitle.startsWith("Standalone pull"))
        assertEquals("Standalone", report.runFacts.toMap().getValue("Project"))
        assertTrue(report.directionSummary.all { it.status == "Exceeds limits" })
        assertTrue(report.warnings.any { "over the 100 lb limit (Rope)" in it })
        assertTrue(report.warnings.any { "over the 50 lb/ft limit" in it })
        assertTrue(report.unratedEquipment.isEmpty())
    }

    @Test fun uncalculatedPullWithoutPathOrConduitBuildsGracefully() {
        val units = UnitSystem.IMPERIAL
        val run = Run(name = "Empty", raceway = null, path = emptyList(), pull = PullSetup(cablesFromCircuits = false))
        val result = PullAnalysis.analyze(run, runDesign = null, units = units)
        val report = PullReportModel.build(
            result = result,
            projectName = null,
            companyHeader = emptyList(),
            date = "Nov 24, 2026",
            units = units,
            lengthText = lengthFormatter(units),
        )

        assertTrue(report.summaryLine.startsWith("Pull not calculated:"))
        assertTrue(report.directionSummary.isEmpty())
        assertTrue(report.segments.isEmpty())
        assertTrue(report.cables.isEmpty())
        assertTrue(report.warnings.isNotEmpty())
        assertEquals("Not set", report.runFacts.toMap().getValue("Conduit"))
    }
}
