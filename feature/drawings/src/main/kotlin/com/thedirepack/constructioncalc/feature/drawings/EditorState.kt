package com.thedirepack.constructioncalc.feature.drawings

import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.model.PlanArrowStyle
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanMeasurement
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanReference
import com.thedirepack.constructioncalc.core.model.PlanZone
import kotlin.math.hypot

internal enum class ToolGroup(val label: String) { TAKEOFF("Takeoff"), MARKUP("Markup") }

internal enum class DrawingMode(val title: String, val hint: String, val group: ToolGroup) {
    Navigate("Pan", "Drag to pan. Pinch to zoom.", ToolGroup.TAKEOFF),
    Measure("Measure", "Tap or drag between the ends of a line to measure.", ToolGroup.TAKEOFF),
    Route("Route", "Tap connected points along a conduit route to measure legs and bends.", ToolGroup.TAKEOFF),
    Adjust("Select", "Tap a markup or measurement to select it. Drag the sheet to pan.", ToolGroup.TAKEOFF),
    Reference("Calibrate", "Tap or drag between the ends of a known dimension.", ToolGroup.TAKEOFF),
    Zone("Zone", "Draw an override zone for a detail with a different scale.", ToolGroup.TAKEOFF),
    Pen("Pen", "Draw with a finger or stylus. Pinch with two fingers to zoom.", ToolGroup.MARKUP),
    Highlight("Highlight", "Drag a finger or stylus over the area to highlight.", ToolGroup.MARKUP),
    Arrow("Arrow", "Tap or drag from the tail to the tip.", ToolGroup.MARKUP),
    Line("Line", "Tap or drag between the line endpoints.", ToolGroup.MARKUP),
    Rectangle("Rectangle", "Tap or drag between two opposite corners.", ToolGroup.MARKUP),
    Text("Text", "Tap where the text should begin.", ToolGroup.MARKUP),
    Symbol("Count", "Tap each device to place the chosen symbol.", ToolGroup.MARKUP),
    Callout("Callout", "Place the leader tip, then the note.", ToolGroup.MARKUP),
    Dimension("Dimension", "Tap or drag between two points to stamp a calibrated dimension.", ToolGroup.MARKUP),
    Cloud("Cloud", "Tap or drag opposite corners to draw a revision cloud.", ToolGroup.MARKUP),
    CloudCallout("Cloud + Note", "Draw a revision cloud, then attach a note.", ToolGroup.MARKUP),
    Photo("Photo", "Tap where you want to pin a field photo.", ToolGroup.MARKUP),
    DetailLink("Detail link", "Tap to link a callout directly to another sheet or detail.", ToolGroup.MARKUP),
    Eraser("Eraser", "Drag to trim strokes or switch to Whole stroke in the bar.", ToolGroup.MARKUP),
    ;

    /** Two points, tapped one after the other or dragged. */
    val usesSpan: Boolean
        get() = this == Zone || this == Reference || this == Measure || this == Arrow ||
            this == Line || this == Rectangle || this == Callout ||
            this == Dimension || this == Cloud || this == CloudCallout

    val usesLoupe: Boolean
        get() = this == Reference || this == Measure || this == Route || this == Adjust || this == Dimension

    /** Placed points snap to the line map (with the measuring or the calibrating setting). */
    val snaps: Boolean
        get() = this == Reference || this == Measure || this == Route || this == Adjust || this == Dimension

    val isFreehand: Boolean
        get() = this == Pen || this == Highlight
}

internal enum class EraserMode(val label: String) {
    PARTIAL("Partial"),
    WHOLE("Whole stroke"),
}

internal enum class CountGrouping(val label: String) {
    DEVICE("Device"),
    CIRCUIT("Circuit"),
    AREA("Area"),
}

/** The operation in progress on the canvas. Only one exists at a time. */
internal sealed interface EditOp {
    data object Idle : EditOp

    /** A span tool's first point, waiting for the second. */
    data class FirstPoint(val point: PlanPoint) : EditOp

