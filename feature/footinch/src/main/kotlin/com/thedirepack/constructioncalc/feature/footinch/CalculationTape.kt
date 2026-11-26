package com.thedirepack.constructioncalc.feature.footinch

import com.thedirepack.constructioncalc.core.data.AppSettings
import com.thedirepack.constructioncalc.core.math.Rational
import com.thedirepack.constructioncalc.core.measure.CalcException
import com.thedirepack.constructioncalc.core.measure.Evaluator
import com.thedirepack.constructioncalc.core.measure.MeasureFormat
import com.thedirepack.constructioncalc.core.measure.MeasureLiteral
import com.thedirepack.constructioncalc.core.measure.Operator
import com.thedirepack.constructioncalc.core.measure.Quantity
import com.thedirepack.constructioncalc.core.measure.Token
import com.thedirepack.constructioncalc.core.measure.input.CalcInput
import com.thedirepack.constructioncalc.core.measure.input.CalcState
import com.thedirepack.constructioncalc.core.model.StoredQuantity
import com.thedirepack.constructioncalc.core.model.stored
import com.thedirepack.constructioncalc.core.ui.lengthFormat
import kotlinx.serialization.Serializable

/**
 * One step of a [CalcGroup]: an expression and its result. A step that continues from the step
 * before it starts with [SavedToken.PrevAnswer] (e.g. `[Ans, +, 2']`), so editing an earlier step
 * recomputes this one.
 */
@Serializable
data class CalcStep(
    val id: Long = 0L,
    val tokens: List<SavedToken> = emptyList(),
    val result: StoredQuantity,
    val preferInches: Boolean? = null,
    val label: String = "",
)

/** A run of linked calculation steps: everything from a fresh start until the next fresh start. */
@Serializable
data class CalcGroup(
    val id: Long = 0L,
    val steps: List<CalcStep> = emptyList(),
    val label: String = "",
    val favorite: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val finalStep: CalcStep? get() = steps.lastOrNull()
    val finalResult: StoredQuantity? get() = finalStep?.result
    val finalPreferInches: Boolean? get() = finalStep?.preferInches
}

/** One named, saved memory value. */
@Serializable
data class MemoryEntry(
    val id: Long = 0L,
    val value: StoredQuantity,
    val preferInches: Boolean? = null,
    val label: String = "",
)

sealed interface CascadeResult {
    data class Success(val steps: List<CalcStep>) : CascadeResult
    data class Failure(val stepIndex: Int, val message: String) : CascadeResult
}

sealed interface TapeEditResult {
    /** [group] is null when the edit removed the whole group. */
    data class Updated(val groups: List<CalcGroup>, val group: CalcGroup?) : TapeEditResult
    data class Invalid(val stepIndex: Int, val message: String) : TapeEditResult
}

data class GroupAppendOutcome(
    val groups: List<CalcGroup>,
    val appendedCount: Int,
)

/** How the step editor presents a step. */
sealed interface EditableStepSpec {
    /** A continuation `[Ans, op, operand…]` (e.g. `+ 5' 3"`): pick the operator, retype the operand. */
    data class BinaryContinuation(
        val operator: Operator,
        val operandTokens: List<SavedToken>,
    ) : EditableStepSpec

    /** An opening step or a compound expression. */
    data class FullExpression(
        val tokens: List<SavedToken>,
        val hasPrevAnswer: Boolean,
    ) : EditableStepSpec
}

/** Pure operations for the grouped calculation tape (unit-tested without Android). */
internal object CalculationTapeOps {
    const val MAX_RECENT_GROUPS = 100

    private val PLACEHOLDER: StoredQuantity = Quantity.number(Rational.ZERO).stored()

