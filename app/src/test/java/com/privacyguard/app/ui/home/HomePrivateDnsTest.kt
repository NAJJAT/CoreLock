package com.privacyguard.app.ui.home

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.network.PrivateDnsCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 3 screenshot item 2: Private DNS set while Home is visible (the "Start anyway"
 * dialog case) showed no card until the app was closed and opened again.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HomePrivateDnsTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val cm get() = app.getSystemService(ConnectivityManager::class.java)

    @After
    fun tearDown() {
        app.getSharedPreferences("corelock_home", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Runs main-thread work (viewModelScope) and lets the IO/Default parts finish. */
    private fun settleUntil(condition: () -> Boolean): Boolean {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun the_card_appears_when_private_dns_changes_while_home_is_visible() {
        val host = AtomicReference<String?>(null)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = HomeViewModel(app, { host.get() }, changes)
        val collector = vm.viewModelScope.launch { vm.uiState.collect {} }
        try {
            settleUntil { false } // let Home collect everything once
            assertNull(vm.uiState.value.attention)

            host.set("dns.google") // the user sets it; no resume happens
            changes.tryEmit(Unit)
            assertTrue(
                "card after the change, without onResume",
                settleUntil { vm.uiState.value.attention == HomeLogic.Attention.PrivateDns("dns.google") },
            )

            host.set(null) // turned off again
            changes.tryEmit(Unit)
            assertTrue("card gone again", settleUntil { vm.uiState.value.attention == null })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun the_watch_fires_on_a_link_change_and_unregisters_when_home_goes() {
        val fired = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { PrivateDnsCheck.changes(app).collect { fired.incrementAndGet() } }
        val callbacks = shadowOf(cm).networkCallbacks
        assertEquals(1, callbacks.size)

        callbacks.single().onLinkPropertiesChanged(checkNotNull(cm.activeNetwork), LinkProperties())
        assertEquals(1, fired.get())

        scope.cancel()
        assertTrue("no callback left behind", shadowOf(cm).networkCallbacks.isEmpty())
    }
}
