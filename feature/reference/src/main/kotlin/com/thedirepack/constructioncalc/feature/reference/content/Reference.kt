package com.thedirepack.constructioncalc.feature.reference.content

import com.thedirepack.constructioncalc.core.electrical.code.Code
import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.measure.UnitSystem

/** The Reference's top-level subjects, in browsing order. */
enum class RefSection(val label: String) {
    FORMULAS("Formula sheet"),
    CONDUCTORS("Conductors and ampacity"),
    PROTECTION("Overcurrent protection and grounding"),
    RACEWAY("Conduit and raceway"),
    BENDING("Conduit bending"),
    MOTORS("Motors"),
    DEVICES("Devices, plugs and wiring"),
    FIELD("Field reference"),
}

/** Where an entry's content comes from, shown under its title. */
sealed interface RefSource {
    val label: String

    /** A code table or section. Values come from the same data the calculators use. */
    data class CodeRef(val rules: List<CodeRule>) : RefSource {
        constructor(rule: CodeRule) : this(listOf(rule))

        override val label: String get() = "${Code.label} · ${rules.joinToString(", ") { it.cite }}"
    }

    /** Math: exact unless the entry says otherwise. */
    data object Math : RefSource {
        override val label = "Formula"
    }

    /** Common practice, standards outside the NEC, or typical manufacturer values. */
    data class Practice(val note: String = "Common practice; check local rules and manufacturer data") : RefSource {
        override val label: String get() = note
    }
}

/** One piece of an entry's page. */
sealed interface RefBlock {
    data class Paragraph(val text: String) : RefBlock
    data class Bullets(val items: List<String>) : RefBlock

    /** An equation shown large, e.g. "I = P ÷ (√3 × V × PF)". */
    data class Equation(val text: String) : RefBlock

    /**
     * A table. The first column is the row heading. [note] goes under it (units, blanks…).
     */
    data class Table(val columns: List<String>, val rows: List<List<String>>, val note: String? = null) : RefBlock {
        init {
            require(rows.all { it.size == columns.size }) { "Table rows must match ${columns.size} columns" }
        }
    }

    /**
     * A choice that changes what's shown (copper or aluminum, a conduit type), built when picked
     * so the big generated tables aren't all made up front.
     */
    class Choice(val label: String, val options: List<String>, val content: (Int) -> List<RefBlock>) : RefBlock

    /** A button to another tool: open it, optionally at a topic (e.g. a formula to solve). */
    data class ToolLink(val label: String, val toolId: String, val topic: String? = null) : RefBlock
}

/** What a page is built for: the user's unit system (tables show mm, mm², Ω/km in metric). */
data class RefContext(val system: UnitSystem = UnitSystem.IMPERIAL) {
    val metric: Boolean get() = system == UnitSystem.METRIC

    companion object {
        val IMPERIAL = RefContext(UnitSystem.IMPERIAL)
        val METRIC = RefContext(UnitSystem.METRIC)
    }
}

/**
 * One page of the Reference.
 *
 * @property id stable (favorites and links from tools are saved by it); never change one
 * @property tools tools this entry is about: their Reference action lists it
 * @property keywords extra words search should find it by
 */
class RefEntry(
    val id: String,
    val title: String,
    val section: RefSection,
    val summary: String,
    val source: RefSource,
    val tools: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    private val build: (RefContext) -> List<RefBlock>,
) {
    private val built = java.util.concurrent.ConcurrentHashMap<RefContext, List<RefBlock>>()

    /** The page's content for [context], built once per unit system. */
    fun blocks(context: RefContext = RefContext.IMPERIAL): List<RefBlock> = built.getOrPut(context) { build(context) }

    private val searchText: String by lazy {
        (listOf(title, summary, section.label, source.label) + keywords + tools).joinToString(" ").lowercase()
    }

    /**
     * How well this entry matches [words] (all must appear somewhere); 0 for no match. Title
     * matches rank above the rest.
     */
    fun score(words: List<String>): Int {
        if (words.isEmpty() || !words.all { it in searchText }) return 0
        val t = title.lowercase()
        return 1 + words.count { it in t } * 2 + if (t.startsWith(words.first())) 3 else 0
    }
}

/** Splits a search box's text into lowercase words. */
fun searchWords(query: String): List<String> =
    query.lowercase().split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }

/** `tool:<id>`: the Reference entries about one tool. */
object Topics {
    private const val TOOL_PREFIX = "tool:"

    fun forTool(toolId: String) = "$TOOL_PREFIX$toolId"

    fun toolOf(topic: String): String? = topic.takeIf { it.startsWith(TOOL_PREFIX) }?.removePrefix(TOOL_PREFIX)
}
