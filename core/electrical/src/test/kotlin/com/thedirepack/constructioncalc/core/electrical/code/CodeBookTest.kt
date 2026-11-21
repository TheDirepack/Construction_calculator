package com.thedirepack.constructioncalc.core.electrical.code

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class CodeBookTest {
    @Test
    fun everyRuleHasACitationIn2023() {
        assertEquals(emptyList<CodeRule>(), Nec2023.missing)
    }

    @Test
    fun tablesAreCitedAsTables() {
        CodeRule.entries.filter { it.isTable }.forEach { rule ->
            assertTrue("$rule cites ${rule.cite}", rule.cite.contains("Table") || rule.cite.startsWith("Annex"))
        }
    }

    @Test
    fun citationsAreUnique() {
        val dupes = CodeRule.entries.groupBy { it.cite }.filterValues { it.size > 1 }
        assertEquals(emptyMap<String, List<CodeRule>>(), dupes)
    }

    @Test
    fun currentBookIs2023() {
        assertEquals("NEC 2023", Code.label)
        assertEquals("Table 310.16", CodeRule.AMPACITY_TABLE.cite)
    }
}
