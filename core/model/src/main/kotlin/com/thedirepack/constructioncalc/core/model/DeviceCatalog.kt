package com.thedirepack.constructioncalc.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One electrical device class. [id] is `<family>.<variant>` (e.g. `switch.three_way`), the same
 * id the symbol detector is trained on, so a detection, a hand-placed symbol and a takeoff line all
 * name the device the same way.
 */
data class CatalogDevice(
    val id: String,
    val label: String,
    val category: String,
    val family: String,
    val variant: String,
    /** How legend sheets describe this device; shown as help and searched. */
    val legendSamples: List<String>,
) {
    /** A 1–4 character tag for a generic glyph when the device has no drawn artwork. */
    val shortLabel: String get() = DeviceShortLabels.of(family, variant)

    internal val searchText: String =
        listOf(id, label, category, legendSamples.joinToString(" ")).joinToString(" ").lowercase()
}

/**
 * The device catalog: ml/symbol-detector/annotator/electrical_device_catalog.json is the single
 * source of truth. The build copies that file onto this module's classpath (see
 * core/model/build.gradle.kts); nothing here restates its device list.
 */
class DeviceCatalog private constructor(
    val devices: List<CatalogDevice>,
    /** Categories in catalog order. */
    val categories: List<String>,
    private val renamed: Map<String, String>,
    private val subtypeAliases: Map<String, Map<String, String>>,
    private val aliases: Map<String, String>,
) {
    private val byId: Map<String, CatalogDevice> = devices.associateBy { it.id }

    /** The device with exactly this id, following merges recorded in the catalog's `renamed` table. */
    fun find(id: String): CatalogDevice? = byId[id] ?: renamed[id]?.let(byId::get)

    /**
     * Snaps a class name from a detector (or any outside source) to a catalog device: an exact id,
     * a renamed/merged id, a family with a default subtype (`receptacle` → duplex,
     * `receptacle.gfci` → GFCI duplex), or a NECA / extracted-device alias. Returns null when it
     * names no device in the catalog; callers drop and count those.
     */
    fun snap(classId: String): CatalogDevice? {
        val raw = classId.trim()
        if (raw.isEmpty()) return null
        find(raw)?.let { return it }
        val key = normalize(raw)
        find(key)?.let { return it }
        aliases[key]?.let(byId::get)?.let { return it }
        val family = key.substringBefore('.')
        val subtype = if ('.' in key) key.substringAfter('.') else ""
        subtypeAliases[family]?.get(subtype)?.let { byId["$family.$it"] }?.let { return it }
        return null
    }

    /**
     * Devices whose id, name, category or legend wording contains every word of [query]
     * (case-insensitive), optionally only from [category]. A blank query lists everything.
     */
    fun search(query: String, category: String? = null): List<CatalogDevice> {
        val words = query.lowercase().split(' ', ',', '-', '/').map(String::trim).filter(String::isNotEmpty)
        return devices.filter { device ->
            (category == null || device.category == category) && words.all { it in device.searchText }
        }
    }

    companion object {
        /** Classpath location of the copy the build makes of the catalog. */
        const val RESOURCE = "/device-catalog/electrical_device_catalog.json"

        /** The bundled catalog, read once on first use (do that off the main thread). */
        val bundled: DeviceCatalog by lazy {
            val stream = DeviceCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: error("The device catalog is missing from the build ($RESOURCE).")
            parse(stream.use { it.readBytes().decodeToString() })
        }

        fun parse(text: String): DeviceCatalog {
            val root = Json.parseToJsonElement(text).jsonObject
            val version = root["format_version"]?.jsonPrimitive?.intOrNull
            require(version == 1) { "Unsupported device catalog format $version." }
            val devices = (root["devices"] as JsonArray).map { element ->
                val obj = element.jsonObject
                val family = obj.string("symbol_family")
                val variant = obj.string("symbol_variant")
                CatalogDevice(
                    id = "$family.$variant",
                    label = obj.string("label"),
                    category = obj.string("category"),
                    family = family,
                    variant = variant,
                    legendSamples = (obj["sample_legend_texts"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                )
            }
            require(devices.map { it.id }.toSet().size == devices.size) { "Duplicate device ids in the catalog." }
            val ids = devices.mapTo(HashSet()) { it.id }
            val categories = (root["categories"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .ifEmpty { devices.map { it.category }.distinct() }
            val naming = root["naming"] as? JsonObject
            val renamed = (naming?.get("renamed") as? JsonObject).orEmpty()
                .mapNotNull { (from, to) -> (to as? JsonPrimitive)?.contentOrNull?.takeIf { it in ids }?.let { from to it } }
                .toMap()
            val subtypes = (naming?.get("subtype_aliases") as? JsonObject).orEmpty().mapValues { (_, value) ->
                (value as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            }
            val aliases = HashMap<String, String>()
            for (element in root["devices"] as JsonArray) {
                val obj = element.jsonObject
                val id = obj.string("symbol_family") + "." + obj.string("symbol_variant")
                val groups = obj["aliases"] as? JsonObject ?: continue
                for (group in listOf("neca_v7", "devices_json")) {
                    (groups[group] as? JsonArray).orEmpty().forEach { alias ->
                        (alias as? JsonPrimitive)?.contentOrNull?.let { aliases.putIfAbsent(normalize(it), id) }
                    }
                }
            }
            return DeviceCatalog(devices, categories, renamed, subtypes, aliases)
        }

        private fun JsonObject.string(key: String): String =
            requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "Device catalog entry is missing $key." }

        /** `Receptacle/Duplex GFCI` → `receptacle.duplex_gfci`. */
        internal fun normalize(raw: String): String {
            val lowered = raw.trim().lowercase().replace('/', '.').replace('-', '_').replace(' ', '_')
            return lowered.split('.').joinToString(".") { part -> part.trim('_').replace(Regex("_+"), "_") }
        }
    }
}

/** Short glyph tags: NECA-style letters where one is customary, else the variant's initials. */
internal object DeviceShortLabels {
    private val known = mapOf(
        "receptacle.duplex" to "",
        "receptacle.duplex_gfci" to "GFI",
        "receptacle.duplex_gfci_weatherproof" to "GFWP",
        "receptacle.duplex_weatherproof" to "WP",
        "receptacle.quad" to "Q",
        "receptacle.single" to "1",
        "receptacle.usb_charger_duplex" to "USB",
        "receptacle.isolated_ground" to "IG",
        "receptacle.tamper_resistant" to "TR",
        "receptacle.special_purpose" to "SP",
        "receptacle.duplex_above_counter" to "AC",
        "receptacle.floor_mounted" to "F",
        "receptacle.ceiling_mounted" to "C",
        "switch.single_pole" to "S",
        "switch.double_pole" to "S2",
        "switch.three_way" to "S3",
        "switch.four_way" to "S4",
        "switch.dimmer" to "SD",
        "switch.three_way_dimmer" to "S3D",
        "switch.four_way_dimmer" to "S4D",
        "switch.pilot_light" to "SP",
        "switch.key_operated" to "SK",
        "switch.momentary" to "SM",
        "switch.time_switch" to "ST",
        "switch.occupancy_sensor" to "SOS",
        "switch.occupancy_sensor_dimming" to "SOSD",
        "switch.vacancy_sensor" to "SVS",
        "switch.low_voltage" to "SLV",
        "switch.low_voltage_dimmer" to "SLVD",
        "switch.wireless" to "SW",
        "switch.wireless_dimmer" to "SWD",
        "switch.wireless_multi_button" to "SWM",
        "switch.emergency_power_off_pushbutton" to "EPO",
        "sensor.thermostat" to "T",
        "sensor.humidistat" to "H",
        "sensor.co2_sensor" to "CO2",
        "sensor.co_sensor" to "CO",
        "sensor.photocell" to "PC",
        "sensor.daylight_photosensor" to "PS",
        "fire_alarm_device.smoke_detector" to "SD",
        "fire_alarm_device.duct_smoke_detector" to "DSD",
        "fire_alarm_device.heat_detector" to "HD",
        "fire_alarm_device.manual_pull_station" to "F",
        "fire_alarm_device.horn_and_strobe" to "HS",
        "fire_alarm_device.fire_alarm_strobe" to "S",
        "fire_alarm_device.speaker_and_strobe" to "SS",
        "fire_alarm_device.fire_alarm_control_panel" to "FACP",
        "fire_alarm_device.carbon_monoxide_detector" to "CO",
        "data_outlet.data" to "D",
        "data_outlet.wap" to "WAP",
        "data_outlet.telephone_outlet" to "T",
        "data_outlet.catv_outlet" to "TV",
        "data_outlet.voice_data_combo" to "VD",
        "luminaire.exit_sign" to "X",
        "motor_control.vfd" to "VFD",
        "panelboard.automatic_transfer_switch" to "ATS",
        "transformer.transformer" to "T",
        "junction_box.junction_box" to "J",
        "junction_box.pull_box" to "PB",
    )

    fun of(family: String, variant: String): String = known["$family.$variant"] ?: variant
        .split('_')
        .filter { it.isNotEmpty() && it !in setOf("and", "or", "of", "to", "with") }
        .joinToString("") { word -> if (word.any(Char::isDigit)) word.uppercase() else word.first().uppercase() }
        .take(4)
}