    /**
     * Splits an expression into linked steps when its top-level binary operators all share one
     * precedence (`5' + 3' + 2'` → `5'`, `+ 3'`, `+ 2'`), so each value can be edited on its own.
     * Mixed precedence (`5' + 3' × 2`) stays one step so the order of operations never changes.
     */
    fun decompose(tokens: List<Token>): List<List<SavedToken>> {
        val saved = tokens.map(SavedToken::ofForTape)
        if (saved.isEmpty()) return emptyList()

        val topOps = mutableListOf<Pair<Int, Operator>>()
        var depth = 0
        for (i in saved.indices) {
            when (val tok = saved[i]) {
                SavedToken.OpenParen -> depth++
                SavedToken.CloseParen -> if (depth > 0) depth--
                is SavedToken.Op -> if (depth == 0 && !isUnaryMinus(saved, i)) topOps += i to tok.operator
                else -> Unit
            }
        }
        if (topOps.isEmpty()) return listOf(saved)
        val precedence = topOps.first().second.precedence
        if (topOps.any { it.second.precedence != precedence }) return listOf(saved)

        val chunks = ArrayList<List<SavedToken>>(topOps.size + 1)
        var start = 0
        for ((idx, _) in topOps) {
            val chunk = saved.subList(start, idx)
            if (chunk.isEmpty()) return listOf(saved)
            chunks += chunk
            start = idx + 1
        }
        val tail = saved.subList(start, saved.size)
        if (tail.isEmpty()) return listOf(saved)
        chunks += tail

        // A later chunk using Ans refers to the answer before this expression: keep it whole.
        if (chunks.drop(1).any { SavedToken.PrevAnswer in it }) return listOf(saved)

        val steps = ArrayList<List<SavedToken>>(chunks.size)
        if (chunks.first() != listOf(SavedToken.PrevAnswer)) steps += chunks.first()
        for (i in topOps.indices) {
            steps += listOf(SavedToken.PrevAnswer, SavedToken.Op(topOps[i].second)) + chunks[i + 1]
        }
        return steps
    }

    /** Evaluates [steps] in order, feeding each result into the next step's `Ans`. */
    fun evaluateSteps(steps: List<CalcStep>): CascadeResult {
        var prevQty: Quantity? = null
        var prevPreferInches: Boolean? = null
        val evaluated = ArrayList<CalcStep>(steps.size)
        for (i in steps.indices) {
            val step = steps[i]
            try {
                val resolved = step.tokens.map { it.toToken(prevQty, prevPreferInches) }
                val qty = Evaluator.evaluate(resolved)
                val inches = CalcInput.prefersInches(resolved)
                evaluated += step.copy(result = qty.stored(), preferInches = inches)
                prevQty = qty
                prevPreferInches = inches
            } catch (e: CalcException) {
                return CascadeResult.Failure(i, "Step ${i + 1}: ${e.message ?: "Invalid calculation"}")
            }
        }
        return CascadeResult.Success(evaluated)
    }

    // A group's first step has nothing before it, so an answer carried in from elsewhere
    // (e.g. a project item or a cleared tape) is stored as a plain value.
    private fun snapshotPreviousAnswer(tokens: List<Token>): List<Token> = tokens.map { token ->
        if (token is Token.Value && token.fromPreviousAnswer) token.copy(fromPreviousAnswer = false) else token
    }

    private fun rawSteps(decomposed: List<List<SavedToken>>, firstId: Long): List<CalcStep> =
        decomposed.mapIndexed { idx, tokens -> CalcStep(id = firstId + idx, tokens = tokens, result = PLACEHOLDER) }

