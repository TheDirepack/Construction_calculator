package com.thedirepack.constructioncalc.feature.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardMessageTest {
    @Test fun aFailedCopyIsReportedInsteadOfCrashing() {
        assertEquals(
            "Couldn't copy. Try Share as text instead.",
            clipboardMessage(34) { throw RuntimeException("android.os.TransactionTooLargeException") },
        )
        assertEquals("Couldn't copy. Try Share as text instead.", clipboardMessage(30) { false })
    }

    @Test fun aSuccessfulCopyConfirmsOnlyWhereTheSystemDoesNot() {
        assertEquals("Copied", clipboardMessage(32) { true })
        assertNull(clipboardMessage(33) { true })
    }
}
