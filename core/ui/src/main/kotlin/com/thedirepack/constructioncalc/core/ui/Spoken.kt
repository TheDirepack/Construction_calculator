package com.thedirepack.constructioncalc.core.ui

private val MIXED = Regex("""(\d+) (\d+)/(\d+)""")
private val FRACTION = Regex("""(\d+)/(\d+)""")
private val SPACES = Regex("""\s+""")

/**
 * How a measurement or result reads aloud, for screen readers: `5' 3 1/2"` → "5 feet 3 and 1 over 2
 * inches", `−2"` → "minus 2 inches", `12 °C` → "12 degrees C". Printed fractions and tick marks are
 * otherwise read as "slash" and "apostrophe".
 */
fun spokenText(text: String): String = text
    .replace("−", " minus ")
    .replace(MIXED, "$1 and $2 over $3")
    .replace(FRACTION, "$1 over $2")
    .replace("'", " feet ")
    .replace("\"", " inches ")
    .replace("°", " degrees ")
    .replace("²", " squared ")
    .replace("³", " cubed ")
    .replace("≈", " about ")
    .replace(SPACES, " ")
    .trim()