    /** A route being traced, point by point. */
    data class Tracing(val points: List<PlanPoint>) : EditOp
}

/** The dialog open over the editor, if any. Only one exists at a time. */
internal sealed interface EditorDialog {
    data class NewZone(val first: PlanPoint, val second: PlanPoint) : EditorDialog
    data class Reference(val zoneId: String?, val first: PlanPoint, val second: PlanPoint) : EditorDialog
    data class PlaceText(val point: PlanPoint) : EditorDialog
    data object PickSymbol : EditorDialog
    data class PlaceCallout(val first: PlanPoint, val second: PlanPoint) : EditorDialog
    data class PlaceCloudCallout(val first: PlanPoint, val second: PlanPoint) : EditorDialog
    data class PlacePhoto(val point: PlanPoint) : EditorDialog
    data class ViewPhoto(val markId: String) : EditorDialog
    data class PlaceDetailLink(val point: PlanPoint) : EditorDialog
    data class EditDetailLink(val markId: String) : EditorDialog
    data class EditMarkStyle(val markId: String?) : EditorDialog
    data class EditMeasurement(val id: String) : EditorDialog
    data object Calibration : EditorDialog
    data object PageItems : EditorDialog
    data object Takeoff : EditorDialog
    data object SendRoute : EditorDialog
}

/**
 * Everything the drawing editor is doing, in one value. Changing page or tool goes through
 * [goToPage] and [withMode], which reset the operation and dialog in one place, so a half-placed
 * span or route never leaks onto another page or into another tool.
 */
internal data class EditorSession(
    val page: Int = 0,
    val mode: DrawingMode = DrawingMode.Adjust,
    val op: EditOp = EditOp.Idle,
    val dialog: EditorDialog? = null,
    val selectedZoneId: String? = null,
    /** A saved route to highlight (opened from a run with "View on plan"). */
    val focusedRouteId: String? = null,
) {
    val firstPoint: PlanPoint? get() = (op as? EditOp.FirstPoint)?.point
    val routePoints: List<PlanPoint> get() = (op as? EditOp.Tracing)?.points.orEmpty()

    fun goToPage(target: Int): EditorSession =
        if (target == page) this else EditorSession(page = target, mode = mode)

    fun withMode(next: DrawingMode): EditorSession =
        if (next == mode) this else copy(mode = next, op = EditOp.Idle, dialog = null)

    fun open(next: EditorDialog): EditorSession = copy(dialog = next)
    fun closeDialog(): EditorSession = copy(dialog = null)

    fun withRoute(points: List<PlanPoint>): EditorSession =
        copy(op = if (points.isEmpty()) EditOp.Idle else EditOp.Tracing(points))
}

/** Which end of which item an [EndpointHandle] moves. */
internal sealed interface EndpointTarget {
    data class Measurement(val id: String) : EndpointTarget
    data class Path(val id: String) : EndpointTarget
    data class DocumentReference(val index: Int) : EndpointTarget
    data class ZoneReference(val zoneId: String, val index: Int) : EndpointTarget
}

/** A draggable end of a measurement or known dimension; [end] is 0 for the first point, 1 for the second. */
internal data class EndpointHandle(val target: EndpointTarget, val end: Int, val point: PlanPoint)

/**
 * The result of an editor action: the next session, an optional drawing change (recorded for
 * undo), a message for the user, and a new measurement to offer to other tools.
 */
internal data class EditorOutcome(
    val session: EditorSession,
    val change: ((PlanDrawing) -> PlanDrawing)? = null,
    val message: String? = null,
    val published: Pair<PlanMeasurement, Quantity>? = null,
)

