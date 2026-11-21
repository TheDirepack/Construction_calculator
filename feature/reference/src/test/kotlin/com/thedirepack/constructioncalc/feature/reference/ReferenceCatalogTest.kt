package com.thedirepack.constructioncalc.feature.reference

import com.thedirepack.constructioncalc.core.electrical.code.CodeRule
import com.thedirepack.constructioncalc.core.electrical.formula.FormulaCatalog
import com.thedirepack.constructioncalc.core.ui.tool.ToolIds
import com.thedirepack.constructioncalc.feature.reference.content.RefBlock
import com.thedirepack.constructioncalc.feature.reference.content.RefEntry
import com.thedirepack.constructioncalc.feature.reference.content.RefSource
import com.thedirepack.constructioncalc.feature.reference.content.ReferenceCatalog
import com.thedirepack.constructioncalc.feature.reference.content.Topics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceCatalogTest {
    /** Every block, with every option of every choice expanded. */
    private fun expand(blocks: List<RefBlock>): List<RefBlock> = blocks.flatMap { b ->
        if (b is RefBlock.Choice) listOf(b) + b.options.indices.flatMap { expand(b.content(it)) } else listOf(b)
    }

    private fun tables(e: RefEntry) = expand(e.blocks).filterIsInstance<RefBlock.Table>()

    @Test
    fun everyEntryBuildsAndHasContent() {
        ReferenceCatalog.all.forEach { e ->
            val blocks = expand(e.blocks)
            assertTrue("${e.id} is empty", blocks.isNotEmpty())
            tables(e).forEach { t -> assertTrue("${e.id} has an empty table", t.rows.isNotEmpty()) }
        }
    }

    @Test
    fun everyFormulaHasAPage() {
        FormulaCatalog.all.forEach { f ->
            val entry = ReferenceCatalog.find("formula:${f.id}")
            assertTrue(f.id, entry != null)
            assertTrue(expand(entry!!.blocks).any { it is RefBlock.ToolLink && it.toolId == ToolIds.FORMULAS && it.topic == f.id })
        }
    }

    @Test
    fun everyCodeTableTheEnginesUseIsInTheReference() {
        val cited = ReferenceCatalog.all.map { it.source }.filterIsInstance<RefSource.CodeRef>().flatMap { it.rules }.toSet()
        val tables = CodeRule.entries.filter { it.isTable } - CodeRule.COMPACT_CONDUCTOR_DIMENSIONS // not in the app's data yet
        assertEquals(emptyList<CodeRule>(), tables.filter { it !in cited })
    }

    @Test
    fun ampacityTableMatchesTheBook() {
        val copper = tables(ReferenceCatalog.find("nec-ampacity")!!).first()
        val row12 = copper.rows.first { it[0] == "12 AWG" }
        assertEquals(listOf("12 AWG", "20", "25", "30"), row12)
        assertTrue(copper.rows.first { it[0] == "18 AWG" }.containsAll(listOf("—", "14")))
    }

    @Test
    fun ambientRangesAreContiguous() {
        val table = tables(ReferenceCatalog.find("nec-ambient")!!).first()
        assertEquals(listOf("10 or less", "50 or less", "1.29", "1.2", "1.15"), table.rows.first())
        assertEquals(listOf("31–35", "87–95", "0.91", "0.94", "0.96"), table.rows[5])
        assertEquals(listOf("81–85", "177–185", "—", "—", "0.29"), table.rows.last())
    }

    @Test
    fun wiresInConduitMatchAnnexC() {
        // Annex C Table C.1: 3/4" EMT holds 16 × 12 AWG THHN; 1/2" EMT holds 9.
        val annex = ReferenceCatalog.find("nec-conductors-in-conduit")!!
        val emt = annex.blocks.filterIsInstance<RefBlock.Choice>().first().content(0) // EMT first
        val thhn = (emt.first() as RefBlock.Choice).content(0).filterIsInstance<RefBlock.Table>().first()
        val row = thhn.rows.first { it[0] == "12 AWG" }
        assertEquals("9", row[thhn.columns.indexOf("1/2\"")])
        assertEquals("16", row[thhn.columns.indexOf("3/4\"")])
    }

    @Test
    fun searchFindsByKeywordAndTitle() {
        assertEquals("nec-ampacity", ReferenceCatalog.search("ampacity").first().id)
        assertTrue(ReferenceCatalog.search("l14-30").any { it.id == "nema-configurations" })
        assertTrue(ReferenceCatalog.search("offset shrink").first().id == "bend-offsets")
        assertTrue(ReferenceCatalog.search("motor hp three").any { it.id == "formula:motor-current-three-phase" })
        assertTrue(ReferenceCatalog.search("zzzz").isEmpty())
        assertTrue(ReferenceCatalog.search("  ").isEmpty())
    }

    @Test
    fun toolTopics() {
        assertEquals(ToolIds.CONDUIT_FILL, Topics.toolOf(Topics.forTool(ToolIds.CONDUIT_FILL)))
        assertTrue(ReferenceCatalog.forTool(ToolIds.CONDUIT_FILL).any { it.id == "nec-conduit-dimensions" })
        assertTrue(ReferenceCatalog.hasEntriesFor(ToolIds.FORMULAS))
        assertTrue(!ReferenceCatalog.hasEntriesFor(ToolIds.PROJECTS))
    }

    @Test
    fun codeEntriesNameTheEdition() {
        assertEquals("NEC 2023 · Table 310.16", ReferenceCatalog.find("nec-ampacity")!!.source.label)
    }
}
