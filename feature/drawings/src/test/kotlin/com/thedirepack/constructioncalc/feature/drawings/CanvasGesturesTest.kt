package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import com.thedirepack.constructioncalc.core.model.PlanPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasGesturesTest {

    @Test fun viewportMovePreservesFocalPointAcrossZoomAndAllRotations() {
        val initial = ViewportState(zoom = 1.5f, scroll = Offset(30f, -20f))
        val centroid = Offset(420f, 310f)
        val pan = Offset(18f, -12f)
        val factor = 1.8f

        for (turns in 0..3) {
            val before = computePageTransform(initial, pageWidth = 800f, pageHeight = 600f, quarterTurns = turns, viewportWidth = 1080f, viewportHeight = 900f)
            val pagePointBefore = before.toPage(centroid)

            val moved = computeViewportMove(
                current = initial,
                pageWidth = 800f,
                pageHeight = 600f,
                quarterTurns = turns,
                viewportWidth = 1080f,
                viewportHeight = 900f,
                centroid = centroid,
                pan = pan,
                zoomFactor = factor,
            )
            assertEquals(initial.zoom * factor, moved.zoom, 1e-4f)

            val after = computePageTransform(moved, pageWidth = 800f, pageHeight = 600f, quarterTurns = turns, viewportWidth = 1080f, viewportHeight = 900f)
            val screenAfter = after.toScreen(pagePointBefore)
            assertEquals("rotation $turns x", (centroid + pan).x, screenAfter.x, 1e-2f)
            assertEquals("rotation $turns y", (centroid + pan).y, screenAfter.y, 1e-2f)
        }
    }

    @Test fun viewportMoveClampsZoomBoundsAndScrollBoundsAndInvalidFactors() {
        val base = ViewportState(zoom = 1f, scroll = Offset.Zero)
        val tooSmall = computeViewportMove(base, 800f, 600f, 0, 1000f, 800f, Offset(500f, 400f), Offset.Zero, 0.1f)
        assertEquals(MIN_CANVAS_ZOOM, tooSmall.zoom, 1e-5f)

        val tooLarge = computeViewportMove(base, 800f, 600f, 0, 1000f, 800f, Offset(500f, 400f), Offset.Zero, 100f)
        assertEquals(MAX_CANVAS_ZOOM, tooLarge.zoom, 1e-5f)

        val nanZoom = computeViewportMove(base, 800f, 600f, 0, 1000f, 800f, Offset(500f, 400f), Offset(20f, 10f), Float.NaN)
        assertEquals(1f, nanZoom.zoom, 1e-5f)

        val hugePan = computeViewportMove(base, 800f, 600f, 0, 1000f, 800f, Offset(500f, 400f), Offset(50_000f, -50_000f), 1f)
        assertTrue(hugePan.scroll.x <= 500f && hugePan.scroll.x >= -500f)
        assertTrue(hugePan.scroll.y <= 500f && hugePan.scroll.y >= -500f)
    }

    @Test fun doubleTapTogglesZoomAndMatchesTimingAndDistance() {
        val fit = ViewportState(zoom = FIT_CANVAS_ZOOM, scroll = Offset.Zero)
        val zoomedIn = computeDoubleTapTarget(fit, Offset(650f, 250f), 800f, 600f, 0, 1000f, 800f)
        assertEquals(DOUBLE_TAP_ZOOM, zoomedIn.zoom, 1e-4f)

        val reset = computeDoubleTapTarget(zoomedIn, Offset(500f, 400f), 800f, 600f, 0, 1000f, 800f)
        assertEquals(FIT_CANVAS_ZOOM, reset.zoom, 1e-4f)
        assertEquals(Offset.Zero, reset.scroll)

        assertTrue(isDoubleTap(1000L, Offset(100f, 100f), 1200L, Offset(115f, 108f), 48f))
        assertFalse(isDoubleTap(1000L, Offset(100f, 100f), 1450L, Offset(105f, 105f), 48f))
        assertFalse(isDoubleTap(1000L, Offset(100f, 100f), 1150L, Offset(250f, 100f), 48f))
        assertFalse(isDoubleTap(0L, Offset.Zero, 100L, Offset.Zero, 48f))
    }

    @Test fun pointerIntentHandlesStylusEraserBarrelModesAndPalmRejection() {
        // Stylus tail eraser temporarily erases in any drawing mode when editable.
        assertEquals(
            PointerIntent.TEMP_ERASE,
            resolvePointerIntent(PointerType.Eraser, barrelPressed = false, DrawingMode.Pen, editable = true, stylusOnlyDraw = false, StylusBarrelAction.OFF, false, 1000L, 900L),
        )
        // Barrel actions on Stylus
        assertEquals(
            PointerIntent.TEMP_ERASE,
            resolvePointerIntent(PointerType.Stylus, barrelPressed = true, DrawingMode.Pen, editable = true, stylusOnlyDraw = true, StylusBarrelAction.ERASER, true, 1000L, 950L),
        )
        assertEquals(
            PointerIntent.PAN_ONLY,
            resolvePointerIntent(PointerType.Stylus, barrelPressed = true, DrawingMode.Pen, editable = true, stylusOnlyDraw = true, StylusBarrelAction.PAN, true, 1000L, 950L),
        )
        assertEquals(
            PointerIntent.ACTIVE_TOOL,
            resolvePointerIntent(PointerType.Stylus, barrelPressed = true, DrawingMode.Pen, editable = true, stylusOnlyDraw = true, StylusBarrelAction.OFF, true, 1000L, 950L),
        )
        // Palm rejection blocks touch while hovering or within cooldown.
        assertEquals(
            PointerIntent.IGNORE,
            resolvePointerIntent(PointerType.Touch, barrelPressed = false, DrawingMode.Pen, editable = true, stylusOnlyDraw = false, StylusBarrelAction.ERASER, stylusHovering = true, nowMs = 2000L, lastStylusActivityMs = 1000L),
        )
        assertEquals(
            PointerIntent.IGNORE,
            resolvePointerIntent(PointerType.Touch, barrelPressed = false, DrawingMode.Pen, editable = true, stylusOnlyDraw = false, StylusBarrelAction.ERASER, stylusHovering = false, nowMs = 1150L, lastStylusActivityMs = 1000L),
        )
        // After cooldown with Pencil mode ON: finger pans in Pen/Measure, but can still select in Adjust.
        assertEquals(
            PointerIntent.PAN_ONLY,
            resolvePointerIntent(PointerType.Touch, barrelPressed = false, DrawingMode.Pen, editable = true, stylusOnlyDraw = true, StylusBarrelAction.ERASER, stylusHovering = false, nowMs = 1500L, lastStylusActivityMs = 1000L),
        )
        assertEquals(
            PointerIntent.ACTIVE_TOOL,
            resolvePointerIntent(PointerType.Touch, barrelPressed = false, DrawingMode.Adjust, editable = true, stylusOnlyDraw = true, StylusBarrelAction.ERASER, stylusHovering = false, nowMs = 1500L, lastStylusActivityMs = 1000L),
        )
        // With Pencil mode OFF after cooldown: finger draws normally.
        assertEquals(
            PointerIntent.ACTIVE_TOOL,
            resolvePointerIntent(PointerType.Touch, barrelPressed = false, DrawingMode.Pen, editable = true, stylusOnlyDraw = false, StylusBarrelAction.ERASER, stylusHovering = false, nowMs = 1500L, lastStylusActivityMs = 1000L),
        )
        // Non-editable sheets always pan (unless palm-blocked).
        assertEquals(
            PointerIntent.PAN_ONLY,
            resolvePointerIntent(PointerType.Touch, barrelPressed = false, DrawingMode.Pen, editable = false, stylusOnlyDraw = false, StylusBarrelAction.ERASER, stylusHovering = false, nowMs = 1500L, lastStylusActivityMs = 0L),
        )
    }

    @Test fun multiFingerTapRecognizesUndoAndRedoWithoutFalselyTriggeringOnPinch() {
        assertEquals(MultiTapAction.UNDO, evaluateMultiFingerTap(true, peakTouchCount = 2, durationMs = 140L, maxPointerTravelPx = 4f, touchSlopPx = 12f, cumulativeZoom = 1.0f))
        assertEquals(MultiTapAction.REDO, evaluateMultiFingerTap(true, peakTouchCount = 3, durationMs = 180L, maxPointerTravelPx = 6f, touchSlopPx = 12f, cumulativeZoom = 1.01f))
        // Disabled
        assertEquals(MultiTapAction.NONE, evaluateMultiFingerTap(false, 2, 120L, 2f, 12f, 1.0f))
        // Pinch zoom or pan travel disqualifies tap gesture
        assertEquals(MultiTapAction.NONE, evaluateMultiFingerTap(true, 2, 120L, 2f, 12f, 1.15f))
        assertEquals(MultiTapAction.NONE, evaluateMultiFingerTap(true, 2, 120L, 25f, 12f, 1.0f))
        assertEquals(MultiTapAction.NONE, evaluateMultiFingerTap(true, 2, 400L, 2f, 12f, 1.0f))
    }

    @Test fun inkStrokeFilterDecimatesSmoothsPressureAndCapsLength() {
        val smooth = InkStrokeFilter(pressureSensitive = true, maxPoints = 5)
        val first = smooth.append(PlanPoint(0.0, 0.0), rawPressure = 0.0f, isStylus = true, minSpacingPt = 2.0)
        assertNotNull(first)
        assertEquals(0.20f, first!!.pressure, 1e-4f)

        // Closer than minSpacingPt -> decimated
        assertNull(smooth.append(PlanPoint(1.0, 1.0), rawPressure = 1.0f, isStylus = true, minSpacingPt = 2.0))

        // Far enough -> accepted and EMA-smoothed toward 1.0 (0.20 + 0.35 * 0.80 = 0.48)
        val second = smooth.append(PlanPoint(5.0, 0.0), rawPressure = 1.0f, isStylus = true, minSpacingPt = 2.0)
        assertNotNull(second)
        assertEquals(0.48f, second!!.pressure, 1e-4f)

        // Cap at maxPoints
        assertNotNull(smooth.append(PlanPoint(10.0, 0.0), 1f, true, 2.0))
        assertNotNull(smooth.append(PlanPoint(15.0, 0.0), 1f, true, 2.0))
        assertNotNull(smooth.append(PlanPoint(20.0, 0.0), 1f, true, 2.0))
        assertNull(smooth.append(PlanPoint(25.0, 0.0), 1f, true, 2.0))

        // Touch or disabled pressure stays constant 1.0
        val uniform = InkStrokeFilter(pressureSensitive = false)
        assertEquals(1.0f, uniform.append(PlanPoint(0.0, 0.0), 0.1f, isStylus = true, minSpacingPt = 1.0)!!.pressure, 1e-6f)
        val touch = InkStrokeFilter(pressureSensitive = true)
        assertEquals(1.0f, touch.append(PlanPoint(0.0, 0.0), 0.3f, isStylus = false, minSpacingPt = 1.0)!!.pressure, 1e-6f)
    }

    @Test fun everyDrawingModeHasAUniqueDrawableIcon() {
        val icons = DrawingMode.entries.map { it.iconRes() }
        assertEquals(DrawingMode.entries.size, icons.toSet().size)
        assertTrue(icons.all { it != 0 })
    }
}
