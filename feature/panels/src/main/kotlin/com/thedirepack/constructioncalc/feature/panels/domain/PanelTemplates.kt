package com.thedirepack.constructioncalc.feature.panels.domain

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.CircuitKind
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.PanelSystem
import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.design.PanelLayout
import com.thedirepack.constructioncalc.core.model.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private fun normalizePanelName(name: String): String = name.trim().ifEmpty { "Panel" }

private fun busSpaceStep(system: PanelSystem): Int = if (system.phases >= 3) 6 else 4

/**
 * Standard panelboard starting configuration that locks space counts to the panel's bus system
 * (multiples of 4 for single-phase, multiples of 6 for three-phase).
 */
data class PanelTemplate(
    val id: String,
    val label: String,
    val system: PanelSystem,
    val defaultSpaces: Int,
    val defaultBusAmps: Int,
    val defaultMainAmps: Int?,
) {
    /** Step between valid bus space counts for this template's phase configuration. */
    val spaceStep: Int get() = busSpaceStep(system)

    /** Valid bus-locked space counts for this template. */
    fun validSpaceCounts(): List<Int> = Panel.validSpaceCounts(system.phases)

    /** Creates a [Panel] from this template with spaces snapped to a valid bus count. */
    fun createPanel(
        name: String,
        spaces: Int = defaultSpaces,
        busAmps: Int? = defaultBusAmps,
        mainAmps: Int? = defaultMainAmps,
        location: String = "",
    ): Panel = Panel(
        name = normalizePanelName(name),
        location = location.trim(),
        system = system,
        spaces = Panel.snapSpacesToBus(spaces, system.phases),
        busAmps = busAmps,
        mainAmps = mainAmps,
    )
}

/** Curated North American panelboard templates for the new-panel dialog. */
object PanelTemplates {
    val RESIDENTIAL_120_240 = PanelTemplate(
        id = "residential_120_240",
        label = "Residential 120/240V 1φ (L1/L2)",
        system = PanelSystem.SINGLE_120_240,
        defaultSpaces = 40,
        defaultBusAmps = 200,
        defaultMainAmps = 200,
    )

    val COMMERCIAL_208Y_120 = PanelTemplate(
        id = "commercial_208y_120",
        label = "Commercial 208Y/120V 3φ (A/B/C)",
        system = PanelSystem.WYE_208_120,
        defaultSpaces = 42,
        defaultBusAmps = 225,
        defaultMainAmps = null,
    )

    val COMMERCIAL_480Y_277 = PanelTemplate(
        id = "commercial_480y_277",
        label = "Commercial 480Y/277V 3φ (A/B/C)",
        system = PanelSystem.WYE_480_277,
        defaultSpaces = 42,
        defaultBusAmps = 225,
        defaultMainAmps = null,
    )

    val DELTA_240 = PanelTemplate(
        id = "delta_240",
        label = "3φ Delta 240V",
        system = PanelSystem.DELTA_240,
        defaultSpaces = 42,
        defaultBusAmps = 225,
        defaultMainAmps = null,
    )

    val DELTA_480 = PanelTemplate(
        id = "delta_480",
        label = "3φ Delta 480V",
        system = PanelSystem.DELTA_480,
        defaultSpaces = 42,
        defaultBusAmps = 225,
        defaultMainAmps = null,
    )

    val all: List<PanelTemplate> = listOf(
        RESIDENTIAL_120_240,
        COMMERCIAL_208Y_120,
        COMMERCIAL_480Y_277,
        DELTA_240,
        DELTA_480,
    )

    val default: PanelTemplate = COMMERCIAL_208Y_120

    fun find(id: String): PanelTemplate? = all.firstOrNull { it.id == id }

    fun forSystem(system: PanelSystem): PanelTemplate =
        all.firstOrNull { it.system == system } ?: default
}

/** Groups starter and user-saved circuit schedule templates in the new-panel picker. */
@Serializable
enum class PanelTemplateCategory(val label: String) {
    GENERAL("General"),
    COMMERCIAL("Commercial"),
    RESIDENTIAL("Residential"),
    DISTRIBUTION("Distribution"),
    CUSTOM("Saved"),
}