/** Pure editor rules, kept out of the composables so they can be tested on the JVM. */
internal object EditorActions {
    /** A single tap (or the end of a drag in the route tool) at [point]. */
    fun tap(
        session: EditorSession,
        drawing: PlanDrawing,
        point: PlanPoint,
        hitRadius: Double,
        style: MarkupToolStyle? = null,
    ): EditorOutcome =
        when (session.mode) {
            DrawingMode.Navigate -> EditorOutcome(session)
            DrawingMode.Text -> EditorOutcome(session.open(EditorDialog.PlaceText(point)))
            DrawingMode.Symbol -> EditorOutcome(session.open(EditorDialog.PickSymbol))
            DrawingMode.Photo -> EditorOutcome(session.open(EditorDialog.PlacePhoto(point)))
            DrawingMode.DetailLink -> EditorOutcome(session.open(EditorDialog.PlaceDetailLink(point)))
            DrawingMode.Adjust, DrawingMode.Eraser -> EditorOutcome(session)
            DrawingMode.Route -> addRoutePoint(session, drawing, point)
            DrawingMode.Pen, DrawingMode.Highlight -> EditorOutcome(session)
            else -> {
                val first = session.firstPoint
                if (first == null) EditorOutcome(session.copy(op = EditOp.FirstPoint(point)))
                else span(session, drawing, first, point, style)
            }
        }

    private fun addRoutePoint(session: EditorSession, drawing: PlanDrawing, point: PlanPoint): EditorOutcome {
        val points = session.routePoints
        val previous = points.lastOrNull() ?: return EditorOutcome(session.withRoute(listOf(point)))
        if (previous == point) return EditorOutcome(session)
        val page = session.page
        if (drawing.crossesZoneBoundary(page, previous, point)) {
            return EditorOutcome(session, message = "Split routes at override zone boundaries.")
        }
        if (drawing.calibrationFor(page, previous, point) == null) {
            val containing = drawing.zoneContaining(page, previous, point)
            return EditorOutcome(
                session,
                message = if (containing != null) "Calibrate “${containing.name}” before tracing inside it."
                else "Calibrate the sheet (or pick a preset scale) before tracing a route.",
            )
        }
        return EditorOutcome(session.withRoute(points + point))
    }

