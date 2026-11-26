package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.model.design.MaterialTakeoffs
import com.thedirepack.constructioncalc.core.model.design.ScheduleCsv
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceCatalogTest {
    private val catalog = DeviceCatalog.bundled

    /** The app reads the detector's catalog; the packaged copy must be that file, byte for byte. */
    @Test fun packagedCatalogIsTheDetectorSourceOfTruth() {
        val source = File("../../ml/symbol-detector/annotator/electrical_device_catalog.json")
        assertTrue("Run from core/model: ${source.absolutePath}", source.isFile)
        val packaged = DeviceCatalog::class.java.getResourceAsStream(DeviceCatalog.RESOURCE)!!.use { it.readBytes() }
        assertArrayEquals("The app's device catalog drifted from ml/symbol-detector; rebuild.", source.readBytes(), packaged)
    }

    @Test fun everyDeviceHasAFamilyVariantIdAndName() {
        assertTrue(catalog.devices.size > 100)
        catalog.devices.forEach { device ->
            assertEquals("${device.family}.${device.variant}", device.id)
            assertTrue(device.label.isNotBlank())
            assertTrue(device.category in catalog.categories)
            assertTrue(device.shortLabel.length <= 4)
        }
    }

    @Test fun snapsDetectorClassNamesToCatalogDevices() {
        assertEquals("switch.three_way", catalog.snap("switch.three_way")?.id)
        // Merged ids from the catalog's renamed table.
        assertEquals("luminaire.exit_sign", catalog.snap("luminaire.exit_sign_wall")?.id)
        // Family default subtypes.
        assertEquals("receptacle.duplex", catalog.snap("receptacle")?.id)
        assertEquals("receptacle.duplex_gfci", catalog.snap("receptacle.gfci")?.id)
        // NECA alias and loose spelling.
        assertEquals("receptacle.duplex", catalog.snap("Receptacle/Duplex")?.id)
        assertEquals("receptacle.duplex", catalog.snap(" receptacle.duplex ")?.id)
        // Not a device: dropped by callers.
        assertNull(catalog.snap("hvac_device"))
        assertNull(catalog.snap("switch.unspecified"))
        assertNull(catalog.snap(""))
    }

    @Test fun searchesNamesCategoriesAndLegendWording() {
        val gfci = catalog.search("gfci")
        assertTrue(gfci.any { it.id == "receptacle.duplex_gfci" })
        assertTrue(catalog.search("3-way dimmer").any { it.id == "switch.three_way_dimmer" })
        assertTrue(catalog.search("", category = "Fire Alarm").all { it.category == "Fire Alarm" })
        assertEquals(catalog.devices, catalog.search("  "))
    }
}

class PlanSymbolCountsTest {
    private val sha = "a".repeat(64)

    private fun symbol(deviceId: String, state: PlanReviewState = PlanReviewState.ACCEPTED) = PlanMark(
        page = 0,
        kind = PlanMarkKind.SYMBOL,
        points = listOf(PlanPoint(10.0, 10.0)),
        symbol = PlanSymbolPlacement(deviceId, reviewState = state, source = if (state == PlanReviewState.ACCEPTED) PlanSymbolSource.MANUAL else PlanSymbolSource.DETECTED),
    )

    @Test fun onlyAcceptedSymbolsReachTheMaterialTakeoff() {
        val custom = PlanCustomSymbol("custom:1", "Floor box", "<svg viewBox=\"0 0 1 1\"><rect width=\"1\" height=\"1\"/></svg>")
        val first = PlanDrawing(
            name = "E1.pdf", sha256 = sha, customSymbols = listOf(custom),
            marks = listOf(
                symbol("receptacle.duplex"), symbol("receptacle.duplex"),
                symbol("receptacle.duplex", PlanReviewState.PENDING),
                symbol("receptacle.duplex", PlanReviewState.REJECTED),
                symbol("switch.single_pole"),
                symbol("custom:1"),
            ),
        )
        val second = PlanDrawing(name = "E2.pdf", sha256 = "b".repeat(64), marks = listOf(symbol("receptacle.duplex")))
        val project = Project.create("Job", 1L).copy(drawings = listOf(first, second))

        val takeoff = MaterialTakeoffs.of(project)
        assertEquals(
            listOf("Receptacle - Duplex" to 3, "Switch - Single Pole" to 1, "Floor box" to 1),
            takeoff.devices.map { it.name to it.count },
        )
        assertEquals(listOf("E1.pdf", "E2.pdf"), takeoff.devices.first().drawingNames)
        assertTrue(!takeoff.isEmpty)
        assertTrue(ProjectText.takeoffText(takeoff).contains("Device Receptacle - Duplex: 3"))
        assertTrue(ScheduleCsv.materialTakeoff(project).contains("Device,Receptacle - Duplex,3,ea,E1.pdf; E2.pdf"))
        assertEquals(1, PlanSymbolCounts.pending(first).size)
    }

    @Test fun noSymbolsNoDevices() {
        val project = Project.create("Job", 1L).copy(drawings = listOf(PlanDrawing(name = "E1.pdf", sha256 = sha)))
        assertTrue(MaterialTakeoffs.of(project).devices.isEmpty())
    }

    @Test fun rotationNormalizes() {
        assertEquals(15.0, PlanSymbolPlacement("switch.dimmer", rotationDegrees = 350.0).rotatedBy(25.0).rotationDegrees, 1e-9)
        assertEquals(270.0, PlanSymbolPlacement("switch.dimmer").rotatedBy(-90.0).rotationDegrees, 1e-9)
    }
}
