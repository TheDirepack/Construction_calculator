package com.thedirepack.constructioncalc.feature.pullplanner.domain

import com.thedirepack.constructioncalc.core.electrical.NamedCable
import com.thedirepack.constructioncalc.core.model.EquipmentRating
import com.thedirepack.constructioncalc.core.model.PullCable
import com.thedirepack.constructioncalc.core.model.PullSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamedCablesTest {
    private val mc = NamedCable("a", "3/C #4 MC", diameterIn = 0.95, weightLbPerFt = 0.62, maxTensionLb = 1200.0, maxSwbpLbPerFt = 500.0)

    @Test fun addingANamedCableCarriesItsDataAndRatings() {
        val setup = NamedCables.apply(PullSetup(pressureLimit = 800.0), mc)
        val cable = setup.cables.single()
        assertEquals("3/C #4 MC", cable.label)
        assertEquals(0.95, cable.diameterIn!!, 0.0)
        assertEquals(0.62, cable.weightLbPerFt!!, 0.0)
        assertEquals(EquipmentRating("Cable max tension: 3/C #4 MC", 1200.0), setup.equipment.last())
        assertEquals(500.0, setup.pressureLimit!!, 0.0) // the tighter limit wins
        assertEquals("1 × 3/C #4 MC", NamedCables.describe(cable))
    }

    @Test fun addingTheSameCableTwiceKeepsOneTensionEntry() {
        val twice = NamedCables.apply(NamedCables.apply(PullSetup(), mc), mc)
        assertEquals(2, twice.cables.size)
        assertEquals(1, twice.equipment.count { it.name.startsWith(NamedCables.TENSION_PREFIX) })
    }

    @Test fun cableWithoutRatingsLeavesLimitsAlone() {
        val plain = NamedCables.apply(PullSetup(), NamedCable("b", "Pump cable", diameterIn = 0.5))
        assertEquals(EquipmentRating.DEFAULTS, plain.equipment)
        assertNull(plain.pressureLimit)
    }

    @Test fun handEnteredCableWithAnOdCanBeSaved() {
        assertNull(NamedCables.fromSetup(PullSetup(cables = listOf(PullCable(3)))))
        val named = NamedCables.fromSetup(PullSetup(pressureLimit = 1000.0, cables = listOf(PullCable(1, diameterIn = 1.2, weightLbPerFt = 1.1, label = "Feeder triplex"))))!!
        assertEquals("Feeder triplex", named.name)
        assertEquals(1.2, named.diameterIn, 0.0)
        assertEquals(1000.0, named.maxSwbpLbPerFt!!, 0.0)
    }

    @Test fun notesLinkToTheirReferencePages() {
        assertEquals("nec-fill-percent", PullRefs.forNote("Jam ratio 3.0 (1.05 × D/d) is between 2.8 and 3.2"))
        assertEquals("nec-bend-radius", PullRefs.forNote("Section 2 has 400° of bends between pull points"))
        assertNull(PullRefs.forNote("Friction μ 0.35"))
    }
}