    /** Two points of a span tool, tapped or dragged. */
    fun span(
        session: EditorSession,
        drawing: PlanDrawing,
        first: PlanPoint,
        second: PlanPoint,
        style: MarkupToolStyle? = null,
    ): EditorOutcome {
        val cleared = session.copy(op = EditOp.Idle)
        if (first == second) return EditorOutcome(cleared)
        val page = session.page
        return when (session.mode) {
            DrawingMode.Callout -> EditorOutcome(cleared.open(EditorDialog.PlaceCallout(first, second)))
            DrawingMode.CloudCallout -> {
                if (first.x == second.x || first.y == second.y) {
                    EditorOutcome(cleared, message = "A revision cloud needs a width and height.")
                } else {
                    EditorOutcome(cleared.open(EditorDialog.PlaceCloudCallout(first, second)))
                }
            }
            DrawingMode.Zone -> {
                if (first.x == second.x || first.y == second.y) {
                    return EditorOutcome(cleared, message = "An override zone needs a width and height.")
                }
                val candidate = PlanZone(name = "Scale zone", page = page, first = first, second = second)
                if (drawing.zones.any { it.overlaps(candidate) }) {
                    EditorOutcome(cleared, message = "Override scale zones cannot overlap.")
                } else {
                    EditorOutcome(cleared.open(EditorDialog.NewZone(first, second)))
                }
            }
            DrawingMode.Reference -> {
                if (drawing.crossesZoneBoundary(page, first, second)) {
                    return EditorOutcome(cleared, message = "Keep a calibration dimension either inside one override zone or outside all zones.")
                }
                val containing = drawing.zoneContaining(page, first, second)
                EditorOutcome(
                    cleared.copy(selectedZoneId = containing?.id ?: cleared.selectedZoneId)
                        .open(EditorDialog.Reference(containing?.id, first, second)),
                )
            }
            DrawingMode.Measure -> {
                if (drawing.crossesZoneBoundary(page, first, second)) {
                    return EditorOutcome(cleared, message = "Split measurements at override zone boundaries.")
                }
                val containing = drawing.zoneContaining(page, first, second)
                val fit = drawing.calibrationFor(page, first, second)
                    ?: return EditorOutcome(
                        cleared,
                        message = if (containing != null) "Calibrate “${containing.name}” before measuring inside it."
                        else "Calibrate the sheet (or pick a preset scale) before measuring.",
                    )
                val saved = PlanMeasurement(page = page, zoneId = containing?.id, first = first, second = second)
                EditorOutcome(
                    cleared.copy(selectedZoneId = containing?.id ?: cleared.selectedZoneId),
                    change = { it.copy(measurements = it.measurements + saved) },
                    published = saved to fit.distance(first, second),
                )
            }
            DrawingMode.Dimension -> {
                if (drawing.crossesZoneBoundary(page, first, second)) {
                    return EditorOutcome(cleared, message = "Split dimensions at override zone boundaries.")
                }
                val raw = PlanMark(page = page, kind = PlanMarkKind.DIMENSION, points = listOf(first, second), arrowStyle = PlanArrowStyle.BOTH)
                val mark = style?.applyTo(raw) ?: raw
                val hasFit = drawing.dimensionLength(mark) != null
                EditorOutcome(
                    cleared,
                    change = { it.copy(marks = it.marks + mark) },
                    message = if (hasFit) null else "Placed dimension. Calibrate the sheet to show real lengths.",
                )
            }
            DrawingMode.Cloud -> {
                if (first.x == second.x || first.y == second.y) {
                    EditorOutcome(cleared)
                } else {
                    val raw = PlanMark(page = page, kind = PlanMarkKind.CLOUD, points = listOf(first, second))
                    val mark = style?.applyTo(raw) ?: raw
                    EditorOutcome(cleared, change = { it.copy(marks = it.marks + mark) })
                }
            }
            DrawingMode.Arrow, DrawingMode.Line, DrawingMode.Rectangle -> {
                val kind = when (session.mode) {
                    DrawingMode.Arrow -> PlanMarkKind.ARROW
                    DrawingMode.Rectangle -> PlanMarkKind.RECTANGLE
                    else -> PlanMarkKind.LINE
                }
                if (kind == PlanMarkKind.RECTANGLE && (first.x == second.x || first.y == second.y)) {
                    EditorOutcome(cleared)
                } else {
                    val raw = PlanMark(page = page, kind = kind, points = listOf(first, second))
                    val mark = style?.applyTo(raw) ?: raw
                    EditorOutcome(cleared, change = { it.copy(marks = it.marks + mark) })
                }
            }
            else -> EditorOutcome(cleared)
        }
    }

    /** Draggable ends on [page]: measurements, and the calibration dimensions when they're shown. */
    fun handles(drawing: PlanDrawing, page: Int, includeReferences: Boolean): List<EndpointHandle> = buildList {
        for (m in drawing.measurements) {
            if (m.page != page) continue
            add(EndpointHandle(EndpointTarget.Measurement(m.id), 0, m.first))
            add(EndpointHandle(EndpointTarget.Measurement(m.id), 1, m.second))
        }
        if (!includeReferences) return@buildList
        drawing.calibration.references.forEachIndexed { index, ref ->
            if (ref.page != page) return@forEachIndexed
            add(EndpointHandle(EndpointTarget.DocumentReference(index), 0, ref.first))
            add(EndpointHandle(EndpointTarget.DocumentReference(index), 1, ref.second))
        }
        for (zone in drawing.zones) {
            if (zone.page != page) continue
            zone.references.forEachIndexed { index, ref ->
                add(EndpointHandle(EndpointTarget.ZoneReference(zone.id, index), 0, ref.first))
                add(EndpointHandle(EndpointTarget.ZoneReference(zone.id, index), 1, ref.second))
            }
        }
    }