/** Print and export layout variants for PDF and PNG panel schedules. */
@Serializable
enum class PanelExportLayout(val label: String, val summary: String) {
    BRANCH_STANDARD(
        "Standard two-column",
        "Odd/even branch panel with wire, load, breaker, and phase totals",
    ),
    PANEL_DOOR(
        "Panel door directory",
        "Larger circuit descriptions and breakers formatted for inside panel doors",
    ),
    DISTRIBUTION_SINGLE(
        "Single-column distribution",
        "One-column feeder and breaker schedule for distribution panels",
    ),
    ;

    companion object {
        /** Resolves a stored layout name, falling back to [BRANCH_STANDARD] when null or unknown. */
        fun fromName(name: String?): PanelExportLayout =
            entries.firstOrNull { it.name == name } ?: BRANCH_STANDARD
    }
}

/** Portable circuit specification stored inside a [SavedPanelTemplate] (without project-scoped IDs). */
@Serializable
data class TemplateCircuit(
    val slot: Int,
    val poles: Int = 1,
    val breakerAmps: Int = 20,
    val kind: CircuitKind = CircuitKind.LOAD,
    val description: String = "",
    val loadVa: Double? = null,
    val continuous: Boolean = false,
    val neutral: Boolean = true,
    val nonlinear: Boolean = false,
)

/**
 * Reusable starter or user-saved panel schedule that instantiates into a project [Panel],
 * always snapping space counts to the physical bus ([Panel.snapSpacesToBus]).
 */
@Serializable
data class SavedPanelTemplate(
    val id: String,
    val name: String,
    val category: PanelTemplateCategory = PanelTemplateCategory.CUSTOM,
    val system: PanelSystem = PanelSystem.WYE_208_120,
    val spaces: Int = 42,
    val busAmps: Int? = 225,
    val mainAmps: Int? = null,
    val circuits: List<TemplateCircuit> = emptyList(),
    val note: String = "",
    val builtIn: Boolean = false,
) {
    /** Step between valid bus space counts for this template's phase configuration. */
    val spaceStep: Int get() = busSpaceStep(system)

    /** Valid bus-locked space counts for this template's phase configuration. */
    fun validSpaceCounts(): List<Int> = Panel.validSpaceCounts(system.phases)

    /** [spaces] snapped to the nearest valid bus count for [system]. */
    val snappedSpaces: Int get() = Panel.snapSpacesToBus(spaces, system.phases)

    /** Counts how many template circuits would fall outside [targetSpaces] (after bus snapping). */
    fun clippedCircuitCount(targetSpaces: Int): Int {
        val snapped = Panel.snapSpacesToBus(targetSpaces, system.phases)
        return circuits.count { tc ->
            tc.poles < 1 || tc.slot < 1 || tc.slot + 2 * (tc.poles - 1) > snapped
        }
    }

    /**
     * Builds a new [Panel] named [panelName] with fresh IDs for the panel and every circuit,
     * snapping [spaces] to the physical bus via [Panel.snapSpacesToBus] and placing only valid,
     * non-overlapping circuits that fit within the snapped space count.
     */
    fun instantiate(
        panelName: String,
        spaces: Int = this.spaces,
        busAmps: Int? = this.busAmps,
        mainAmps: Int? = this.mainAmps,
        location: String = "",
    ): Panel {
        val safeSpaces = Panel.snapSpacesToBus(spaces, system.phases)
        var panel = Panel(
            id = newId(),
            name = normalizePanelName(panelName),
            location = location.trim(),
            system = system,
            spaces = safeSpaces,
            busAmps = busAmps?.takeIf { it > 0 },
            mainAmps = mainAmps?.takeIf { it > 0 },
            note = note,
        )
        for (tc in circuits) {
            if (system.phases == 1 && tc.poles > 2) continue
            if (system.lineToNeutral == null && tc.poles == 1 && tc.kind == CircuitKind.LOAD) continue
            if (!PanelLayout.canPlace(panel, tc.slot, tc.poles)) continue
            val amps = tc.breakerAmps.takeIf { it > 0 } ?: 20
            val validLoad = tc.loadVa?.takeIf { it.isFinite() && it >= 0.0 }
            val circuit = Circuit(
                id = newId(),
                slot = tc.slot,
                poles = tc.poles,
                breakerAmps = if (tc.kind == CircuitKind.SPACE) null else amps,
                description = tc.description,
                kind = tc.kind,
                loadVa = if (tc.kind == CircuitKind.LOAD) validLoad else null,
                continuous = tc.kind == CircuitKind.LOAD && tc.continuous,
                neutral = if (tc.kind == CircuitKind.SPACE) false else tc.neutral && system.lineToNeutral != null,
                nonlinear = tc.kind == CircuitKind.LOAD && tc.nonlinear,
            )
            panel = panel.copy(circuits = panel.circuits + circuit)
        }
        return panel.copy(circuits = panel.circuits.sortedBy { it.slot })
    }

    companion object {
        /**
         * Captures a reusable template from [panel], stripping project-specific links and IDs
         * and snapping [Panel.spaces] to the physical bus via [Panel.snapSpacesToBus].
         */
        fun fromPanel(panel: Panel, templateName: String, id: String = newId()): SavedPanelTemplate =
            SavedPanelTemplate(
                id = id,
                name = templateName.trim().ifEmpty { panel.name.ifBlank { "Saved template" } },
                category = PanelTemplateCategory.CUSTOM,
                system = panel.system,
                spaces = Panel.snapSpacesToBus(panel.spaces, panel.system.phases),
                busAmps = panel.busAmps,
                mainAmps = panel.mainAmps,
                circuits = panel.circuits.sortedBy { it.slot }.map { c ->
                    TemplateCircuit(
                        slot = c.slot,
                        poles = c.poles,
                        breakerAmps = c.breakerAmps ?: 20,
                        kind = c.kind,
                        description = c.description,
                        loadVa = c.loadVa,
                        continuous = c.continuous,
                        neutral = c.neutral,
                        nonlinear = c.nonlinear,
                    )
                },
                note = panel.note,
                builtIn = false,
            )
    }
}

