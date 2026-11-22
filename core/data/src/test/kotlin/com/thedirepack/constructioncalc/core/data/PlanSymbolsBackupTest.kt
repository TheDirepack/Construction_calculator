package com.thedirepack.constructioncalc.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class PlanSymbolsBackupTest {
    @get:Rule val temp = TemporaryFolder()

    private val strictTestValidator: (String) -> Unit = { svg ->
        require(svg.contains("<svg") && !svg.contains("<script") && !svg.contains("<!DOCTYPE")) {
            "Rejected unsafe SVG"
        }
    }

    private fun store(name: String): PlanSymbols =
        PlanSymbols(directory = temp.newFolder(name), validateSvg = strictTestValidator)

    @Test fun emptyStoreExportsNull() {
        val symbols = store("empty")
        assertNull(symbols.backup.export())
    }

    @Test fun roundTripExportAndImport() {
        val source = store("src")
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()
        source.writeSymbol(PlanSymbol(id1, "Receptacle", """<svg viewBox="0 0 10 10"><circle cx="5" cy="5" r="4"/></svg>"""))
        source.writeSymbol(PlanSymbol(id2, "Switch", """<svg viewBox="0 0 10 10"><rect width="8" height="8"/></svg>"""))

        val exported = source.backup.export()
        assertNotNull(exported)

        val dest = store("dest")
        dest.backup.import(exported!!, replace = false)

        val restored = dest.list()
        assertEquals(2, restored.size)
        assertEquals(listOf("Receptacle", "Switch"), restored.map { it.name })
        assertEquals(id1, restored[0].id)
        assertEquals(id2, restored[1].id)
    }

    @Test fun mergePreservesExistingAndReplaceClearsOld() {
        val source = store("src2")
        val importedId = UUID.randomUUID().toString()
        source.writeSymbol(PlanSymbol(importedId, "Panel", """<svg viewBox="0 0 10 10"><rect width="10" height="10"/></svg>"""))
        val exported = source.backup.export()!!

        val dest = store("dest2")
        val existingId = UUID.randomUUID().toString()
        dest.writeSymbol(PlanSymbol(existingId, "Light", """<svg viewBox="0 0 10 10"><circle cx="5" cy="5" r="3"/></svg>"""))

        dest.backup.import(exported, replace = false)
        assertEquals(setOf("Light", "Panel"), dest.list().map { it.name }.toSet())

        dest.backup.import(exported, replace = true)
        assertEquals(listOf("Panel"), dest.list().map { it.name })
    }

    @Test fun rejectsUnsafeSvgAndPathTraversalIds() {
        val dir = temp.newFolder("safe")
        val symbols = PlanSymbols(directory = dir, validateSvg = strictTestValidator)
        val goodId = UUID.randomUUID().toString()
        val badSvgId = UUID.randomUUID().toString()
        val payload = Json.parseToJsonElement(
            """
            {
              "version": 1,
              "symbols": [
                {"id": "$goodId", "name": "Good", "svg": "<svg viewBox=\"0 0 10 10\"><rect width=\"5\" height=\"5\"/></svg>"},
                {"id": "$badSvgId", "name": "Scripted", "svg": "<svg><script>alert(1)</script></svg>"},
                {"id": "../escaped", "name": "Traversal", "svg": "<svg viewBox=\"0 0 10 10\"><rect width=\"5\" height=\"5\"/></svg>"}
              ]
            }
            """.trimIndent(),
        )

        symbols.backup.import(payload, replace = false)

        val listed = symbols.list()
        assertEquals(1, listed.size)
        assertEquals("Good", listed.single().name)
        assertFalse(File(dir.parentFile, "escaped.json").exists())
        assertFalse(File(dir, "$badSvgId.json").exists())
    }

    @Test fun malformedPayloadThrowsWithoutWipingExisting() {
        val symbols = store("keep")
        val id = UUID.randomUUID().toString()
        symbols.writeSymbol(PlanSymbol(id, "KeepMe", """<svg viewBox="0 0 10 10"><circle cx="5" cy="5" r="4"/></svg>"""))

        assertThrows(IllegalArgumentException::class.java) {
            symbols.backup.import(JsonPrimitive("not-an-object"), replace = true)
        }
        assertEquals(1, symbols.list().size)
    }

    @Test fun roundTripsNamedSetsFavoritesAndRecentsAndLegacyV1Compat() {
        val source = store("meta-src")
        val id1 = UUID.randomUUID().toString()
        source.writeSymbol(PlanSymbol(id1, "GFCI", """<svg viewBox="0 0 10 10"><rect width="8" height="8"/></svg>"""))
        source.writeMeta(
            SymbolLibraryMeta(
                sets = mapOf("Rough-in" to listOf(id1, "built-in:Switch"), "../bad" to listOf("x")),
                favorites = setOf(id1, "built-in:Duplex receptacle"),
                recents = listOf("built-in:Duplex receptacle", id1),
            ),
        )
        assertEquals(1, source.list().size) // library-meta.json is not treated as a symbol

        val exported = source.backup.export()!!
        val mergedDest = store("meta-merge")
        mergedDest.writeMeta(
            SymbolLibraryMeta(
                sets = mapOf("Rough-in" to listOf("built-in:Junction box"), "Lighting" to listOf("built-in:Light fixture")),
                favorites = setOf("built-in:Junction box"),
                recents = listOf("built-in:Light fixture"),
            ),
        )
        mergedDest.backup.import(exported, replace = false)
        val merged = mergedDest.readMeta()
        assertEquals(listOf("built-in:Junction box", id1, "built-in:Switch"), merged.sets["Rough-in"])
        assertEquals(listOf("built-in:Light fixture"), merged.sets["Lighting"])
        assertFalse("../bad" in merged.sets)
        assertEquals(setOf("built-in:Junction box", id1, "built-in:Duplex receptacle"), merged.favorites)
        assertEquals(listOf("built-in:Duplex receptacle", id1, "built-in:Light fixture"), merged.recents)

        // Removing a symbol cleans up its metadata references.
        mergedDest.remove(id1)
        assertFalse(id1 in mergedDest.readMeta().favorites)

        // Legacy v1 JSON without "meta" replaces cleanly when replace=true.
        val legacy = Json.parseToJsonElement("""{"version": 1, "symbols": []}""")
        mergedDest.backup.import(legacy, replace = true)
        assertEquals(SymbolLibraryMeta(), mergedDest.readMeta())
    }
}