    /** The handle nearest [point] within [radius] page points. */
    fun handleAt(handles: List<EndpointHandle>, point: PlanPoint, radius: Double): EndpointHandle? =
        handles.minByOrNull { distance(it.point, point) }?.takeIf { distance(it.point, point) <= radius }

    /** Moves one end of a measurement or known dimension, keeping zone rules intact. */
    fun moveEndpoint(session: EditorSession, drawing: PlanDrawing, handle: EndpointHandle, point: PlanPoint): EditorOutcome {
        if (point == handle.point) return EditorOutcome(session)
        val page = session.page
        return when (val target = handle.target) {
            is EndpointTarget.Path -> {
                val path = drawing.paths.firstOrNull { it.id == target.id && it.page == page } ?: return EditorOutcome(session)
                val moved = path.moveVertex(handle.end, point)
                    ?: return EditorOutcome(session, message = "That move would make the path invalid.")
                EditorOutcome(session, change = { d -> d.copy(paths = d.paths.map { if (it == path) moved else it }) })
            }
            is EndpointTarget.Measurement -> {
                val m = drawing.measurements.firstOrNull { it.id == target.id } ?: return EditorOutcome(session)
                val first = if (handle.end == 0) point else m.first
                val second = if (handle.end == 1) point else m.second
                if (first == second) return EditorOutcome(session)
                if (drawing.crossesZoneBoundary(page, first, second)) {
                    return EditorOutcome(session, message = "Keep a measurement inside one override zone or outside all zones.")
                }
                val zoneId = drawing.zoneContaining(page, first, second)?.id
                val moved = m.copy(first = first, second = second, zoneId = zoneId)
                EditorOutcome(session, change = { d -> d.copy(measurements = d.measurements.map { if (it.id == m.id) moved else it }) })
            }
            is EndpointTarget.DocumentReference -> {
                val ref = drawing.calibration.references.getOrNull(target.index) ?: return EditorOutcome(session)
                val first = if (handle.end == 0) point else ref.first
                val second = if (handle.end == 1) point else ref.second
                if (first == second) return EditorOutcome(session)
                if (drawing.zones.any { it.page == ref.page && (it.contains(first) || it.contains(second)) }) {
                    return EditorOutcome(session, message = "A document dimension can't reach into an override zone.")
                }
                val moved = ref.copy(first = first, second = second)
                EditorOutcome(session, change = { d ->
                    d.copy(calibration = d.calibration.copy(references = d.calibration.references.replaced(target.index, moved)))
                })
            }
            is EndpointTarget.ZoneReference -> {
                val zone = drawing.zones.firstOrNull { it.id == target.zoneId } ?: return EditorOutcome(session)
                val ref = zone.references.getOrNull(target.index) ?: return EditorOutcome(session)
                val first = if (handle.end == 0) point else ref.first
                val second = if (handle.end == 1) point else ref.second
                if (first == second) return EditorOutcome(session)
                if (!zone.contains(first) || !zone.contains(second)) {
                    return EditorOutcome(session, message = "Keep the dimension inside “${zone.name}”.")
                }
                val moved: PlanReference = ref.copy(first = first, second = second)
                EditorOutcome(session, change = { d ->
                    d.copy(zones = d.zones.map { if (it.id == zone.id) it.copy(references = it.references.replaced(target.index, moved)) else it })
                })
            }
        }
    }

    /** The measurement whose line passes within [radius] page points of [point], nearest first. */
    fun measurementAt(measurements: List<PlanMeasurement>, point: PlanPoint, radius: Double): PlanMeasurement? =
        measurements.minByOrNull { segmentDistance(point, it.first, it.second) }
            ?.takeIf { segmentDistance(point, it.first, it.second) <= radius }

    internal fun segmentDistance(p: PlanPoint, a: PlanPoint, b: PlanPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0.0) return distance(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSq).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun distance(a: PlanPoint, b: PlanPoint) = hypot(a.x - b.x, a.y - b.y)

    private fun <T> List<T>.replaced(index: Int, value: T): List<T> = mapIndexed { i, item -> if (i == index) value else item }
}
