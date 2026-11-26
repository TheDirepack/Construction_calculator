package com.thedirepack.constructioncalc.core.data

/**
 * The trades the app is built for. Each trade brings its own calculators, formulas and code books;
 * only [ELECTRICAL] is built so far. Add a trade here first, then its tools and its code source.
 *
 * @property isAvailable false for trades that are planned but have no tools yet ("coming later")
 */
enum class Trade(val label: String, val summary: String, val isAvailable: Boolean) {
    ELECTRICAL("Electrical", "Wire sizing, conduit, voltage drop, panels, plans and NEC tables.", isAvailable = true),
    PLUMBING("Plumbing", "Coming later.", isAvailable = false),
    HVAC("HVAC", "Coming later.", isAvailable = false),
    ;

    companion object {
        val DEFAULT = ELECTRICAL

        /** The available trade named [name], or null (a planned trade can't be the active one yet). */
        fun parse(name: String?): Trade? = entries.firstOrNull { it.name == name && it.isAvailable }
    }
}
