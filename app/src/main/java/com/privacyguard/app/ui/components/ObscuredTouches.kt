package com.privacyguard.app.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Put inside a dialog whose "yes" must come from the user: taps that reach it while
 * another app's window covers it are dropped (tapjacking). Compose has no modifier for
 * this, so the dialog's own view and its parents get [View.setFilterTouchesWhenObscured];
 * a ViewGroup checks that flag before passing a touch on. Android 12+ already blocks most
 * such touches; this also covers Android 7 to 11.
 */
@Composable
fun IgnoreObscuredTouches() {
    val view = LocalView.current
    DisposableEffect(view) {
        protect(view)
        // The dialog window may attach after the first composition: protect its parents then too.
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = protect(v)
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(listener)
        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }
}

private fun protect(view: View) {
    var v: View? = view
    while (v != null) {
        v.filterTouchesWhenObscured = true
        v = v.parent as? View
    }
}
