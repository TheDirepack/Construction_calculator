package com.thedirepack.constructioncalc.core.data

import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ceil

class PlanExportsTransformTest {
    private val delta = 1e-4f

    private fun Matrix.map(u: Float, v: Float): Pair<Float, Float> {
        val out = transform(Vector(u, v))
        return out.x to out.y
    }

    private fun assertPointEquals(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>) {
        assertEquals("x", expectedX, actual.first, delta)
        assertEquals("y", expectedY, actual.second, delta)
    }

    @Test
    fun fullSheetCornersMapCorrectlyAcrossAllRotations() {
        val cropX = 100f
        val cropY = 200f
        val cropW = 400f
        val cropH = 600f

        // 0°: displayed 400 x 600, same orientation as source.
        val m0 = vectorSheetSourceMatrix(
            cropX, cropY, cropW, cropH,
            rotationDegrees = 0,
            region = PlanExportCrop(0f, 0f, 400f, 600f),
            contentLeft = 0f,
            contentBottom = 0f,
        )
        assertPointEquals(0f, 0f, m0.map(cropX, cropY))
        assertPointEquals(400f, 0f, m0.map(cropX + cropW, cropY))
        assertPointEquals(0f, 600f, m0.map(cropX, cropY + cropH))
        assertPointEquals(400f, 600f, m0.map(cropX + cropW, cropY + cropH))

        // 90° CW: displayed 600 x 400.
        val m90 = vectorSheetSourceMatrix(
            cropX, cropY, cropW, cropH,
            rotationDegrees = 90,
            region = PlanExportCrop(0f, 0f, 600f, 400f),
            contentLeft = 0f,
            contentBottom = 0f,
        )
        assertPointEquals(0f, 400f, m90.map(cropX, cropY))
        assertPointEquals(0f, 0f, m90.map(cropX + cropW, cropY))
        assertPointEquals(600f, 400f, m90.map(cropX, cropY + cropH))
        assertPointEquals(600f, 0f, m90.map(cropX + cropW, cropY + cropH))

        // 180°: displayed 400 x 600.
        val m180 = vectorSheetSourceMatrix(
            cropX, cropY, cropW, cropH,
            rotationDegrees = 180,
            region = PlanExportCrop(0f, 0f, 400f, 600f),
            contentLeft = 0f,
            contentBottom = 0f,
        )
        assertPointEquals(400f, 600f, m180.map(cropX, cropY))
        assertPointEquals(0f, 600f, m180.map(cropX + cropW, cropY))
        assertPointEquals(400f, 0f, m180.map(cropX, cropY + cropH))
        assertPointEquals(0f, 0f, m180.map(cropX + cropW, cropY + cropH))

        // 270° CW: displayed 600 x 400.
        val m270 = vectorSheetSourceMatrix(
            cropX, cropY, cropW, cropH,
            rotationDegrees = 270,
            region = PlanExportCrop(0f, 0f, 600f, 400f),
            contentLeft = 0f,
            contentBottom = 0f,
        )
        assertPointEquals(600f, 0f, m270.map(cropX, cropY))
        assertPointEquals(600f, 400f, m270.map(cropX + cropW, cropY))
        assertPointEquals(0f, 0f, m270.map(cropX, cropY + cropH))
        assertPointEquals(0f, 400f, m270.map(cropX + cropW, cropY + cropH))
    }

