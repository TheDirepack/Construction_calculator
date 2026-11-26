package com.thedirepack.constructioncalc.feature.drawings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thedirepack.constructioncalc.core.data.DeviceGlyphs
import com.thedirepack.constructioncalc.core.data.PlanSymbol
import com.thedirepack.constructioncalc.core.data.SymbolLibraryMeta
import com.thedirepack.constructioncalc.core.data.drawPlanSymbol
import com.thedirepack.constructioncalc.core.model.CatalogDevice
import com.thedirepack.constructioncalc.core.model.DeviceCatalog
import com.thedirepack.constructioncalc.core.model.PlanCustomSymbol
import com.thedirepack.constructioncalc.core.model.PlanDrawing
import com.thedirepack.constructioncalc.core.model.PlanMark
import com.thedirepack.constructioncalc.core.model.PlanMarkKind
import com.thedirepack.constructioncalc.core.model.PlanPoint
import com.thedirepack.constructioncalc.core.model.PlanSymbolPlacement

/** Something the Symbol tool can place: a catalog device or an SVG imported on this device. */
internal sealed interface SymbolChoice {
    /** Library id: the catalog id, or the imported SVG's id in [PlanSymbol] storage. */
    val id: String
    val name: String

    /** The id a placed mark refers to. */
    val deviceId: String

    data class Device(val device: CatalogDevice) : SymbolChoice {
        override val id: String get() = device.id
        override val name: String get() = device.label
        override val deviceId: String get() = device.id
    }

    data class Imported(val symbol: PlanSymbol) : SymbolChoice {
        override val id: String get() = symbol.id
        override val name: String get() = symbol.name
        override val deviceId: String get() = PlanSymbolPlacement.CUSTOM_PREFIX + symbol.id
        val custom: PlanCustomSymbol get() = PlanCustomSymbol(deviceId, symbol.name.take(240), symbol.svg)
    }
}

internal sealed interface SymbolFilter {
    val label: String

    data object All : SymbolFilter { override val label = "All" }
    data object Favorites : SymbolFilter { override val label = "Favorites" }
    data object Recent : SymbolFilter { override val label = "Recent" }
    data object Imported : SymbolFilter { override val label = "Imported" }
    data class Category(val name: String) : SymbolFilter { override val label get() = name }
    data class UserSet(val name: String) : SymbolFilter { override val label get() = name }
}

/** Choices matching [filter] and every word of [query], in catalog order (recents in recent order). */
internal fun filterSymbolChoices(
    catalog: DeviceCatalog,
    imported: List<PlanSymbol>,
    meta: SymbolLibraryMeta,
    filter: SymbolFilter,
    query: String,
): List<SymbolChoice> {
    val matchingDevices = catalog.search(query, (filter as? SymbolFilter.Category)?.name).map(SymbolChoice::Device)
    val words = query.lowercase().split(' ').filter(String::isNotBlank)
    val matchingImported = imported.filter { symbol -> words.all { it in symbol.name.lowercase() } }.map(SymbolChoice::Imported)
    val all: List<SymbolChoice> = matchingDevices + matchingImported
    return when (filter) {
        SymbolFilter.All -> all
        SymbolFilter.Favorites -> all.filter { it.id in meta.favorites }
        SymbolFilter.Recent -> all.associateBy { it.id }.let { byId -> meta.recents.mapNotNull(byId::get) }
        SymbolFilter.Imported -> matchingImported
        is SymbolFilter.Category -> matchingDevices
        is SymbolFilter.UserSet -> meta.sets[filter.name].orEmpty().toSet().let { ids -> all.filter { it.id in ids } }
    }
}

/** Finds a saved library id (catalog id or imported SVG id) among [imported] and the catalog. */
internal fun resolveSymbolChoice(id: String, catalog: DeviceCatalog, imported: List<PlanSymbol>): SymbolChoice? =
    catalog.find(id)?.let(SymbolChoice::Device) ?: imported.firstOrNull { it.id == id }?.let(SymbolChoice::Imported)

/** A new accepted symbol mark centered on [center]; an imported SVG is copied onto the drawing once. */
internal fun placeSymbol(
    drawing: PlanDrawing,
    choice: SymbolChoice,
    page: Int,
    center: PlanPoint,
    size: Double,
    rotationDegrees: Double,
    circuitTag: String,
    areaTag: String,
): Pair<PlanDrawing, PlanMark> {
    val mark = PlanMark(
        page = page,
        kind = PlanMarkKind.SYMBOL,
        points = listOf(center),
        symbol = PlanSymbolPlacement(
            deviceId = choice.deviceId,
            size = size.coerceIn(PlanSymbolPlacement.MIN_SIZE, PlanSymbolPlacement.MAX_SIZE),
            rotationDegrees = PlanSymbolPlacement.normalizeDegrees(rotationDegrees),
        ),
        circuitTag = circuitTag,
        areaTag = areaTag,
    )
    val withArt = if (choice is SymbolChoice.Imported) drawing.withCustomSymbol(choice.custom) else drawing
    return withArt.copy(marks = withArt.marks + mark) to mark
}

/** Changes a symbol mark's device. Reclassifying a pending detection doesn't accept it. */
internal fun reclassifySymbol(drawing: PlanDrawing, mark: PlanMark, choice: SymbolChoice): PlanDrawing {
    val symbol = mark.symbol ?: return drawing
    val updated = mark.copy(symbol = symbol.copy(deviceId = choice.deviceId))
    val withArt = if (choice is SymbolChoice.Imported) drawing.withCustomSymbol(choice.custom) else drawing
    return withArt.copy(marks = withArt.marks.map { if (it.id == mark.id) updated else it })
}

@Composable
internal fun SymbolPreview(choice: SymbolChoice, size: Dp = 32.dp) {
    val paint = remember { android.graphics.Paint() }
    Canvas(Modifier.size(size)) {
        drawIntoCanvas {
            when (choice) {
                is SymbolChoice.Device -> DeviceGlyphs.drawDevice(it.nativeCanvas, choice.deviceId, 0f, 0f, this.size.minDimension, paint)
                is SymbolChoice.Imported -> drawPlanSymbol(it.nativeCanvas, choice.symbol.svg, 0f, 0f, this.size.width, this.size.height)
            }
        }
    }
}

/** Preview of whatever a placed mark refers to (catalog device or the drawing's custom SVG). */
@Composable
internal fun DevicePreview(deviceId: String, customSymbols: List<PlanCustomSymbol>, size: Dp = 28.dp) {
    val paint = remember { android.graphics.Paint() }
    Canvas(Modifier.size(size)) {
        drawIntoCanvas { DeviceGlyphs.drawDevice(it.nativeCanvas, deviceId, 0f, 0f, this.size.minDimension, paint, customSymbols) }
    }
}
