package com.thedirepack.constructioncalc.feature.footinch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thedirepack.constructioncalc.core.measure.MeasureLiteral
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Precision
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.measure.input.Calculation
import com.thedirepack.constructioncalc.core.measure.input.Key
import com.thedirepack.constructioncalc.core.ui.fractionText

/** Which numeric pad zone produced a tap in [DualSplitKeypad]. */
internal enum class DualPadZone { FEET, INCHES, NUMBER }

/** User actions emitted by [DualSplitKeypad]. */
internal sealed interface DualSplitAction {
    data class Digit(val zone: DualPadZone, val digit: Char) : DualSplitAction
    data class Dot(val zone: DualPadZone) : DualSplitAction
    data class Backspace(val zone: DualPadZone) : DualSplitAction
    data class Fraction(val numerator: Int, val denominator: Int) : DualSplitAction
    data object FractionBackspace : DualSplitAction
    data class Op(val operator: Operator) : DualSplitAction
    data object Equals : DualSplitAction
    data object Clear : DualSplitAction
}

/** Editable feet/inches/fraction components of an imperial [Token.Literal]. */
internal data class ImperialParts(
    val feet: String = "",
    val inches: String = "",
    val fraction: Pair<Int, Int>? = null,
)

/**
 * Pure-Kotlin translator from ArchCalc-style dual-split keypad taps into [CalcInput] [Key]
 * sequences. Handles independent editing of feet, whole inches, fractions, and plain numbers.
 */
internal object DualSplitKeyMapper {

    /** Generates reduced `(numerator, denominator)` fraction buttons grouped by precision tier. */
    fun fractionKeysFor(precision: Precision): List<Pair<Int, Int>> {
        val max = precision.denominator
        return (1 until max).map { n ->
            val g = gcd(n, max)
            (n / g) to (max / g)
        }.sortedWith(
            compareBy<Pair<Int, Int>> { tierForDenominator(it.second) }
                .thenBy { it.first.toDouble() / it.second },
        )
    }

    /** Extracts editable imperial components from [text], or null when not an imperial literal. */
    fun parseImperialParts(text: String): ImperialParts? {
        if (text.isEmpty()) return ImperialParts()
        val parsed = runCatching { MeasureLiteral.parts(text) }.getOrNull() ?: return null
        if (!parsed.isLength || parsed.metric != null) return null
        val frac = parsed.fraction?.let(::parseFractionPair)
        return ImperialParts(
            feet = parsed.feet.orEmpty(),
            inches = parsed.inches.orEmpty(),
            fraction = frac,
        )
    }

    /** Builds the [Key] sequence representing [parts] from an empty operand position. */
    fun buildImperialKeys(parts: ImperialParts): List<Key> = buildList {
        if (parts.feet.isNotEmpty()) {
            parts.feet.forEach { add(Key.Char(it)) }
            add(Key.Char(MeasureLiteral.FEET))
        }
        if (parts.inches.isNotEmpty()) {
            parts.inches.forEach { add(Key.Char(it)) }
            add(Key.Char(MeasureLiteral.INCH))
        }
        parts.fraction?.let { (n, d) -> add(Key.Fraction(n, d)) }
    }

    /** Translates [action] against [state] into [Key] events for [CalcInput.reduce]. */
    fun toKeys(state: CalcState, action: DualSplitAction): List<Key> = when (action) {
        is DualSplitAction.Op -> listOf(Key.Op(action.operator))
        DualSplitAction.Equals -> listOf(Key.Equals)
        DualSplitAction.Clear -> listOf(Key.Clear)
        is DualSplitAction.Fraction -> listOf(Key.Fraction(action.numerator, action.denominator))
        DualSplitAction.FractionBackspace -> fractionBackspaceKeys(state)
        is DualSplitAction.Digit -> digitKeys(state, action.zone, action.digit)
        is DualSplitAction.Dot -> dotKeys(state, action.zone)
        is DualSplitAction.Backspace -> backspaceKeys(state, action.zone)
    }

    /** Applies [action] to [state] via [toKeys] and [CalcInput.reduce]. */
    fun apply(state: CalcState, action: DualSplitAction): CalcInput.Outcome {
        val keys = toKeys(state, action)
        if (keys.isEmpty()) return CalcInput.Outcome(state.copy(error = null), accepted = false)
        var current = state
        var completed: Calculation? = null
        for (key in keys) {
            val outcome = CalcInput.reduce(current, key)
            if (!outcome.accepted) return CalcInput.Outcome(state.copy(error = null), accepted = false)
            current = outcome.state
            if (outcome.completed != null) completed = outcome.completed
        }
        return CalcInput.Outcome(current, completed = completed, accepted = true)
    }

