package com.thedirepack.constructioncalc.core.model

/** Accepted symbols of one device across a project's drawings. */
data class DeviceCount(
    val deviceId: String,
    val name: String,
    /** Catalog category, or null for an imported custom symbol (or an id the catalog no longer has). */
    val category: String?,
    val count: Int,
    val drawingNames: List<String>,
)

/**
 * Counting rules for symbol marks, shared by the drawing takeoff and the project material list:
 * only [PlanReviewState.ACCEPTED] symbols count; pending detections and rejected ones never do.
 */
object PlanSymbolCounts {
    fun accepted(drawing: PlanDrawing): List<PlanMark> =
        drawing.marks.filter { it.kind == PlanMarkKind.SYMBOL && it.symbol?.counts == true }

    fun pending(drawing: PlanDrawing): List<PlanMark> =
        drawing.marks.filter { it.kind == PlanMarkKind.SYMBOL && it.symbol?.reviewState == PlanReviewState.PENDING }

    /** The device's display name: catalog label, custom symbol name, or the raw id. */
    fun name(drawing: PlanDrawing?, deviceId: String, catalog: () -> DeviceCatalog = { DeviceCatalog.bundled }): String =
        if (deviceId.startsWith(PlanSymbolPlacement.CUSTOM_PREFIX)) {
            drawing?.customSymbols?.firstOrNull { it.id == deviceId }?.name ?: "Custom symbol"
        } else {
            catalog().find(deviceId)?.label ?: deviceId
        }

    /** Accepted symbols per device over every drawing in [project], catalog devices first in catalog order. */
    fun of(project: Project, catalog: () -> DeviceCatalog = { DeviceCatalog.bundled }): List<DeviceCount> {
        val counts = LinkedHashMap<String, Triple<String, Int, MutableList<String>>>()
        for (drawing in project.drawings) {
            for (mark in accepted(drawing)) {
                val symbol = mark.symbol ?: continue
                // Custom symbols are per drawing; the same name across drawings counts as one item.
                val key = if (symbol.isCustom) "custom-name:" + name(drawing, symbol.deviceId, catalog).trim().lowercase() else symbol.deviceId
                val (name, count, drawings) = counts[key] ?: Triple(name(drawing, symbol.deviceId, catalog), 0, mutableListOf())
                if (drawing.name !in drawings) drawings += drawing.name
                counts[key] = Triple(name, count + 1, drawings)
            }
        }
        if (counts.isEmpty()) return emptyList()
        val loaded = catalog()
        val order = loaded.devices.withIndex().associate { it.value.id to it.index }
        return counts.map { (key, value) ->
            val custom = key.startsWith("custom-name:")
            val device = if (custom) null else loaded.find(key)
            val id = if (custom) PlanSymbolPlacement.CUSTOM_PREFIX + key.removePrefix("custom-name:") else device?.id ?: key
            DeviceCount(id, value.first, device?.category, value.second, value.third)
        }.sortedWith(compareBy<DeviceCount>({ order[it.deviceId] ?: Int.MAX_VALUE }, { it.name.lowercase() }))
    }
}
