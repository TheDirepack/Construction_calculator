package com.thedirepack.constructioncalc.feature.drawings

import android.content.SharedPreferences
import java.util.Locale

internal enum class SheetScope(val label: String) {
    ALL("All"), FAVORITES("Favorites"), RECENT("Recent"),
}

internal enum class SheetDiscipline(val label: String) {
    GENERAL("General"),
    CIVIL("Civil"),
    LANDSCAPE("Landscape"),
    STRUCTURAL("Structural"),
    ARCHITECTURAL("Architectural"),
    INTERIORS("Interiors"),
    MECHANICAL("Mechanical"),
    ELECTRICAL("Electrical"),
    PLUMBING("Plumbing"),
    FIRE_PROTECTION("Fire protection"),
    TELECOM("Telecommunications"),
    OTHER("Other"),
    UNCLASSIFIED("Unclassified"),
}

private val disciplineCode = Regex("""^([A-Z]{1,4})[- .]?\d""")

internal fun sheetDiscipline(label: SheetLabel?): SheetDiscipline {
    val code = label?.number?.uppercase(Locale.ROOT)?.let { disciplineCode.find(it)?.groupValues?.get(1) }
        ?: return SheetDiscipline.UNCLASSIFIED
    return when {
        code.startsWith("FP") || code.startsWith("FA") || code == "F" -> SheetDiscipline.FIRE_PROTECTION
        code.startsWith("HV") || code.startsWith("M") -> SheetDiscipline.MECHANICAL
        else -> when (code.first()) {
            'G' -> SheetDiscipline.GENERAL
            'C' -> SheetDiscipline.CIVIL
            'L' -> SheetDiscipline.LANDSCAPE
            'S' -> SheetDiscipline.STRUCTURAL
            'A' -> SheetDiscipline.ARCHITECTURAL
            'I' -> SheetDiscipline.INTERIORS
            'E' -> SheetDiscipline.ELECTRICAL
            'P' -> SheetDiscipline.PLUMBING
            'T' -> SheetDiscipline.TELECOM
            else -> SheetDiscipline.OTHER
        }
    }
}

internal data class SheetVisits(
    val favorites: Set<Int> = emptySet(),
    val recent: List<Int> = emptyList(),
) {
    fun visit(page: Int): SheetVisits = copy(recent = (listOf(page) + recent.filterNot { it == page }).take(25))

    fun toggleFavorite(page: Int): SheetVisits =
        copy(favorites = if (page in favorites) favorites - page else favorites + page)

    fun pages(count: Int, scope: SheetScope): Sequence<Int> = when (scope) {
        SheetScope.ALL -> (0 until count).asSequence()
        SheetScope.FAVORITES -> favorites.asSequence().filter { it in 0 until count }.sorted()
        SheetScope.RECENT -> recent.asSequence().filter { it in 0 until count }
    }
}

internal class SheetNavigationStore(private val views: SharedPreferences, private val key: String) {
    fun visits(): SheetVisits = SheetVisits(
        favorites = readPages("favorites").toSet(),
        recent = readPages("recent"),
    )

    fun saveRecent(visits: SheetVisits) {
        views.edit().putString("$key/recent", visits.recent.joinToString(",")).apply()
    }

    fun saveFavorites(visits: SheetVisits) {
        views.edit().putString("$key/favorites", visits.favorites.joinToString(",")).apply()
    }

    fun labels(): Map<Int, SheetLabel> {
        val prefix = "$key/label/"
        return views.all.mapNotNull { (name, value) ->
            if (!name.startsWith(prefix) || value !is String) return@mapNotNull null
            val page = name.removePrefix(prefix).toIntOrNull() ?: return@mapNotNull null
            page to SheetLabel(value.substringBefore('\n'), value.substringAfter('\n', ""))
        }.toMap()
    }

    fun saveLabel(page: Int, label: SheetLabel?) {
        val name = "$key/label/$page"
        val edit = views.edit()
        if (label == null) edit.remove(name) else edit.putString(name, "${label.number}\n${label.title}")
        edit.apply()
    }

    private fun readPages(field: String): List<Int> =
        views.getString("$key/$field", "").orEmpty().split(',').mapNotNull(String::toIntOrNull).distinct()
}

private val pdfWhitespace = Regex("""[\s\p{Z}]+""")

internal fun normalizeSheetText(text: String): String = text.replace(pdfWhitespace, " ").trim()