    private fun digitKeys(state: CalcState, zone: DualPadZone, digit: Char): List<Key> {
        if (!digit.isDigit()) return emptyList()
        if (zone == DualPadZone.NUMBER) return numberDigitKeys(state, digit)
        return editImperial(state) { parts ->
            when (zone) {
                DualPadZone.FEET -> parts.copy(feet = appendDigit(parts.feet, digit))
                DualPadZone.INCHES -> parts.copy(inches = appendDigit(parts.inches, digit))
                DualPadZone.NUMBER -> null
            }
        }
    }

    private fun dotKeys(state: CalcState, zone: DualPadZone): List<Key> {
        if (zone == DualPadZone.NUMBER) return numberDotKeys(state)
        return editImperial(state) { parts ->
            when (zone) {
                DualPadZone.FEET -> appendDot(parts.feet)?.let { parts.copy(feet = it) }
                DualPadZone.INCHES -> appendDot(parts.inches)?.let { parts.copy(inches = it) }
                DualPadZone.NUMBER -> null
            }
        }
    }

    private fun backspaceKeys(state: CalcState, zone: DualPadZone): List<Key> {
        if (state.answer != null) return emptyList()
        val last = state.tokens.lastOrNull() as? com.thedirepack.constructioncalc.core.measure.Token.Literal
            ?: return emptyList()
        if (zone == DualPadZone.NUMBER) {
            val parsed = runCatching { MeasureLiteral.parts(last.text) }.getOrNull() ?: return emptyList()
            return if (!parsed.isLength) listOf(Key.Backspace) else emptyList()
        }
        val parts = parseImperialParts(last.text) ?: return emptyList()
        val updated = when (zone) {
            DualPadZone.FEET -> if (parts.feet.isEmpty()) return emptyList() else parts.copy(feet = parts.feet.dropLast(1))
            DualPadZone.INCHES -> if (parts.inches.isEmpty()) return emptyList() else parts.copy(inches = parts.inches.dropLast(1))
            DualPadZone.NUMBER -> return emptyList()
        }
        return replaceLastLiteralKeys(last.text.length, updated)
    }

    private fun fractionBackspaceKeys(state: CalcState): List<Key> {
        if (state.answer != null) return emptyList()
        val last = state.tokens.lastOrNull() as? com.thedirepack.constructioncalc.core.measure.Token.Literal
            ?: return emptyList()
        val parts = parseImperialParts(last.text) ?: return emptyList()
        if (parts.fraction == null) return emptyList()
        return replaceLastLiteralKeys(last.text.length, parts.copy(fraction = null))
    }

    private fun editImperial(state: CalcState, transform: (ImperialParts) -> ImperialParts?): List<Key> {
        val activeLiteral = if (state.answer != null) {
            null
        } else {
            state.tokens.lastOrNull() as? com.thedirepack.constructioncalc.core.measure.Token.Literal
        }
        val currentParts = activeLiteral?.let { parseImperialParts(it.text) } ?: ImperialParts()
        val updated = transform(currentParts) ?: return emptyList()
        return replaceLastLiteralKeys(activeLiteral?.text?.length ?: 0, updated)
    }

    private fun replaceLastLiteralKeys(existingLength: Int, updated: ImperialParts): List<Key> = buildList {
        repeat(existingLength) { add(Key.Backspace) }
        addAll(buildImperialKeys(updated))
    }

    private fun numberDigitKeys(state: CalcState, digit: Char): List<Key> {
        if (state.answer != null) return listOf(Key.Char(digit))
        val last = state.tokens.lastOrNull() as? com.thedirepack.constructioncalc.core.measure.Token.Literal
            ?: return listOf(Key.Char(digit))
        val parsed = runCatching { MeasureLiteral.parts(last.text) }.getOrNull()
        return when {
            parsed == null || parsed.isLength -> buildList {
                repeat(last.text.length) { add(Key.Backspace) }
                add(Key.Char(digit))
            }
            last.text == "0" -> listOf(Key.Backspace, Key.Char(digit))
            else -> listOf(Key.Char(digit))
        }
    }

