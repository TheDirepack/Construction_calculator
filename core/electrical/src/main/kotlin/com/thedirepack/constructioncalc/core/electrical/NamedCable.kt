package com.thedirepack.constructioncalc.core.electrical

import kotlin.math.PI

/**
 * A user-defined reusable cable (measured or catalog data) that can be shared across Conduit Fill,
 * Wire Pull Planner, and Partial Reel Length.
 *
 * Stored in shared module settings (`SettingsRepository.module("cables")`) via [NamedCableCodec].
 *
 * @property id stable unique identifier
 * @property name display name (e.g. "3/C #12 MC", "500 kcmil XHHW-2 SIMpull")
 * @property diameterIn outside diameter in inches (> 0)
 * @property areaSqIn cross-sectional area in square inches (> 0); defaults to `π × (diameterIn / 2)²`
 * @property weightLbPerFt optional cable weight, lb/ft (> 0 if set)
 * @property maxTensionLb optional maximum pulling tension rating, lb (> 0 if set)
 * @property maxSwbpLbPerFt optional maximum sidewall bearing pressure rating, lb/ft (> 0 if set)
 * @property note optional user note (manufacturer, catalog number, etc.)
 */
data class NamedCable(
    val id: String,
    val name: String,
    val diameterIn: Double,
    val areaSqIn: Double = circularArea(diameterIn),
    val weightLbPerFt: Double? = null,
    val maxTensionLb: Double? = null,
    val maxSwbpLbPerFt: Double? = null,
    val note: String = "",
) {
    init {
        require(id.isNotBlank()) { "Cable id cannot be blank" }
        require(name.isNotBlank()) { "Cable name cannot be blank" }
        require(diameterIn.isFinite() && diameterIn > 0.0) { "Cable diameter must be positive" }
        require(areaSqIn.isFinite() && areaSqIn > 0.0) { "Cable area must be positive" }
        weightLbPerFt?.let { require(it.isFinite() && it > 0.0) { "Cable weight must be positive" } }
        maxTensionLb?.let { require(it.isFinite() && it > 0.0) { "Max tension must be positive" } }
        maxSwbpLbPerFt?.let { require(it.isFinite() && it > 0.0) { "Max SWBP must be positive" } }
    }

    /** Converts this cable into a [FillConductor.Custom] for conduit fill calculations. */
    fun toFillConductor(): FillConductor.Custom = FillConductor.Custom(name = name, diameterIn = diameterIn, areaSqIn = areaSqIn)

    companion object {
        /** Shared `SettingsRepository.module(...)` id where named cables are stored and backed up. */
        const val MODULE_ID = "cables"

        /** Settings key inside [MODULE_ID] storing the JSON array of [NamedCable]s. */
        const val SETTINGS_KEY = "named_cables_json"

        /** Cross-sectional area of a circular cable of [diameterIn], in². */
        fun circularArea(diameterIn: Double): Double {
            val r = diameterIn / 2.0
            return PI * r * r
        }
    }
}

/**
 * Zero-dependency JSON encoder/decoder for `List<NamedCable>` so `core:electrical` stays pure Kotlin
 * while storing clean, readable JSON in `SettingsRepository.module(NamedCable.MODULE_ID)`.
 */
object NamedCableCodec {
    fun encode(cables: List<NamedCable>): String = buildString {
        append('[')
        cables.forEachIndexed { idx, c ->
            if (idx > 0) append(',')
            append('{')
            append("\"id\":").append(quote(c.id))
            append(",\"name\":").append(quote(c.name))
            append(",\"diameterIn\":").append(c.diameterIn)
            append(",\"areaSqIn\":").append(c.areaSqIn)
            c.weightLbPerFt?.let { append(",\"weightLbPerFt\":").append(it) }
            c.maxTensionLb?.let { append(",\"maxTensionLb\":").append(it) }
            c.maxSwbpLbPerFt?.let { append(",\"maxSwbpLbPerFt\":").append(it) }
            if (c.note.isNotEmpty()) append(",\"note\":").append(quote(c.note))
            append('}')
        }
        append(']')
    }

    /** Decodes a JSON array produced by [encode]. Malformed entries are skipped rather than crashing. */
    fun decode(json: String): List<NamedCable> {
        val text = json.trim()
        if (text.isEmpty() || text == "[]") return emptyList()
        val parser = JsonReader(text)
        return runCatching { parser.readCableArray() }.getOrDefault(emptyList())
    }

    private fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private class JsonReader(private val s: String) {
        private var pos = 0

        fun readCableArray(): List<NamedCable> {
            skipWs()
            if (peek() != '[') return emptyList()
            pos++
            val result = mutableListOf<NamedCable>()
            skipWs()
            if (peek() == ']') {
                pos++
                return result
            }
            while (pos < s.length) {
                skipWs()
                readCableObject()?.let(result::add)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        break
                    }
                    else -> break
                }
            }
            return result
        }

        private fun readCableObject(): NamedCable? {
            skipWs()
            if (peek() != '{') return null
            pos++
            val fields = mutableMapOf<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return null
            }
            while (pos < s.length) {
                skipWs()
                val key = readString() ?: break
                skipWs()
                if (peek() != ':') break
                pos++
                skipWs()
                fields[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        break
                    }
                    else -> break
                }
            }
            val id = fields["id"] as? String ?: return null
            val name = fields["name"] as? String ?: return null
            val diameterIn = (fields["diameterIn"] as? Number)?.toDouble() ?: return null
            val areaSqIn = (fields["areaSqIn"] as? Number)?.toDouble() ?: NamedCable.circularArea(diameterIn)
            val weight = (fields["weightLbPerFt"] as? Number)?.toDouble()
            val tension = (fields["maxTensionLb"] as? Number)?.toDouble()
            val swbp = (fields["maxSwbpLbPerFt"] as? Number)?.toDouble()
            val note = (fields["note"] as? String).orEmpty()
            return runCatching {
                NamedCable(
                    id = id,
                    name = name,
                    diameterIn = diameterIn,
                    areaSqIn = areaSqIn,
                    weightLbPerFt = weight,
                    maxTensionLb = tension,
                    maxSwbpLbPerFt = swbp,
                    note = note,
                )
            }.getOrNull()
        }

        private fun readValue(): Any? = when (peek()) {
            '"' -> readString()
            'n' -> {
                if (s.startsWith("null", pos)) pos += 4
                null
            }
            else -> readNumber()
        }

        private fun readString(): String? {
            if (peek() != '"') return null
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val ch = s[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> if (pos < s.length) {
                        when (val esc = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            return null
        }

        private fun readNumber(): Double? {
            val start = pos
            while (pos < s.length && s[pos] in "0123456789+-.eE") pos++
            return s.substring(start, pos).toDoubleOrNull()
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
    }
}
