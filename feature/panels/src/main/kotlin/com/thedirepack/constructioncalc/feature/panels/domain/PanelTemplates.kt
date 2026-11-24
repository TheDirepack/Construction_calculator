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

/** Groups panel templates in the new-panel picker. */
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

/** Portable circuit specification stored inside a [PanelTemplate] (without project-scoped IDs). */
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

/** Reusable starter or user-saved panel layout that instantiates into a project [Panel]. */
@Serializable
data class PanelTemplate(
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
    /**
     * Builds a new [Panel] named [panelName] with fresh IDs for the panel and every circuit,
     * placing only valid, non-overlapping circuits that fit within [spaces].
     */
    fun instantiate(panelName: String): Panel {
        val safeSpaces = spaces.coerceIn(1, Panel.MAX_SPACES)
        var panel = Panel(
            id = newId(),
            name = panelName.trim().ifEmpty { "Panel" },
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
        /** Captures a reusable template from [panel], stripping project-specific links and IDs. */
        fun fromPanel(panel: Panel, templateName: String, id: String = newId()): PanelTemplate =
            PanelTemplate(
                id = id,
                name = templateName.trim().ifEmpty { panel.name.ifBlank { "Saved template" } },
                category = PanelTemplateCategory.CUSTOM,
                system = panel.system,
                spaces = panel.spaces,
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

/** JSON serialization for custom [PanelTemplate] lists stored in tool settings. */
object PanelTemplatesCodec {
    private val listSerializer = ListSerializer(PanelTemplate.serializer())

    fun encode(templates: List<PanelTemplate>): String =
        ProjectCodec.json.encodeToString(listSerializer, templates)

    fun decode(raw: String): List<PanelTemplate> {
        if (raw.isBlank()) return emptyList()
        return runCatching { ProjectCodec.json.decodeFromString(listSerializer, raw) }
            .getOrDefault(emptyList())
    }
}

/** Curated starter panel schedules covering common commercial, residential, and distribution boards. */
object BuiltInPanelTemplates {
    val BLANK_208Y = PanelTemplate(
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

    val COMMERCIAL_LIGHTING_RECEPTACLES = PanelTemplate(
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

    val COMMERCIAL_480_LIGHTING_HVAC = PanelTemplate(
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

    val RESIDENTIAL_MAIN_SERVICE = PanelTemplate(
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

    val RESIDENTIAL_SUBPANEL = PanelTemplate(
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

    val DISTRIBUTION_FEEDER = PanelTemplate(
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

    val all: List<PanelTemplate> = listOf(
        BLANK_208Y,
        COMMERCIAL_LIGHTING_RECEPTACLES,
        COMMERCIAL_480_LIGHTING_HVAC,
        RESIDENTIAL_MAIN_SERVICE,
        RESIDENTIAL_SUBPANEL,
        DISTRIBUTION_FEEDER,
    )
}