    private fun numberDotKeys(state: CalcState): List<Key> {
        if (state.answer != null) return listOf(Key.Char('0'), Key.Char('.'))
        val last = state.tokens.lastOrNull() as? com.thedirepack.constructioncalc.core.measure.Token.Literal
            ?: return listOf(Key.Char('0'), Key.Char('.'))
        val parsed = runCatching { MeasureLiteral.parts(last.text) }.getOrNull()
        return when {
            parsed == null || parsed.isLength -> buildList {
                repeat(last.text.length) { add(Key.Backspace) }
                add(Key.Char('0'))
                add(Key.Char('.'))
            }
            '.' in last.text || '/' in last.text -> emptyList()
            else -> listOf(Key.Char('.'))
        }
    }

    private fun appendDigit(part: String, digit: Char): String =
        if (part == "0") digit.toString() else part + digit

    private fun appendDot(part: String): String? = when {
        '.' in part -> null
        part.isEmpty() -> "0."
        else -> "$part."
    }

    private fun parseFractionPair(text: String): Pair<Int, Int>? {
        val slash = text.indexOf('/')
        if (slash <= 0 || slash >= text.lastIndex) return null
        val n = text.substring(0, slash).toIntOrNull() ?: return null
        val d = text.substring(slash + 1).toIntOrNull() ?: return null
        if (d <= 0) return null
        return n to d
    }

