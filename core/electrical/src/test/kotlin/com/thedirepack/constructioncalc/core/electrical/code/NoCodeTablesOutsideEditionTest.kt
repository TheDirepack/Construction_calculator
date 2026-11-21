package com.thedirepack.constructioncalc.core.electrical.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard for the one-source rule: code tables live only in `code/` (the [ElectricalCode] editions).
 *
 * Scans every Kotlin file in `core/electrical/src/main` outside `code/` and fails if a collection
 * literal (`listOf(`, `mapOf(`, `doubleArrayOf(`…) holds [MAX_NUMBERS_PER_CALL] or more numeric
 * literals, which is what a pasted table looks like. Comments and string literals are ignored.
 *
 * It's a tripwire, not a proof: a single hard-coded factor (`* 1.25`) isn't caught here, so code
 * review still applies. Files that hold non-code data on purpose are allowlisted with the reason.
 */
class NoCodeTablesOutsideEditionTest {
    private val root = File("src/main/kotlin/com/thedirepack/constructioncalc/core/electrical")

    /** Paths relative to [root], with why they may hold numeric tables. */
    private val allowlist = mapOf(
        "PullFormulas.kt" to "IEEE 1185 pulling formulas, not code values",
        "PullRatings.kt" to "manufacturer guidance (Southwire), not code values",
        "AcPhases.kt" to "physics (√3, 2), not code values",
        "formula/" to "textbook formulas and unit factors, not code values",
    )

    private val collectionCall = Regex("""\b(listOf|mutableListOf|setOf|mapOf|linkedMapOf|hashMapOf|arrayOf|doubleArrayOf|intArrayOf|floatArrayOf|longArrayOf)\s*\(""")
    private val numberLiteral = Regex("""(?<![\w.])\d+(\.\d+)?([eE][-+]?\d+)?(?![\w.])""")

    @Test
    fun noTableLikeLiteralsOutsideTheEdition() {
        assertTrue("Run from the core/electrical module directory: ${root.absolutePath}", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it.startsWith("code/") }
            .filterNot { path -> allowlist.keys.any { path == it || (it.endsWith("/") && path.startsWith(it)) } }
            .flatMap { path -> tableLikeCalls(File(root, path).readText()).map { "$path: $it" } }
            .toList()
        assertEquals("Code values belong in code/ (an ElectricalCode edition), see docs/CODE_UPDATES.md", emptyList<String>(), offenders)
    }

    @Test
    fun theGuardCatchesATable() {
        assertCount(1, tableLikeCalls("val bands = listOf(1.29, 1.20, 1.15, 1.08)"))
        assertCount(0, tableLikeCalls("val sizes = listOf(AWG_14, AWG_12) // 1, 2, 3, 4"))
        assertCount(0, tableLikeCalls("""val s = listOf("12 AWG", "10 AWG", "8 AWG", "6 AWG")"""))
    }

    /** Short descriptions of each collection literal in [source] with too many numbers. */
    private fun tableLikeCalls(source: String): List<String> {
        val text = stripCommentsAndStrings(source)
        return collectionCall.findAll(text).mapNotNull { call ->
            val args = argumentsFrom(text, call.range.last)
            val count = numberLiteral.findAll(args).count()
            if (count >= MAX_NUMBERS_PER_CALL) "${call.groupValues[1]}(${args.take(40).replace('\n', ' ')}…) has $count numbers" else null
        }.toList()
    }

    /** The text between the "(" at [open] and its matching ")". */
    private fun argumentsFrom(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        return text.substring(open + 1)
    }

    private fun stripCommentsAndStrings(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex(""""(\\.|[^"\\\n])*""""), "\"\"")
        .replace(Regex("""//[^\n]*"""), " ")

    private fun assertCount(expected: Int, actual: List<String>) = assertEquals(actual.toString(), expected, actual.size)

    private companion object {
        const val MAX_NUMBERS_PER_CALL = 4
    }
}
