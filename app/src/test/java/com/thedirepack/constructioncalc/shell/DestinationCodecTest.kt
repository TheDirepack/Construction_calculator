package com.thedirepack.constructioncalc.shell

import com.thedirepack.constructioncalc.core.model.ProjectItem
import com.thedirepack.constructioncalc.core.model.ResultValue
import com.thedirepack.constructioncalc.core.model.ToolTarget
import com.thedirepack.constructioncalc.core.model.ValueKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The back stack must come back exactly after rotation or process death. */
class DestinationCodecTest {
    private val item = ProjectItem(
        toolId = "voltage-drop",
        title = "Feeder \"A\" | odd\u0001chars",
        createdAt = 1,
        inputs = buildJsonObject { put("amps", JsonPrimitive("12.5")) },
        results = listOf(ResultValue.text("k", "Label", "7' 11 7/8\"")),
    )

    @Test fun everyKindOfEntryRoundTrips() {
        listOf(
            Destination.Tool("foot-inch"),
            Destination.Tool("voltage-drop", item),
            Destination.Tool("voltage-drop", target = ToolTarget.OfCircuit("p", "c")),
            Destination.Tool("conduit-fill", item, ToolTarget.OfRun("p", "r")),
            Destination.Tool("projects", target = ToolTarget.OfProject("p")),
            Destination.Settings(),
            Destination.Settings("conduit-bending"),
        ).forEach { assertEquals(it, DestinationCodec.decode(DestinationCodec.encode(it))) }
    }

    @Test fun damagedEntries() {
        assertNull(DestinationCodec.decode(""))
        assertNull(DestinationCodec.decode("X"))
        assertEquals(Destination.Tool("panels"), DestinationCodec.decode("Tpanels\u0001{broken\u0001nonsense"))
        assertEquals(ValueKind.TEXT, item.results.single().kind)
    }
}