    private fun tierForDenominator(denominator: Int): Int = when {
        denominator <= 8 -> 0
        denominator == 16 -> 1
        denominator == 32 -> 2
        else -> 3
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

private data class DualKeypadPalette(
    val feetContainer: Color,
    val onFeetContainer: Color,
    val feetAccent: Color,
    val inchesContainer: Color,
    val onInchesContainer: Color,
    val inchesAccent: Color,
    val inchesBorder: Color,
)

@Composable
private fun rememberDualKeypadPalette(): DualKeypadPalette {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(isDark) {
        if (isDark) {
            DualKeypadPalette(
                feetContainer = Color(0xFF1A3452),
                onFeetContainer = Color(0xFFD3E4FF),
                feetAccent = Color(0xFF9FCAFF),
                inchesContainer = Color(0xFF1A3B27),
                onInchesContainer = Color(0xFFCBEFD5),
                inchesAccent = Color(0xFF8BD8A3),
                inchesBorder = Color(0xFF3D704F),
            )
        } else {
            DualKeypadPalette(
                feetContainer = Color(0xFFD6E6FA),
                onFeetContainer = Color(0xFF0E3A66),
                feetAccent = Color(0xFF155CBD),
                inchesContainer = Color(0xFFD5EEDB),
                onInchesContainer = Color(0xFF0E4423),
                inchesAccent = Color(0xFF1B6E3A),
                inchesBorder = Color(0xFF88CC9B),
            )
        }
    }
}

/**
 * Responsive ArchCalc-style split dual keypad:
 * - Left: Feet numeric pad (tertiary/blue accent, square keys when [colorBlindShapes] is true)
 * - Middle: Inches numeric pad (secondary/green accent, rounded pill keys when [colorBlindShapes] is true)
 * - Right: Scrollable Fractions list + Operators column (`AC`, `÷`, `×`, `−`, `+`, `=`)
 */
@Composable
internal fun DualSplitKeypad(
    fractionPrecision: Precision,
    colorBlindShapes: Boolean,
    onAction: (DualSplitAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberDualKeypadPalette()
    val scheme = MaterialTheme.colorScheme
    var numberMode by remember { mutableStateOf(false) }
    val fractions = remember(fractionPrecision) { DualSplitKeyMapper.fractionKeysFor(fractionPrecision) }

    val dispatch: (DualSplitAction) -> Unit = { action ->
        when (action) {
            is DualSplitAction.Op, DualSplitAction.Equals, DualSplitAction.Clear, is DualSplitAction.Fraction -> {
                numberMode = false
            }
            else -> Unit
        }
        onAction(action)
    }

    val feetShape: Shape = if (colorBlindShapes) RoundedCornerShape(4.dp) else RoundedCornerShape(14.dp)
    val inchesShape: Shape = if (colorBlindShapes) RoundedCornerShape(50) else RoundedCornerShape(14.dp)

    BoxWithConstraints(modifier) {
        val wideLayout = maxWidth >= 520.dp
        val sectionGap = if (wideLayout) 10.dp else 6.dp
        val keyGap = if (wideLayout) 6.dp else 4.dp

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(sectionGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZoneHeaderBadge(
                    label = if (numberMode) "# NUMBER" else "FEET '",
                    active = !numberMode,
                    container = if (numberMode) scheme.surfaceContainerHigh else palette.feetContainer.copy(alpha = 0.7f),
                    content = if (numberMode) scheme.onSurfaceVariant else palette.feetAccent,
                    onClick = { numberMode = false },
                    modifier = Modifier.weight(1f),
                )
                ZoneHeaderBadge(
                    label = if (numberMode) "# NUMBER" else "INCHES \"",
                    active = !numberMode,
                    container = if (numberMode) scheme.surfaceContainerHigh else palette.inchesContainer.copy(alpha = 0.7f),
                    content = if (numberMode) scheme.onSurfaceVariant else palette.inchesAccent,
                    onClick = { numberMode = false },
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier.weight(if (wideLayout) 1.25f else 1.08f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = { numberMode = !numberMode },
                        shape = RoundedCornerShape(8.dp),
                        color = if (numberMode) scheme.primaryContainer else scheme.surfaceContainerHigh,
                        contentColor = if (numberMode) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .semantics {
                                role = Role.Switch
                                selected = numberMode
                                contentDescription = if (numberMode) "Plain number mode active. Tap for feet and inches." else "Plain number mode"
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (numberMode) "# On" else "# Num",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        "FRAC · OPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(sectionGap),
            ) {
                DualNumberPad(
                    zone = if (numberMode) DualPadZone.NUMBER else DualPadZone.FEET,
                    zoneLabel = if (numberMode) "number" else "feet",
                    badge = if (colorBlindShapes && !numberMode) "'" else null,
                    containerColor = if (numberMode) scheme.surfaceContainerHigh else palette.feetContainer,
                    contentColor = if (numberMode) scheme.onSurface else palette.onFeetContainer,
                    border = if (colorBlindShapes && !numberMode) BorderStroke(1.dp, palette.feetAccent.copy(alpha = 0.45f)) else null,
                    shape = if (numberMode) RoundedCornerShape(14.dp) else feetShape,
                    keyGap = keyGap,
                    onAction = dispatch,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                DualNumberPad(
                    zone = if (numberMode) DualPadZone.NUMBER else DualPadZone.INCHES,
                    zoneLabel = if (numberMode) "number" else "inches",
                    badge = if (colorBlindShapes && !numberMode) "\"" else null,
                    containerColor = if (numberMode) scheme.surfaceContainerHigh else palette.inchesContainer,
                    contentColor = if (numberMode) scheme.onSurface else palette.onInchesContainer,
                    border = if (colorBlindShapes && !numberMode) BorderStroke(1.dp, palette.inchesBorder) else null,
                    shape = if (numberMode) RoundedCornerShape(14.dp) else inchesShape,
                    keyGap = keyGap,
                    onAction = dispatch,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                FractionsAndOperatorsColumn(
                    fractions = fractions,
                    wideLayout = wideLayout,
                    keyGap = keyGap,
                    palette = palette,
                    fractionShape = inchesShape,
                    onAction = dispatch,
                    modifier = Modifier
                        .weight(if (wideLayout) 1.25f else 1.08f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun ZoneHeaderBadge(
    label: String,
    active: Boolean,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = !active,
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
        modifier = modifier.height(24.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DualNumberPad(
    zone: DualPadZone,
    zoneLabel: String,
    badge: String?,
    containerColor: Color,
    contentColor: Color,
    border: BorderStroke?,
    shape: Shape,
    keyGap: Dp,
    onAction: (DualSplitAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember {
        listOf(
            listOf('7', '8', '9'),
            listOf('4', '5', '6'),
            listOf('1', '2', '3'),
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(keyGap)) {
        rows.forEach { digits ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                digits.forEach { digit ->
                    DualPadKey(
                        label = digit.toString(),
                        description = "$zoneLabel $digit",
                        badge = badge,
                        containerColor = containerColor,
                        contentColor = contentColor,
                        border = border,
                        shape = shape,
                        onClick = { onAction(DualSplitAction.Digit(zone, digit)) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(keyGap)) {
            DualPadKey(
                label = ".",
                description = "$zoneLabel decimal point",
                badge = null,
                containerColor = containerColor,
                contentColor = contentColor,
                border = border,
                shape = shape,
                onClick = { onAction(DualSplitAction.Dot(zone)) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            DualPadKey(
                label = "0",
                description = "$zoneLabel 0",
                badge = badge,
                containerColor = containerColor,
                contentColor = contentColor,
                border = border,
                shape = shape,
                onClick = { onAction(DualSplitAction.Digit(zone, '0')) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            DualPadKey(
                label = "⌫",
                description = "Delete $zoneLabel",
                badge = null,
                containerColor = containerColor.copy(alpha = 0.72f),
                contentColor = contentColor,
                border = border,
                shape = shape,
                onClick = { onAction(DualSplitAction.Backspace(zone)) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun DualPadKey(
    label: String,
    description: String,
    badge: String?,
    containerColor: Color,
    contentColor: Color,
    border: BorderStroke?,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 24.sp),
            )
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun FractionsAndOperatorsColumn(
    fractions: List<Pair<Int, Int>>,
    wideLayout: Boolean,
    keyGap: Dp,
    palette: DualKeypadPalette,
    fractionShape: Shape,
    onAction: (DualSplitAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(keyGap)) {
        // Scrollable fractions column with a pinned fraction-backspace key at the top.
        Column(
            Modifier
                .weight(if (wideLayout) 1.35f else 1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(keyGap),
        ) {
            Surface(
                onClick = { onAction(DualSplitAction.FractionBackspace) },
                shape = fractionShape,
                color = palette.inchesContainer.copy(alpha = 0.72f),
                contentColor = palette.onInchesContainer,
                border = BorderStroke(1.dp, palette.inchesBorder.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .semantics { contentDescription = "Clear fraction" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "⌫ frac",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
            if (wideLayout) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(keyGap),
                    horizontalArrangement = Arrangement.spacedBy(keyGap),
                ) {
                    items(fractions, key = { (n, d) -> "$n/$d" }) { (n, d) ->
                        DualFractionKey(n, d, palette, fractionShape, onAction, Modifier.fillMaxWidth().height(40.dp))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(keyGap),
                ) {
                    items(fractions, key = { (n, d) -> "$n/$d" }) { (n, d) ->
                        DualFractionKey(n, d, palette, fractionShape, onAction, Modifier.fillMaxWidth().height(36.dp))
                    }
                }
            }
        }

        // Operators column: AC, ÷, ×, −, +, =
        Column(
            Modifier
                .weight(0.95f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(keyGap),
        ) {
            OperatorButton(
                label = "AC",
                description = "All clear",
                container = scheme.surfaceContainerHighest,
                content = scheme.error,
                onClick = { onAction(DualSplitAction.Clear) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            listOf(
                Operator.DIVIDE to "Divide",
                Operator.MULTIPLY to "Multiply",
                Operator.SUBTRACT to "Minus",
                Operator.ADD to "Plus",
            ).forEach { (op, desc) ->
                OperatorButton(
                    label = op.symbol,
                    description = desc,
                    container = scheme.tertiaryContainer,
                    content = scheme.onTertiaryContainer,
                    onClick = { onAction(DualSplitAction.Op(op)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            OperatorButton(
                label = "=",
                description = "Equals",
                container = scheme.primary,
                content = scheme.onPrimary,
                onClick = { onAction(DualSplitAction.Equals) },
                modifier = Modifier.fillMaxWidth().weight(1.15f),
            )
        }
    }
}

@Composable
private fun DualFractionKey(
    n: Int,
    d: Int,
    palette: DualKeypadPalette,
    shape: Shape,
    onAction: (DualSplitAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, border) = when {
        d <= 4 -> Triple(palette.inchesContainer, palette.onInchesContainer, null)
        d == 8 -> Triple(palette.inchesContainer.copy(alpha = 0.62f), palette.onInchesContainer, null)
        else -> Triple(scheme.surfaceContainer, scheme.onSurface, BorderStroke(1.dp, palette.inchesBorder))
    }
    Surface(
        onClick = { onAction(DualSplitAction.Fraction(n, d)) },
        shape = shape,
        color = container,
        contentColor = content,
        border = border,
        modifier = modifier.semantics { contentDescription = "$n over $d inch" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                fractionText("$n/$d"),
                fontWeight = if (d == 2) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 18.sp),
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun OperatorButton(
    label: String,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 24.sp),
            )
        }
    }
}
