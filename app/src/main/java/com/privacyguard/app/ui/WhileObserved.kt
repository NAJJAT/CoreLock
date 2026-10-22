package com.privacyguard.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Runs [block] (a refresh loop) only while [observed] has a collector, cancelling it
 * when the last one leaves and starting it again, from the top, when one returns.
 *
 * Screens collect with collectAsStateWithLifecycle, so that is: only while the screen
 * is shown. viewModelScope alone kept these loops querying the database while the
 * screen sat on the back stack or the app was in the background with the screen off.
 */
fun CoroutineScope.launchWhileObserved(observed: MutableStateFlow<*>, block: suspend CoroutineScope.() -> Unit): Job =
    launch {
        observed.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .collectLatest { active -> if (active) kotlinx.coroutines.coroutineScope { block() } }
    }
