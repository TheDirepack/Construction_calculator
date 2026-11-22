package com.thedirepack.constructioncalc.feature.drawings

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class PdfWorkTest {
    @Test
    fun pageChangeDropsQueuedRenderAndLetsActiveRenderFinish() = runBlocking {
        val activeStarted = CompletableDeferred<Unit>()
        val finishActive = CountDownLatch(1)
        val active = async {
            PdfWork.render {
                activeStarted.complete(Unit)
                check(finishActive.await(10, TimeUnit.SECONDS))
            }
        }

        try {
            withTimeout(5_000) { activeStarted.await() }
            val obsolete = launch(start = CoroutineStart.UNDISPATCHED) {
                PdfWork.render { fail("A render for a skipped page started") }
            }
            obsolete.cancelAndJoin()

            val latest = async(start = CoroutineStart.UNDISPATCHED) { PdfWork.render { 16 } }
            assertFalse(latest.isCompleted)
            finishActive.countDown()
            withTimeout(5_000) { active.await() }
            assertEquals(16, withTimeout(5_000) { latest.await() })
        } finally {
            finishActive.countDown()
        }
    }
}
