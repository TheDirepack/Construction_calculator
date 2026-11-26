package com.thedirepack.constructioncalc.feature.drawings

import com.thedirepack.constructioncalc.core.data.PlanSymbol
import com.thedirepack.constructioncalc.core.data.SymbolLibraryMeta
import com.thedirepack.constructioncalc.core.model.DeviceCatalog
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReviewState
import com.thedirepack.constructioncalc.core.model.PlanSymbolPlacement
import com.thedirepack.constructioncalc.core.model.PlanSymbolSource
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolEditingTest {
    private val catalog = DeviceCatalog.bundled
    private val drawing = PlanDrawing(name = "E1.pdf", sha256 = "a".repeat(64))
    private val duplex = SymbolChoice.Device(catalog.find("receptacle.duplex")!!)
    private val imported = SymbolChoice.Imported(
        PlanSymbol("0b6f1a52-3f3c-4b7e-9a3e-0c1e0d5f2a11", "Floor box", """<svg viewBox="0 0 1 1"><rect width="1" height="1"/></svg>"""),
    )

    private fun symbolAt(x: Double, y: Double, size: Double = 20.0, rotation: Double = 0.0, state: PlanReviewState = PlanReviewState.ACCEPTED, id: String = "receptacle.duplex") =
        PlanMark(page = 0, kind = PlanMarkKind.SYMBOL, points = listOf(PlanPoint(x, y)), symbol = PlanSymbolPlacement(id, size, rotation, reviewState = state))

    @Test fun placingCentersTheSymbolOnTheTapAndStoresNoArtwork() {
        val (placed, mark) = placeSymbol(drawing, duplex, 0, PlanPoint(100.0, 50.0), 24.0, 90.0, "E-1", "Kitchen")
        assertEquals(PlanPoint(100.0, 50.0), mark.points.single())
        assertEquals("receptacle.duplex", mark.symbol!!.deviceId)
        assertEquals(90.0, mark.symbol!!.rotationDegrees, 0.0)
        assertEquals(PlanSymbolSource.MANUAL, mark.symbol!!.source)
        assertTrue(placed.customSymbols.isEmpty())
        // The saved mark carries an id reference, not SVG.
        assertFalse(ProjectCodec.json.encodeToString(PlanMark.serializer(), mark).contains("<svg"))
    }

    @Test fun importedSvgIsStoredOncePerDrawing() {
        val (once, first) = placeSymbol(drawing, imported, 0, PlanPoint(1.0, 1.0), 24.0, 0.0, "", "")
        val (twice, _) = placeSymbol(once, imported, 0, PlanPoint(9.0, 9.0), 24.0, 0.0, "", "")
        assertEquals("custom:${imported.symbol.id}", first.symbol!!.deviceId)
        assertEquals(1, twice.customSymbols.size)
        assertEquals(2, twice.marks.size)
    }

    @Test fun hitTestFollowsCenterSizeAndRotation() {
        val mark = symbolAt(100.0, 100.0, size = 20.0)
        assertNotNull(markAt(listOf(mark), PlanPoint(108.0, 92.0), 0.0))
        assertNull(markAt(listOf(mark), PlanPoint(112.0, 100.0), 0.0))
        // A 2:1 custom shape doesn't exist, but rotation must still map corners: a 45° square reaches further along the axes.
        val turned = symbolAt(100.0, 100.0, size = 20.0, rotation = 45.0)
        assertNotNull(markAt(listOf(turned), PlanPoint(113.0, 100.0), 0.0))
        assertNull(markAt(listOf(turned), PlanPoint(109.0, 109.0), 0.0))
    }

    @Test fun handlesResizeFromTheCornerAndRotateFromAbove() {
        val mark = symbolAt(100.0, 100.0, size = 20.0)
        val (corner, rotate) = mark.editHandles()
        assertEquals(PlanPoint(110.0, 110.0), corner)
        assertEquals(100.0, rotate.x, 1e-9)
        assertTrue(rotate.y < 90.0)

        val bigger = mark.resize(0, PlanPoint(115.0, 112.0))
        assertEquals(30.0, bigger.symbol!!.size, 1e-9)
        assertEquals(mark.points, bigger.points)

        // Drag the rotate handle to the right of the center: 90°.
        assertEquals(90.0, mark.resize(1, PlanPoint(140.0, 101.0)).symbol!!.rotationDegrees, 1e-9)
        // Below-left: 225°, in 5° steps.
        assertEquals(225.0, mark.resize(1, PlanPoint(80.0, 120.0)).symbol!!.rotationDegrees, 1e-9)
        assertEquals(0.0, rotationToward(PlanPoint(0.0, 0.0), PlanPoint(1.0, -40.0)), 1e-9)

        // Moving keeps size and rotation.
        val moved = mark.move(5.0, -5.0)
        assertEquals(PlanPoint(105.0, 95.0), moved.points.single())
        assertEquals(mark.symbol, moved.symbol)
    }

    @Test fun changingClassKeepsReviewState() {
        val pending = symbolAt(10.0, 10.0, state = PlanReviewState.PENDING)
        val d = drawing.copy(marks = listOf(pending))
        val switch = SymbolChoice.Device(catalog.find("switch.single_pole")!!)
        val updated = reclassifySymbol(d, pending, switch).marks.single().symbol!!
        assertEquals("switch.single_pole", updated.deviceId)
        assertEquals(PlanReviewState.PENDING, updated.reviewState)
    }

    @Test fun reviewDecidesByClassAndPage() {
        val marks = listOf(
            symbolAt(1.0, 1.0, state = PlanReviewState.PENDING),
            symbolAt(2.0, 1.0, state = PlanReviewState.PENDING),
            symbolAt(3.0, 1.0, state = PlanReviewState.PENDING, id = "switch.single_pole"),
            symbolAt(4.0, 1.0, state = PlanReviewState.PENDING).copy(page = 1),
            symbolAt(5.0, 1.0, state = PlanReviewState.REJECTED),
        )
        val d = drawing.copy(marks = marks)
        assertEquals(listOf("receptacle.duplex" to 2, "switch.single_pole" to 1), SymbolReview.pendingGroups(d, 0).map { it.first to it.second.size })

        val accepted = SymbolReview.decideClass(d, 0, "receptacle.duplex", PlanReviewState.ACCEPTED)
        assertEquals(
            listOf(PlanReviewState.ACCEPTED, PlanReviewState.ACCEPTED, PlanReviewState.PENDING, PlanReviewState.PENDING, PlanReviewState.REJECTED),
            accepted.marks.map { it.symbol!!.reviewState },
        )
        val switch = SymbolChoice.Device(catalog.find("switch.three_way")!!)
        assertEquals("switch.three_way", SymbolReview.reclassifyClass(d, 0, "switch.single_pole", switch).marks[2].symbol!!.deviceId)
        assertEquals(4, SymbolReview.clearRejected(d, 0).marks.size)

        val first = SymbolReview.nextPending(d, 0, null)!!
        assertEquals(marks[0].id, first.id)
        assertEquals(marks[1].id, SymbolReview.nextPending(d, 0, first.id)!!.id)
        assertEquals(marks[0].id, SymbolReview.nextPending(d, 0, marks[2].id)!!.id)
    }

    @Test fun pickerSearchesTheWholeCatalogAndImportedSymbols() {
        val meta = SymbolLibraryMeta(favorites = setOf("switch.dimmer"), recents = listOf(imported.id, "receptacle.duplex"))
        val all = filterSymbolChoices(catalog, listOf(imported.symbol), meta, SymbolFilter.All, "")
        assertEquals(catalog.devices.size + 1, all.size)
        assertEquals(listOf("switch.dimmer"), filterSymbolChoices(catalog, emptyList(), meta, SymbolFilter.Favorites, "").map { it.id })
        assertEquals(listOf(imported.id, "receptacle.duplex"), filterSymbolChoices(catalog, listOf(imported.symbol), meta, SymbolFilter.Recent, "").map { it.id })
        assertTrue(filterSymbolChoices(catalog, emptyList(), meta, SymbolFilter.All, "smoke").any { it.id == "fire_alarm_device.smoke_detector" })
        assertTrue(filterSymbolChoices(catalog, listOf(imported.symbol), meta, SymbolFilter.All, "floor box").any { it.id == imported.id })
        assertEquals(listOf(imported.id), filterSymbolChoices(catalog, listOf(imported.symbol), meta, SymbolFilter.Imported, "").map { it.id })
        val fireAlarm = filterSymbolChoices(catalog, emptyList(), meta, SymbolFilter.Category("Fire Alarm"), "")
        assertTrue(fireAlarm.isNotEmpty() && fireAlarm.all { (it as SymbolChoice.Device).device.category == "Fire Alarm" })
        assertEquals(duplex, resolveSymbolChoice("receptacle.duplex", catalog, emptyList()))
        assertEquals(imported, resolveSymbolChoice(imported.id, catalog, listOf(imported.symbol)))
    }

    @Test fun sizeStepsAndSummary() {
        assertEquals(32.0, nextSymbolSize(24.0, larger = true), 0.0)
        assertEquals(12.0, nextSymbolSize(13.7, larger = false), 0.0)
        assertEquals(96.0, nextSymbolSize(96.0, larger = true), 0.0)
        val stats = com.thedirepack.constructioncalc.feature.drawings.recognition.RecognitionStats(proposed = 4, alreadyMarked = 1, unknownClasses = mapOf("x" to 2))
        assertEquals("Found 4 symbols to review (1 already marked, 2 not in the device catalog skipped).", recognitionSummary(stats, cancelled = false))
        assertTrue(recognitionSummary(stats, cancelled = true).startsWith("Recognition stopped."))
    }
}
