package com.thedirepack.constructioncalc.feature.drawings

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetNavigationTest {
    @Test fun recentSheetsAreUniqueAndMostRecentFirst() {
        val visits = (0 until 30).fold(SheetVisits()) { visits, page -> visits.visit(page) }.visit(10)

        assertEquals(25, visits.recent.size)
        assertEquals(listOf(10, 29, 28), visits.recent.take(3))
        assertEquals(listOf(10, 12, 11, 9), visits.pages(13, SheetScope.RECENT).take(4).toList())
    }

    @Test fun favoritesAreIndependentOfRecentsAndListedInPageOrder() {
        val visits = SheetVisits().visit(4).toggleFavorite(7).toggleFavorite(3).toggleFavorite(2).toggleFavorite(3)

        assertEquals(listOf(2, 7), visits.pages(8, SheetScope.FAVORITES).toList())
        assertEquals(listOf(2), visits.pages(5, SheetScope.FAVORITES).toList())
        assertEquals(listOf(4), visits.recent)
    }

    @Test fun disciplineUsesSheetNumberAndAllowsUnclassifiedScans() {
        assertEquals(SheetDiscipline.ELECTRICAL, sheetDiscipline(SheetLabel("ep-101")))
        assertEquals(SheetDiscipline.FIRE_PROTECTION, sheetDiscipline(SheetLabel("FP.201")))
        assertEquals(SheetDiscipline.ARCHITECTURAL, sheetDiscipline(SheetLabel("A101")))
        assertEquals(SheetDiscipline.OTHER, sheetDiscipline(SheetLabel("X101")))
        assertEquals(SheetDiscipline.UNCLASSIFIED, sheetDiscipline(SheetLabel("", "Electrical plan")))
        assertEquals(SheetDiscipline.UNCLASSIFIED, sheetDiscipline(null))
    }

    @Test fun pdfTextMatchingCollapsesLineBreaksAndNonbreakingSpaces() {
        assertEquals("MAIN SERVICE PANEL", normalizeSheetText(" MAIN\r\n SERVICE\u00a0PANEL "))
    }
}
