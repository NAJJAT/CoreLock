package com.privacyguard.app.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WhileObservedTest {

    @Test
    fun loop_runs_only_while_the_flow_is_collected() = runBlocking {
        val state = MutableStateFlow(0)
        val runs = AtomicInteger()
        val loop = launchWhileObserved(state) {
            while (true) { runs.incrementAndGet(); delay(10) }
        }

        delay(60)
        assertEquals("nobody looking: no refresh", 0, runs.get())

        val screen = launch { state.collect { } }
        delay(60)
        assertTrue("refreshes while shown (${runs.get()})", runs.get() >= 2)

        screen.cancel()
        delay(30)
        val stoppedAt = runs.get()
        delay(60)
        assertEquals("stops once the screen is gone", stoppedAt, runs.get())

        val again = launch { state.collect { } }
        delay(30)
        assertTrue("starts again when the screen returns", runs.get() > stoppedAt)

        again.cancel()
        loop.cancel()
    }
}
