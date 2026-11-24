package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.runtime.saveable.SaverScope
import com.thedirepack.constructioncalc.core.data.PlanExportCrop
import com.thedirepack.constructioncalc.core.data.PlanSheetExportMode
import com.thedirepack.constructioncalc.core.model.PlanSheetSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetExportRequestTest {
    private val source = PlanSheetSource("drawing", "a".repeat(64), 2, "E/101", "A:2")
    private val scope = SaverScope { true }

    @Test fun exportDestinationRoundTripKeepsTheSelectedPageAndCrop() {
        val request = SheetExportRequest(source, PlanExportCrop(12f, 25f, 240f, 350f))
        val saved = with(SheetExportSaver) { scope.save(request) }
        assertEquals(request, SheetExportSaver.restore(checkNotNull(saved)))
        assertEquals("E_101 - Rev A_2 - crop.pdf", request.fileName)
    }

    @Test fun wholeSheetAndNoPendingExportRestoreSeparately() {
        val request = SheetExportRequest(source.copy(revision = null), null)
        val saved = with(SheetExportSaver) { scope.save(request) }
        assertEquals(request, SheetExportSaver.restore(checkNotNull(saved)))
        assertEquals("E_101 - Rev unassigned - annotated.pdf", request.fileName)
        assertNull(with(SheetExportSaver) { scope.save(null) })
    }

    @Test fun rasterSnapshotModeRoundTripsAndUsesDistinctFileNames() {
        val fullSnapshot = SheetExportRequest(source.copy(revision = null), null, PlanSheetExportMode.RASTER)
        val savedFull = with(SheetExportSaver) { scope.save(fullSnapshot) }
        assertEquals(fullSnapshot, SheetExportSaver.restore(checkNotNull(savedFull)))
        assertEquals("E_101 - Rev unassigned - snapshot.pdf", fullSnapshot.fileName)

        val cropSnapshot = SheetExportRequest(source, PlanExportCrop(10f, 20f, 110f, 220f), PlanSheetExportMode.RASTER)
        val savedCrop = with(SheetExportSaver) { scope.save(cropSnapshot) }
        assertEquals(cropSnapshot, SheetExportSaver.restore(checkNotNull(savedCrop)))
        assertEquals("E_101 - Rev A_2 - crop-snapshot.pdf", cropSnapshot.fileName)
    }

    @Test fun legacySavedStateWithoutModeRestoresAsVector() {
        val legacyFull = listOf("drawing", "a".repeat(64), 2, "E/101", "")
        assertEquals(
            SheetExportRequest(source.copy(revision = null), null, PlanSheetExportMode.VECTOR),
            SheetExportSaver.restore(legacyFull),
        )
        val legacyCrop = listOf("drawing", "a".repeat(64), 2, "E/101", "A:2", 12f, 25f, 240f, 350f)
        assertEquals(
            SheetExportRequest(source, PlanExportCrop(12f, 25f, 240f, 350f), PlanSheetExportMode.VECTOR),
            SheetExportSaver.restore(legacyCrop),
        )
    }
}