    @Test
    fun normalizedRotationsMatchCanonicalAngles() {
        val region4x6 = PlanExportCrop(0f, 0f, 400f, 600f)
        val region6x4 = PlanExportCrop(0f, 0f, 600f, 400f)
        assertEquals(
            vectorSheetSourceMatrix(10f, 20f, 400f, 600f, 0, region4x6, 0f, 0f),
            vectorSheetSourceMatrix(10f, 20f, 400f, 600f, 360, region4x6, 0f, 0f),
        )
        assertEquals(
            vectorSheetSourceMatrix(10f, 20f, 400f, 600f, 90, region6x4, 0f, 0f),
            vectorSheetSourceMatrix(10f, 20f, 400f, 600f, 450, region6x4, 0f, 0f),
        )
        assertEquals(
            vectorSheetSourceMatrix(10f, 20f, 400f, 600f, 180, region4x6, 0f, 0f),
            vectorSheetSourceMatrix(10f, 20f, 400f, 600f, -180, region4x6, 0f, 0f),
        )
        assertEquals(
            vectorSheetSourceMatrix(10f, 20f, 400f, 600f, 270, region6x4, 0f, 0f),
            vectorSheetSourceMatrix(10f, 20f, 400f, 600f, -90, region6x4, 0f, 0f),
        )
    }

    @Test
    fun croppedRegionWithNarrowCenteringMapsExactDisplayedCorners() {
        val cropX = 24f
        val cropY = 48f
        val cropW = 400f
        val cropH = 600f
        // Region width 120 < 180 min output width -> contentLeft = (180 - 120) / 2 = 30.
        val region = PlanExportCrop(50f, 30f, 170f, 180f)
        val headerH = 36f
        val pageW = maxOf(180f, ceil(region.width))
        val pageH = ceil(region.height) + headerH
        val contentLeft = (pageW - region.width) / 2f
        val contentBottom = pageH - headerH - region.height

        fun sourcePointForDisplayed(rot: Int, dispX: Float, dispYDown: Float): Pair<Float, Float> {
            val dispH = if (rot == 90 || rot == 270) cropW else cropH
            val dy = dispH - dispYDown
            return when (rot) {
                90 -> (cropX + cropW - dy) to (cropY + dispX)
                180 -> (cropX + cropW - dispX) to (cropY + cropH - dy)
                270 -> (cropX + dy) to (cropY + cropH - dispX)
                else -> (cropX + dispX) to (cropY + dy)
            }
        }

        for (rot in listOf(0, 90, 180, 270)) {
            val matrix = vectorSheetSourceMatrix(
                cropX, cropY, cropW, cropH, rot, region, contentLeft, contentBottom,
            )
            val (tlU, tlV) = sourcePointForDisplayed(rot, region.left, region.top)
            val (trU, trV) = sourcePointForDisplayed(rot, region.right, region.top)
            val (blU, blV) = sourcePointForDisplayed(rot, region.left, region.bottom)
            val (brU, brV) = sourcePointForDisplayed(rot, region.right, region.bottom)

            assertPointEquals(contentLeft, contentBottom + region.height, matrix.map(tlU, tlV))
            assertPointEquals(contentLeft + region.width, contentBottom + region.height, matrix.map(trU, trV))
            assertPointEquals(contentLeft, contentBottom, matrix.map(blU, blV))
            assertPointEquals(contentLeft + region.width, contentBottom, matrix.map(brU, brV))
        }
    }

    @Test
    fun fractionalRegionHeightAlignsWithHeaderAndContentBottom() {
        val cropX = 12f
        val cropY = 18f
        val cropW = 500f
        val cropH = 400f
        val region = PlanExportCrop(10f, 20f, 210.5f, 140.25f)
        val headerH = 36f
        val pageW = maxOf(180f, ceil(region.width))
        val pageH = ceil(region.height) + headerH
        val contentLeft = (pageW - region.width) / 2f
        val contentBottom = pageH - headerH - region.height

        val matrix = vectorSheetSourceMatrix(cropX, cropY, cropW, cropH, 0, region, contentLeft, contentBottom)
        // Top of crop touches pageH - headerH; bottom of crop touches contentBottom.
        assertPointEquals(contentLeft, pageH - headerH, matrix.map(cropX + region.left, cropY + cropH - region.top))
        assertPointEquals(contentLeft + region.width, contentBottom, matrix.map(cropX + region.right, cropY + cropH - region.bottom))
    }
}
