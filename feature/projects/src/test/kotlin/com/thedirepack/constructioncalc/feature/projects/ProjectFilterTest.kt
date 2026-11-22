package com.thedirepack.constructioncalc.feature.projects

import com.thedirepack.constructioncalc.core.model.Circuit
import com.thedirepack.constructioncalc.core.model.Panel
import com.thedirepack.constructioncalc.core.model.Project
import com.thedirepack.constructioncalc.core.model.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectFilterTest {
    private val office = Project(
        id = "office",
        name = "Downtown Office",
        note = "Tenant buildout floor 3",
        createdAt = 1L,
        runs = listOf(Run(name = "Panel A -> RTU-3")),
        panels = listOf(
            Panel(
                name = "LP-3A",
                circuits = listOf(Circuit(slot = 1, description = "Breakroom Receptacles")),
            ),
        ),
    )

    private val warehouse = Project(
        id = "warehouse",
        name = "North Warehouse",
        note = "High-bay lighting retrofit",
        createdAt = 2L,
        runs = listOf(Run(name = "Main Feeder")),
        panels = listOf(
            Panel(
                name = "MDP-1",
                circuits = listOf(Circuit(slot = 1, description = "Loading Dock Heater")),
            ),
        ),
    )

    private val all = listOf(office, warehouse)

    @Test fun blankQueryReturnsAllProjects() {
        assertEquals(all, filterProjects(all, ""))
        assertEquals(all, filterProjects(all, "   "))
    }

    @Test fun matchesByNameNotePanelCircuitAndRun() {
        assertEquals(listOf(office), filterProjects(all, "downtown"))
        assertEquals(listOf(warehouse), filterProjects(all, "retrofit"))
        assertEquals(listOf(office), filterProjects(all, "LP-3A"))
        assertEquals(listOf(office), filterProjects(all, "breakroom"))
        assertEquals(listOf(office), filterProjects(all, "rtu-3"))
        assertEquals(listOf(warehouse), filterProjects(all, "main feeder"))
    }

    @Test fun multiWordQueryRequiresAllTermsAcrossFields() {
        assertEquals(listOf(office), filterProjects(all, "LP-3A breakroom"))
        assertEquals(listOf(warehouse), filterProjects(all, "MDP-1 dock"))
        assertEquals(emptyList<Project>(), filterProjects(all, "LP-3A dock"))
    }
}
