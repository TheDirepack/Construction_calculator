package com.thedirepack.constructioncalc.feature.reference.content

/**
 * Every Reference entry. Add content by adding a [RefEntry] to one of the source lists:
 * - [CodeTables]: NEC tables, generated from `core:electrical` (never retype a table here);
 * - [FormulaSheet]: one page per formula in `FormulaCatalog`, added automatically;
 * - [FieldReference]: trade references that aren't code tables.
 */
object ReferenceCatalog {
    val all: List<RefEntry> by lazy {
        val list = FormulaSheet.entries + CodeTables.entries + FieldReference.entries
        val dupes = list.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(dupes.isEmpty()) { "Duplicate reference ids: $dupes" }
        list.sortedBy { it.section.ordinal }
    }

    private val byId: Map<String, RefEntry> by lazy { all.associateBy { it.id } }

    fun find(id: String): RefEntry? = byId[id]

    fun section(section: RefSection): List<RefEntry> = all.filter { it.section == section }

    /** Entries about a tool (its Reference action in the top bar lists these). */
    fun forTool(toolId: String): List<RefEntry> = all.filter { toolId in it.tools }

    fun hasEntriesFor(toolId: String): Boolean = all.any { toolId in it.tools }

    /** Entries matching every word of [query], best first. */
    fun search(query: String): List<RefEntry> {
        val words = searchWords(query)
        if (words.isEmpty()) return emptyList()
        return all.map { it to it.score(words) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }
}