/** JSON serialization for custom [SavedPanelTemplate] lists stored in tool settings. */
object PanelTemplatesCodec {
    private val listSerializer = ListSerializer(SavedPanelTemplate.serializer())

    fun encode(templates: List<SavedPanelTemplate>): String =
        ProjectCodec.json.encodeToString(listSerializer, templates)

    fun decode(raw: String): List<SavedPanelTemplate> {
        if (raw.isBlank()) return emptyList()
        return runCatching { ProjectCodec.json.decodeFromString(listSerializer, raw) }
            .getOrDefault(emptyList())
    }
}

/** Curated starter panel schedules covering common commercial, residential, and distribution boards. */
object BuiltInPanelTemplates {
    val BLANK_208Y = SavedPanelTemplate(
        id = "builtin-blank-208y",
        name = "Blank 208Y/120 V",
        category = PanelTemplateCategory.GENERAL,
        system = PanelSystem.WYE_208_120,
        spaces = 42,
        busAmps = 225,
        mainAmps = null,
        circuits = emptyList(),
        note = "Standard 42-space 208Y/120 V MLO panelboard ready for custom layout.",
        builtIn = true,
    )

    val COMMERCIAL_LIGHTING_RECEPTACLES = SavedPanelTemplate(
        id = "builtin-commercial-208y",
        name = "Commercial Lighting & Receptacles",
        category = PanelTemplateCategory.COMMERCIAL,
        system = PanelSystem.WYE_208_120,
        spaces = 42,
        busAmps = 225,
        mainAmps = 225,
        circuits = listOf(
            TemplateCircuit(1, poles = 1, breakerAmps = 20, description = "Lighting - Open Office North", loadVa = 1440.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(2, poles = 1, breakerAmps = 20, description = "Receptacles - Open Office North", loadVa = 1440.0, neutral = true),
            TemplateCircuit(3, poles = 1, breakerAmps = 20, description = "Lighting - Open Office South", loadVa = 1440.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(4, poles = 1, breakerAmps = 20, description = "Receptacles - Open Office South", loadVa = 1440.0, neutral = true),
            TemplateCircuit(5, poles = 1, breakerAmps = 20, description = "Lighting - Corridors & Lobby", loadVa = 1200.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(6, poles = 1, breakerAmps = 20, description = "Receptacles - Conference & Lobby", loadVa = 1440.0, neutral = true),
            TemplateCircuit(7, poles = 1, breakerAmps = 20, description = "Receptacles - Workstations A", loadVa = 1440.0, neutral = true, nonlinear = true),
            TemplateCircuit(8, poles = 1, breakerAmps = 20, description = "Copy / Print Alcove", loadVa = 1500.0, neutral = true, nonlinear = true),
            TemplateCircuit(9, poles = 1, breakerAmps = 20, description = "Receptacles - Workstations B", loadVa = 1440.0, neutral = true, nonlinear = true),
            TemplateCircuit(10, poles = 1, breakerAmps = 20, description = "Breakroom Microwave", loadVa = 1500.0, neutral = true),
            TemplateCircuit(11, poles = 1, breakerAmps = 20, description = "Receptacles - Workstations C", loadVa = 1440.0, neutral = true, nonlinear = true),
            TemplateCircuit(12, poles = 1, breakerAmps = 20, description = "Breakroom Refrigerator & Coffee", loadVa = 1440.0, neutral = true),
            TemplateCircuit(13, poles = 1, breakerAmps = 20, description = "IDF / IT Closet Dedicated", loadVa = 1440.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(14, poles = 1, breakerAmps = 20, description = "Restroom & Janitor GFCI", loadVa = 1080.0, neutral = true),
            TemplateCircuit(15, poles = 1, breakerAmps = 20, description = "Exterior & Exit Lighting", loadVa = 960.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(16, poles = 1, breakerAmps = 20, description = "Breakroom Countertop GFCI", loadVa = 1440.0, neutral = true),
            TemplateCircuit(17, poles = 1, breakerAmps = 20, description = "Lighting - Conference & Breakroom", loadVa = 1200.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(18, poles = 1, breakerAmps = 20, description = "Dedicated Plotter / Workroom", loadVa = 1440.0, neutral = true, nonlinear = true),
            TemplateCircuit(37, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(38, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(39, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(40, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(41, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(42, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
        ),
        note = "208Y/120 V branch panelboard starter with lighting, receptacles, copy/breakroom, and spares.",
        builtIn = true,
    )

    val COMMERCIAL_480_LIGHTING_HVAC = SavedPanelTemplate(
        id = "builtin-commercial-480y",
        name = "Commercial 480Y/277 V Lighting & HVAC",
        category = PanelTemplateCategory.COMMERCIAL,
        system = PanelSystem.WYE_480_277,
        spaces = 42,
        busAmps = 225,
        mainAmps = null,
        circuits = listOf(
            TemplateCircuit(1, poles = 1, breakerAmps = 20, description = "277V Lighting - Zone A (North)", loadVa = 3324.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(3, poles = 1, breakerAmps = 20, description = "277V Lighting - Zone B (Central)", loadVa = 3324.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(5, poles = 1, breakerAmps = 20, description = "277V Lighting - Zone C (South)", loadVa = 3324.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(7, poles = 1, breakerAmps = 20, description = "277V Warehouse High-Bay 1", loadVa = 3600.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(9, poles = 1, breakerAmps = 20, description = "277V Warehouse High-Bay 2", loadVa = 3600.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(11, poles = 1, breakerAmps = 20, description = "277V Site & Exterior Lighting", loadVa = 2770.0, continuous = true, neutral = true, nonlinear = true),
            TemplateCircuit(2, poles = 3, breakerAmps = 40, description = "RTU-1 Rooftop HVAC", loadVa = 22000.0, continuous = true, neutral = false),
            TemplateCircuit(8, poles = 3, breakerAmps = 40, description = "RTU-2 Rooftop HVAC", loadVa = 22000.0, continuous = true, neutral = false),
            TemplateCircuit(14, poles = 3, breakerAmps = 30, description = "MAU-1 Make-Up Air Unit", loadVa = 16000.0, continuous = true, neutral = false),
            TemplateCircuit(20, poles = 3, breakerAmps = 20, description = "Exhaust Fans EF-1 & EF-2", loadVa = 9600.0, continuous = true, neutral = false),
            TemplateCircuit(37, poles = 3, breakerAmps = 30, kind = CircuitKind.SPARE, neutral = false),
            TemplateCircuit(38, poles = 3, breakerAmps = 20, kind = CircuitKind.SPARE, neutral = false),
        ),
        note = "480Y/277 V commercial panel with continuous 277 V lighting, 3-pole HVAC units, and spares.",
        builtIn = true,
    )

    val RESIDENTIAL_MAIN_SERVICE = SavedPanelTemplate(
        id = "builtin-residential-main",
        name = "Residential Main Service",
        category = PanelTemplateCategory.RESIDENTIAL,
        system = PanelSystem.SINGLE_120_240,
        spaces = 40,
        busAmps = 200,
        mainAmps = 200,
        circuits = listOf(
            TemplateCircuit(1, poles = 1, breakerAmps = 20, description = "Kitchen Small Appliance #1", loadVa = 1500.0, neutral = true),
            TemplateCircuit(3, poles = 1, breakerAmps = 20, description = "Kitchen Small Appliance #2", loadVa = 1500.0, neutral = true),
            TemplateCircuit(5, poles = 1, breakerAmps = 20, description = "Laundry Receptacle", loadVa = 1500.0, neutral = true),
            TemplateCircuit(7, poles = 1, breakerAmps = 20, description = "Bathroom Receptacles (GFCI)", loadVa = 1500.0, neutral = true),
            TemplateCircuit(9, poles = 1, breakerAmps = 20, description = "Refrigerator", loadVa = 800.0, neutral = true),
            TemplateCircuit(11, poles = 1, breakerAmps = 20, description = "Dishwasher & Disposal", loadVa = 1600.0, neutral = true),
            TemplateCircuit(13, poles = 1, breakerAmps = 20, description = "Microwave (Built-In)", loadVa = 1500.0, neutral = true),
            TemplateCircuit(15, poles = 1, breakerAmps = 15, description = "Lighting - Main Floor", loadVa = 1200.0, continuous = true, neutral = true),
            TemplateCircuit(17, poles = 1, breakerAmps = 15, description = "Lighting - Bedrooms & Hall", loadVa = 1200.0, continuous = true, neutral = true),
            TemplateCircuit(19, poles = 1, breakerAmps = 15, description = "Living & Dining Receptacles", loadVa = 1440.0, neutral = true),
            TemplateCircuit(21, poles = 1, breakerAmps = 15, description = "Primary Bedroom Receptacles", loadVa = 1200.0, neutral = true),
            TemplateCircuit(23, poles = 1, breakerAmps = 15, description = "Bedrooms 2 & 3 Receptacles", loadVa = 1440.0, neutral = true),
            TemplateCircuit(25, poles = 1, breakerAmps = 20, description = "Garage & Exterior GFCI", loadVa = 1440.0, neutral = true),
            TemplateCircuit(27, poles = 1, breakerAmps = 15, description = "Smoke / CO & Furnace Controls", loadVa = 600.0, neutral = true),
            TemplateCircuit(2, poles = 2, breakerAmps = 50, description = "Electric Range", loadVa = 9600.0, neutral = true),
            TemplateCircuit(6, poles = 2, breakerAmps = 30, description = "Electric Clothes Dryer", loadVa = 5000.0, neutral = true),
            TemplateCircuit(10, poles = 2, breakerAmps = 30, description = "Electric Water Heater", loadVa = 4500.0, continuous = true, neutral = false),
            TemplateCircuit(14, poles = 2, breakerAmps = 30, description = "A/C Condenser", loadVa = 4800.0, continuous = true, neutral = false),
            TemplateCircuit(18, poles = 2, breakerAmps = 20, description = "Air Handler / Heat Pump Fan", loadVa = 2400.0, continuous = true, neutral = false),
            TemplateCircuit(37, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(38, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(39, poles = 1, breakerAmps = 15, kind = CircuitKind.SPARE),
            TemplateCircuit(40, poles = 1, breakerAmps = 15, kind = CircuitKind.SPARE),
        ),
        note = "120/240 V 1φ 200 A dwelling main service with required appliance, lighting, and HVAC circuits.",
        builtIn = true,
    )

    val RESIDENTIAL_SUBPANEL = SavedPanelTemplate(
        id = "builtin-residential-sub",
        name = "Residential Subpanel",
        category = PanelTemplateCategory.RESIDENTIAL,
        system = PanelSystem.SINGLE_120_240,
        spaces = 24,
        busAmps = 100,
        mainAmps = null,
        circuits = listOf(
            TemplateCircuit(1, poles = 1, breakerAmps = 20, description = "Garage Bench Receptacles #1 (GFCI)", loadVa = 1440.0, neutral = true),
            TemplateCircuit(3, poles = 1, breakerAmps = 20, description = "Garage Bench Receptacles #2 (GFCI)", loadVa = 1440.0, neutral = true),
            TemplateCircuit(5, poles = 1, breakerAmps = 20, description = "Garage Door Openers & Ceiling", loadVa = 960.0, neutral = true),
            TemplateCircuit(7, poles = 1, breakerAmps = 15, description = "Shop & Garage LED Lighting", loadVa = 900.0, continuous = true, neutral = true),
            TemplateCircuit(9, poles = 1, breakerAmps = 20, description = "Exterior Patio / Yard GFCI", loadVa = 1200.0, neutral = true),
            TemplateCircuit(2, poles = 2, breakerAmps = 50, description = "EV Charger / Welder Outlet", loadVa = 7680.0, continuous = true, neutral = false),
            TemplateCircuit(6, poles = 2, breakerAmps = 30, description = "Shop Air Compressor", loadVa = 3600.0, neutral = false),
            TemplateCircuit(10, poles = 2, breakerAmps = 20, description = "Mini-Split Heat Pump", loadVa = 2880.0, continuous = true, neutral = false),
            TemplateCircuit(21, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(22, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(23, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
            TemplateCircuit(24, poles = 1, breakerAmps = 20, kind = CircuitKind.SPARE),
        ),
        note = "120/240 V 1φ 100 A MLO garage, workshop, or addition subpanel.",
        builtIn = true,
    )

    val DISTRIBUTION_FEEDER = SavedPanelTemplate(
        id = "builtin-distribution-480y",
        name = "Distribution / Feeder Panel",
        category = PanelTemplateCategory.DISTRIBUTION,
        system = PanelSystem.WYE_480_277,
        spaces = 30,
        busAmps = 400,
        mainAmps = 400,
        circuits = listOf(
            TemplateCircuit(1, poles = 3, breakerAmps = 225, description = "Feeder - Panel HP-1 (480V)", loadVa = 96000.0, continuous = true, neutral = true),
            TemplateCircuit(7, poles = 3, breakerAmps = 150, description = "Feeder - Transformer T-1 (75 kVA)", loadVa = 60000.0, continuous = true, neutral = false),
            TemplateCircuit(13, poles = 3, breakerAmps = 100, description = "Feeder - Panel LP-1 (via XFMR)", loadVa = 42000.0, continuous = true, neutral = false),
            TemplateCircuit(19, poles = 3, breakerAmps = 100, kind = CircuitKind.SPARE, neutral = false),
            TemplateCircuit(25, poles = 3, breakerAmps = 100, kind = CircuitKind.SPARE, neutral = false),
            TemplateCircuit(2, poles = 3, breakerAmps = 150, description = "Feeder - Chiller / Cooling Plant", loadVa = 72000.0, continuous = true, neutral = false),
            TemplateCircuit(8, poles = 3, breakerAmps = 100, description = "Feeder - AHU-1 Mechanical", loadVa = 45000.0, continuous = true, neutral = false),
            TemplateCircuit(14, poles = 3, breakerAmps = 100, kind = CircuitKind.SPARE, neutral = false),
            TemplateCircuit(20, poles = 3, breakerAmps = 60, kind = CircuitKind.SPARE, neutral = false),
            TemplateCircuit(26, poles = 3, breakerAmps = 100, kind = CircuitKind.SPARE, neutral = false),
        ),
        note = "480Y/277 V 400 A main distribution board with 3-pole subpanel and equipment feeders.",
        builtIn = true,
    )

    val all: List<SavedPanelTemplate> = listOf(
        BLANK_208Y,
        COMMERCIAL_LIGHTING_RECEPTACLES,
        COMMERCIAL_480_LIGHTING_HVAC,
        RESIDENTIAL_MAIN_SERVICE,
        RESIDENTIAL_SUBPANEL,
        DISTRIBUTION_FEEDER,
    )

    /** Pre-populated starter circuit schedules (excluding the blank 208Y entry covered by [PanelTemplates.default]). */
    val schedules: List<SavedPanelTemplate> = all.filter { it.circuits.isNotEmpty() }
}