    fun startNewGroup(
        current: List<CalcGroup>,
        expressionTokens: List<Token>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Pair<List<CalcGroup>, CalcGroup> {
        val raw = rawSteps(decompose(snapshotPreviousAnswer(expressionTokens)), 1L)
        val evaluated = when (val cascade = evaluateSteps(raw)) {
            is CascadeResult.Success -> cascade.steps
            // Can't happen for a finished calculation; keep it as one step rather than lose it.
            is CascadeResult.Failure -> listOf(
                CalcStep(1L, snapshotPreviousAnswer(expressionTokens).map(SavedToken::of), Evaluator.evaluate(expressionTokens).stored()),
            )
        }
        val group = CalcGroup(id = nextGroupId(current), steps = assignStepIds(evaluated), updatedAt = nowMillis)
        return trimGroups(listOf(group) + current) to group
    }

    fun appendToGroup(
        current: List<CalcGroup>,
        groupId: Long,
        expressionTokens: List<Token>,
        nowMillis: Long = System.currentTimeMillis(),
    ): GroupAppendOutcome? {
        val target = current.firstOrNull { it.id == groupId } ?: return null
        val maxId = (target.steps.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L)
        val appended = rawSteps(decompose(expressionTokens), maxId + 1)
        val cascade = evaluateSteps(target.steps + appended) as? CascadeResult.Success ?: return null
        val updated = target.copy(steps = assignStepIds(cascade.steps), updatedAt = nowMillis)
        return GroupAppendOutcome(trimGroups(listOf(updated) + current.filterNot { it.id == groupId }), appended.size)
    }

    /** Replaces the last [replaceCount] steps of [groupId] with [expressionTokens] (re-opened with ⌫ and changed). */
    fun replaceLastSteps(
        current: List<CalcGroup>,
        groupId: Long,
        replaceCount: Int,
        expressionTokens: List<Token>,
        nowMillis: Long = System.currentTimeMillis(),
    ): GroupAppendOutcome? {
        val target = current.firstOrNull { it.id == groupId } ?: return null
        val prefix = target.steps.take((target.steps.size - replaceCount).coerceAtLeast(0))
        val tokens = if (prefix.isEmpty()) snapshotPreviousAnswer(expressionTokens) else expressionTokens
        val maxId = (prefix.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L)
        val replacement = rawSteps(decompose(tokens), maxId + 1)
        val cascade = evaluateSteps(prefix + replacement) as? CascadeResult.Success ?: return null
        val updated = target.copy(steps = assignStepIds(cascade.steps), updatedAt = nowMillis)
        return GroupAppendOutcome(trimGroups(listOf(updated) + current.filterNot { it.id == groupId }), replacement.size)
    }

    /** Replaces step [stepId]'s expression and recomputes every later step that builds on it. */
    fun editStep(
        current: List<CalcGroup>,
        groupId: Long,
        stepId: Long,
        newTokens: List<SavedToken>,
        nowMillis: Long = System.currentTimeMillis(),
    ): TapeEditResult {
        val group = current.firstOrNull { it.id == groupId }
            ?: return TapeEditResult.Invalid(0, "Calculation not found")
        val idx = group.steps.indexOfFirst { it.id == stepId }
        if (idx < 0) return TapeEditResult.Invalid(0, "Step not found")
        if (newTokens.isEmpty()) return TapeEditResult.Invalid(idx, "Step ${idx + 1}: Enter a value")
        if (idx == 0 && SavedToken.PrevAnswer in newTokens) {
            return TapeEditResult.Invalid(0, "Step 1: There is no earlier step to use")
        }
        val replaced = group.steps.mapIndexed { i, step -> if (i == idx) step.copy(tokens = newTokens) else step }
        return when (val cascade = evaluateSteps(replaced)) {
            is CascadeResult.Failure -> TapeEditResult.Invalid(cascade.stepIndex, cascade.message)
            is CascadeResult.Success -> {
                val updated = group.copy(steps = cascade.steps, updatedAt = nowMillis)
                TapeEditResult.Updated(current.map { if (it.id == groupId) updated else it }, updated)
            }
        }
    }

    /**
     * Removes step [stepId]. When the first step goes, a following `+ x` becomes `x` and `− x`
     * becomes `−x`; a following `×`/`÷` has nothing to work on, so that delete is refused.
     */
    fun deleteStep(
        current: List<CalcGroup>,
        groupId: Long,
        stepId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): TapeEditResult {
        val group = current.firstOrNull { it.id == groupId }
            ?: return TapeEditResult.Invalid(0, "Calculation not found")
        val idx = group.steps.indexOfFirst { it.id == stepId }
        if (idx < 0) return TapeEditResult.Invalid(0, "Step not found")

        val remaining = group.steps.filterNot { it.id == stepId }.toMutableList()
        if (remaining.isEmpty()) return TapeEditResult.Updated(current.filterNot { it.id == groupId }, null)

        if (idx == 0) {
            val first = remaining.first().tokens
            if (first.size >= 3 && first[0] == SavedToken.PrevAnswer && first[1] is SavedToken.Op) {
                val rest = first.drop(2)
                if (SavedToken.PrevAnswer !in rest) {
                    when ((first[1] as SavedToken.Op).operator) {
                        Operator.ADD -> remaining[0] = remaining[0].copy(tokens = rest)
                        Operator.SUBTRACT -> remaining[0] = remaining[0].copy(tokens = listOf(SavedToken.Op(Operator.SUBTRACT)) + rest)
                        Operator.MULTIPLY, Operator.DIVIDE -> Unit
                    }
                }
            }
            if (SavedToken.PrevAnswer in remaining.first().tokens) {
                return TapeEditResult.Invalid(1, "Step 2 needs the step before it. Edit or delete step 2 first.")
            }
        }

        return when (val cascade = evaluateSteps(remaining)) {
            is CascadeResult.Failure -> TapeEditResult.Invalid(cascade.stepIndex, cascade.message)
            is CascadeResult.Success -> {
                val updated = group.copy(steps = cascade.steps, updatedAt = nowMillis)
                TapeEditResult.Updated(current.map { if (it.id == groupId) updated else it }, updated)
            }
        }
    }

    fun classifyForEdit(step: CalcStep, stepIndex: Int): EditableStepSpec {
        val tokens = step.tokens
        if (stepIndex > 0 && tokens.size >= 3 && tokens[0] == SavedToken.PrevAnswer && tokens[1] is SavedToken.Op) {
            val operand = tokens.drop(2)
            if (SavedToken.PrevAnswer !in operand) {
                return EditableStepSpec.BinaryContinuation((tokens[1] as SavedToken.Op).operator, operand)
            }
        }
        return EditableStepSpec.FullExpression(tokens, SavedToken.PrevAnswer in tokens)
    }

    /** Indices of the numbers and measurements in [tokens] (what the step editor lets you retype one at a time). */
    fun editableValueIndices(tokens: List<SavedToken>): List<Int> =
        tokens.indices.filter { tokens[it] is SavedToken.Literal || tokens[it] is SavedToken.Value }

    /** Replaces the token at [index], wrapping a multi-token replacement in parentheses inside a longer expression. */
    fun replaceToken(tokens: List<SavedToken>, index: Int, replacement: List<SavedToken>): List<SavedToken> {
        if (index !in tokens.indices || replacement.isEmpty()) return tokens
        val wrapped = if (tokens.size > 1 && replacement.size > 1) {
            listOf(SavedToken.OpenParen) + replacement + SavedToken.CloseParen
        } else {
            replacement
        }
        return tokens.subList(0, index) + wrapped + tokens.subList(index + 1, tokens.size)
    }

    /** A step as entered: `5' 3 1/2"`, `+ 2' 8"`, `× 2`, `√(Ans² + 3'²)`. */
    fun formatStepInput(step: CalcStep, settings: AppSettings, prevPreferInches: Boolean?): String {
        val t = step.tokens
        if (t.size >= 3 && t[0] == SavedToken.PrevAnswer && t[1] is SavedToken.Op) {
            val rest = t.drop(2)
            if (SavedToken.PrevAnswer !in rest) {
                return "${(t[1] as SavedToken.Op).operator.symbol} ${formatTokens(rest, settings, prevPreferInches)}"
            }
        }
        return formatTokens(t, settings, prevPreferInches)
    }

    /** One line for a collapsed group: its name, else its first and last steps. */
    fun groupSummary(group: CalcGroup, settings: AppSettings): String {
        if (group.label.isNotBlank()) return group.label
        val steps = group.steps
        val first = steps.firstOrNull()?.let { formatStepInput(it, settings, null) }.orEmpty()
        if (steps.size <= 1) return first
        val last = formatStepInput(steps.last(), settings, steps[steps.lastIndex - 1].preferInches)
        return if (steps.size == 2) "$first $last" else "$first … $last"
    }

    fun formatTokens(tokens: List<SavedToken>, settings: AppSettings, prevPreferInches: Boolean?): String {
        val format = settings.lengthFormat(inferPreferInches(tokens, prevPreferInches))
        return buildString {
            tokens.forEachIndexed { i, token ->
                val previous = tokens.getOrNull(i - 1)
                val afterUnaryMinus = previous is SavedToken.Op && previous.operator == Operator.SUBTRACT &&
                    (i == 1 || tokens[i - 2] is SavedToken.Op || tokens[i - 2] == SavedToken.OpenParen || tokens[i - 2] == SavedToken.Sqrt)
                val noSpaceBefore = i == 0 || afterUnaryMinus || previous == SavedToken.OpenParen ||
                    previous == SavedToken.Sqrt || token == SavedToken.CloseParen || token == SavedToken.Square
                if (!noSpaceBefore) append(' ')
                when (token) {
                    is SavedToken.Literal -> append(MeasureLiteral.display(token.text))
                    is SavedToken.Value -> append(MeasureFormat.primary(token.quantity.toQuantity(), format))
                    SavedToken.PrevAnswer -> append("Ans")
                    is SavedToken.Op -> append(token.operator.symbol)
                    SavedToken.OpenParen -> append('(')
                    SavedToken.CloseParen -> append(')')
                    SavedToken.Sqrt -> append('√')
                    SavedToken.Square -> append('²')
                }
            }
        }
    }

    private fun inferPreferInches(tokens: List<SavedToken>, prevPreferInches: Boolean?): Boolean? {
        var sawInches = false
        for (token in tokens) {
            val prefers = when (token) {
                is SavedToken.Literal -> when {
                    MeasureLiteral.FEET in token.text -> false
                    MeasureLiteral.INCH in token.text -> true
                    else -> null
                }
                is SavedToken.Value -> token.preferInches
                SavedToken.PrevAnswer -> prevPreferInches
                else -> null
            }
            if (prefers == false) return false
            if (prefers == true) sawInches = true
        }
        return if (sawInches) true else null
    }

    /** Step [stepIndex]'s tokens with `Ans` resolved to the step before it, ready for the calculator. */
    fun resolvedTokensForStep(group: CalcGroup, stepIndex: Int): List<Token> {
        val step = group.steps.getOrNull(stepIndex) ?: return emptyList()
        val prev = group.steps.getOrNull(stepIndex - 1)
        return step.tokens.map { it.toToken(prev?.result?.toQuantity(), prev?.preferInches) }
    }

    fun toggleFavorite(current: List<CalcGroup>, groupId: Long): List<CalcGroup> =
        trimGroups(current.map { if (it.id == groupId) it.copy(favorite = !it.favorite) else it })

    fun setGroupLabel(current: List<CalcGroup>, groupId: Long, label: String): List<CalcGroup> =
        current.map { if (it.id == groupId) it.copy(label = label.trim()) else it }

    fun setStepLabel(current: List<CalcGroup>, groupId: Long, stepId: Long, label: String): List<CalcGroup> =
        current.map { g ->
            if (g.id != groupId) g else g.copy(steps = g.steps.map { s -> if (s.id == stepId) s.copy(label = label.trim()) else s })
        }

    fun removeGroup(current: List<CalcGroup>, groupId: Long): List<CalcGroup> = current.filterNot { it.id == groupId }

    /** Clears everything except starred groups. */
    fun clearRecent(current: List<CalcGroup>): List<CalcGroup> = current.filter { it.favorite }

    fun ensureUniqueIds(groups: List<CalcGroup>): List<CalcGroup> {
        val used = HashSet<Long>(groups.size)
        var nextId = (groups.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        return trimGroups(
            groups.filter { it.steps.isNotEmpty() }.map { g ->
                val gid = if (g.id > 0L && used.add(g.id)) {
                    g.id
                } else {
                    while (!used.add(nextId)) nextId++
                    nextId++
                }
                g.copy(id = gid, steps = assignStepIds(g.steps))
            },
        )
    }

    fun mergeGroups(current: List<CalcGroup>, restored: List<CalcGroup>, replace: Boolean): List<CalcGroup> {
        val combined = if (replace) {
            restored
        } else {
            (restored + current).distinctBy { g -> g.label to g.steps.map { it.tokens to it.result } }
        }
        return ensureUniqueIds(combined)
    }

    private fun assignStepIds(steps: List<CalcStep>): List<CalcStep> {
        val used = HashSet<Long>(steps.size)
        var nextId = (steps.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        return steps.map { step ->
            if (step.id > 0L && used.add(step.id)) {
                step
            } else {
                while (!used.add(nextId)) nextId++
                step.copy(id = nextId++)
            }
        }
    }

    private fun nextGroupId(groups: List<CalcGroup>): Long = (groups.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L

    /** Keeps every starred group and the newest [MAX_RECENT_GROUPS] others. */
    private fun trimGroups(groups: List<CalcGroup>): List<CalcGroup> {
        var recentCount = 0
        return groups.filter { g -> g.favorite || ++recentCount <= MAX_RECENT_GROUPS }
    }

    private fun isUnaryMinus(tokens: List<SavedToken>, i: Int): Boolean {
        if ((tokens[i] as? SavedToken.Op)?.operator != Operator.SUBTRACT) return false
        if (i == 0) return true
        val prev = tokens[i - 1]
        return prev is SavedToken.Op || prev is SavedToken.OpenParen || prev is SavedToken.Sqrt
    }
}

/**
 * How the calculator display relates to the tape: which group the next finished calculation joins,
 * how many steps the last `=` added, and how many steps a ⌫-reopened answer will replace.
 */
data class TapeLink(
    val activeGroupId: Long? = null,
    val lastAppendedCount: Int = 0,
    val editingLastStepCount: Int = 0,
) {
    companion object {
        val NONE = TapeLink()

        /**
         * The link after a key changed the calculator from [before] to [after] (before any `=` commit).
         * - Cleared display: no group.
         * - From a shown answer: chaining (operator, `x²`, `√`, `Ans`) keeps the group; ⌫ reopens the
         *   last expression to correct it; anything else (a digit, a recalled value) starts fresh.
         */
        fun afterKey(link: TapeLink, before: CalcState, after: CalcState): TapeLink = when {
            after.tokens.isEmpty() && after.answer == null -> NONE
            before.answer == null || after.answer != null -> link
            after.tokens.any { it is Token.Value && it.fromPreviousAnswer } && before.tokens != after.tokens ->
                link.copy(editingLastStepCount = 0)
            after.tokens == before.tokens && link.activeGroupId != null ->
                link.copy(editingLastStepCount = link.lastAppendedCount.coerceAtLeast(1))
            else -> NONE
        }
    }
}

sealed interface MemoryOpResult {
    data class Success(val entries: List<MemoryEntry>) : MemoryOpResult
    data class Error(val message: String) : MemoryOpResult
}

/**
 * Pure operations for named memory. The list is kept most-recently-used first: saving or recalling
 * a value moves it to the top, and `M+` / `M−` change the top one.
 */
internal object MemoryOps {
    const val MAX = 50

    /** The name a value gets when saved without one: the first free `M1`, `M2`, … */
    fun defaultName(current: List<MemoryEntry>): String {
        val used = current.map { it.label.trim().lowercase() }.toSet()
        var n = 1
        while ("m$n" in used) n++
        return "M$n"
    }

    /**
     * Saves [value] under [label] (blank: [defaultName]) at the top. Saving under a name that already
     * exists (ignoring case) replaces that value instead of adding a second one.
     */
    fun store(current: List<MemoryEntry>, value: Quantity, preferInches: Boolean?, label: String = ""): Pair<List<MemoryEntry>, Long> {
        val name = label.trim().ifEmpty { defaultName(current) }
        val existing = current.firstOrNull { it.label.equals(name, ignoreCase = true) }
        val id = existing?.id ?: ((current.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L)
        val entry = MemoryEntry(id = id, value = value.stored(), preferInches = preferInches, label = name)
        return (listOf(entry) + current.filterNot { it.id == id }).take(MAX) to id
    }

    /** `M+` / `M−`: adds [operand] to (or subtracts it from) the top value, starting one when there is none. */
    fun applyOperator(current: List<MemoryEntry>, op: Operator, operand: Quantity, operandPreferInches: Boolean?): MemoryOpResult {
        val target = current.firstOrNull()
            ?: return when (op) {
                Operator.ADD -> MemoryOpResult.Success(store(current, operand, operandPreferInches).first)
                Operator.SUBTRACT -> MemoryOpResult.Success(store(current, operand.copy(value = -operand.value), operandPreferInches).first)
                Operator.MULTIPLY, Operator.DIVIDE -> MemoryOpResult.Error("Save a value to memory first")
            }
        val updated = try {
            Evaluator.apply(op, target.value.toQuantity(), operand)
        } catch (e: CalcException) {
            return MemoryOpResult.Error(e.message ?: "Can't combine those units")
        }
        val prefers = when {
            target.preferInches == false || operandPreferInches == false -> false
            target.preferInches == true || operandPreferInches == true -> true
            else -> null
        }
        val next = target.copy(value = updated.stored(), preferInches = prefers)
        return MemoryOpResult.Success(current.map { if (it.id == target.id) next else it })
    }

    /** Moves [id] to the top (it was just recalled), so `M+` / `M−` work on it next. */
    fun touch(current: List<MemoryEntry>, id: Long): List<MemoryEntry> {
        val entry = current.firstOrNull { it.id == id } ?: return current
        return listOf(entry) + current.filterNot { it.id == id }
    }

    fun remove(current: List<MemoryEntry>, id: Long): List<MemoryEntry> = current.filterNot { it.id == id }

    /** Renames [id]; a blank name keeps the old one. */
    fun rename(current: List<MemoryEntry>, id: Long, label: String): List<MemoryEntry> {
        val clean = label.trim()
        if (clean.isEmpty()) return current
        return current.map { if (it.id == id) it.copy(label = clean) else it }
    }

    fun ensureUniqueIds(entries: List<MemoryEntry>): List<MemoryEntry> {
        val used = HashSet<Long>(entries.size)
        var nextId = (entries.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        var named = emptyList<MemoryEntry>()
        entries.take(MAX).forEach { entry ->
            val withId = if (entry.id > 0L && used.add(entry.id)) {
                entry
            } else {
                while (!used.add(nextId)) nextId++
                entry.copy(id = nextId++)
            }
            named = named + if (withId.label.isBlank()) withId.copy(label = defaultName(named)) else withId
        }
        return named
    }

    fun merge(current: List<MemoryEntry>, restored: List<MemoryEntry>, replace: Boolean): List<MemoryEntry> {
        val combined = if (replace) restored else (restored + current).distinctBy { Triple(it.value, it.preferInches, it.label) }
        return ensureUniqueIds(combined)
    }
}
