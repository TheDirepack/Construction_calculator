package com.thedirepack.constructioncalc.shell

import com.thedirepack.constructioncalc.core.model.ProjectCodec
import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.ValueKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The back stack must come back cleanly after rotation or process death. */
class DestinationCodecTest {
    private val item = ProjectItem(
        id = "item-42",
        toolId = "voltage-drop",
        title = "Feeder \"A\" | odd\u0001chars",
        createdAt = 1,
        inputs = buildJsonObject { put("amps", JsonPrimitive("12.5")) },
        results = listOf(ResultValue.text("k", "Label", "7' 11 7/8\"")),
    )

    @Test fun everyKindOfEntryRoundTripsWithLightweightItemRef() {
        val ref = ItemRef(projectId = "proj-1", itemId = item.id)
        listOf(
            Destination.Tool("foot-inch"),
            Destination.Tool("voltage-drop", itemRef = ref),
            Destination.Tool("voltage-drop", target = ToolTarget.OfCircuit("p", "c")),
            Destination.Tool("conduit-fill", target = ToolTarget.OfRun("p", "r"), itemRef = ref),
            Destination.Tool("projects", target = ToolTarget.OfProject("p")),
            Destination.Settings(),
            Destination.Settings("conduit-bending"),
        ).forEach { assertEquals(it, DestinationCodec.decode(DestinationCodec.encode(it))) }
    }

    @Test fun encodingItemStoresLightweightRefInsteadOfFullJson() {
        val encoded = DestinationCodec.encode(
            Destination.Tool("voltage-drop", item = item, itemRef = ItemRef("proj-1", item.id)),
        )
        assertFalse("Encoded state must not embed full ProjectItem JSON: $encoded", encoded.contains("Feeder"))
        assertFalse("Encoded state must not embed inputs JSON: $encoded", encoded.contains("12.5"))
        val decoded = DestinationCodec.decode(encoded) as Destination.Tool
        assertNull(decoded.item)
        assertEquals(ItemRef("proj-1", "item-42"), decoded.itemRef)
    }

    @Test fun legacyFullJsonEntryStillDecodesAsFallback() {
        val legacyJson = ProjectCodec.json.encodeToString(ProjectItem.serializer(), item)
        val legacyEncoded = "Tvoltage-drop\u0001$legacyJson\u0001${ToolTarget.OfRun("p", "r").encode()}"
        val decoded = DestinationCodec.decode(legacyEncoded) as Destination.Tool
        assertEquals("voltage-drop", decoded.toolId)
        assertEquals(item, decoded.item)
        assertEquals(ItemRef(null, item.id), decoded.itemRef)
        assertEquals(ToolTarget.OfRun("p", "r"), decoded.target)
    }

    @Test fun damagedEntries() {
        assertNull(DestinationCodec.decode(""))
        assertNull(DestinationCodec.decode("X"))
        assertEquals(Destination.Tool("panels"), DestinationCodec.decode("Tpanels\u0001{broken\u0001nonsense"))
        assertEquals(Destination.Tool("panels"), DestinationCodec.decode("Tpanels\u0001R\u0002\u0001nonsense"))
        assertEquals(ValueKind.TEXT, item.results.single().kind)
    }
}
