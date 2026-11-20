package com.thedirepack.constructioncalc.core.model

import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.Dimension
import com.thedirepack.constructioncalc.core.measure.Quantity
import kotlinx.serialization.Serializable

/**
 * What a value means. This is how tools find values they can use from each other: a length
 * field in Voltage Drop offers every [LENGTH] result from Foot-Inch, Conduit Bending, etc.
 *
 * Numeric kinds are stored exactly in their [baseUnit]. Lengths are inches (like
 * [Quantity]); tools convert for display.
 */
@Serializable
enum class ValueKind(val baseUnit: String) {
    NUMBER(""),
    LENGTH("in"),
    AREA("sq in"),
    VOLUME("cu in"),
    ANGLE("°"),
    PERCENT("%"),
    VOLTAGE("V"),
    CURRENT("A"),
    POWER("W"),
    APPARENT_POWER("VA"),
    RESISTANCE("Ω"),
    WEIGHT("lb"),
    FORCE("lbf"),
    PRESSURE("psi"),
    FLOW("gpm"),
    TEMPERATURE("°F"),
    /** Non-numeric results, e.g. a wire size "4/0 AWG" or a conduit "1-1/4\" EMT". */
    TEXT("");

    val isMeasure get() = this == NUMBER || this == LENGTH || this == AREA || this == VOLUME

    companion object {
        fun of(dimension: Dimension) = when (dimension) {
            Dimension.NUMBER -> NUMBER
            Dimension.LENGTH -> LENGTH
            Dimension.AREA -> AREA
            Dimension.VOLUME -> VOLUME
        }
    }
}

/**
 * One named output of a tool: the unit of exchange between tools, projects and shared files.
 *
 * @property key stable id within its tool, e.g. `"total_length"`, so a saved item can be re-read.
 * @property exact exact value in [ValueKind.baseUnit] as `"n"` or `"n/d"`; null for [ValueKind.TEXT].
 * @property text how it read when produced, e.g. `7' 11 7/8"` or `3.2 %`. Used when a reader
 *   (a person, or an older/newer app version) can't interpret [exact].
 */
@Serializable
data class ResultValue(
    val key: String,
    val label: String,
    val kind: ValueKind,
    val exact: String? = null,
    val text: String,
) {
    val rational: Rational? get() = exact?.let { runCatching { Rational.parse(it) }.getOrNull() }

    /** As a calculator [Quantity], if this is a number, length, area or volume. */
    fun toQuantity(): Quantity? {
        val value = rational ?: return null
        return when (kind) {
            ValueKind.NUMBER -> Quantity(value, Dimension.NUMBER)
            ValueKind.LENGTH -> Quantity(value, Dimension.LENGTH)
            ValueKind.AREA -> Quantity(value, Dimension.AREA)
            ValueKind.VOLUME -> Quantity(value, Dimension.VOLUME)
            else -> null
        }
    }

    companion object {
        fun of(key: String, label: String, quantity: Quantity, text: String) =
            ResultValue(key, label, ValueKind.of(quantity.dimension), quantity.value.toString(), text)

        fun number(key: String, label: String, kind: ValueKind, value: Rational, text: String) =
            ResultValue(key, label, kind, value.toString(), text)

        fun number(key: String, label: String, kind: ValueKind, value: Double, text: String) =
            number(key, label, kind, Rational.of(value), text)

        fun text(key: String, label: String, text: String) = ResultValue(key, label, ValueKind.TEXT, null, text)
    }
}

/** A [Quantity] in a serializable form, for tools' saved inputs. */
@Serializable
data class StoredQuantity(val exact: String, val dimension: Dimension) {
    fun toQuantity() = Quantity(Rational.parse(exact), dimension)

    companion object {
        fun of(q: Quantity) = StoredQuantity(q.value.toString(), q.dimension)
    }
}

fun Quantity.stored() = StoredQuantity.of(this)
