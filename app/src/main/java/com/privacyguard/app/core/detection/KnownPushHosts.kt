package com.privacyguard.app.core.detection

/**
 * Hosts that apps contact on a timer by design: push channels, messaging
 * keep-alives and connectivity checks. Contacting them at a steady interval is
 * how notifications work, not a sign of malware, so beacon detection skips them.
 */
object KnownPushHosts {

    private val suffixes = listOf(
        // Google / Firebase Cloud Messaging and connectivity checks
        "mtalk.google.com", "fcm.googleapis.com", "fcmtoken.googleapis.com", "android.googleapis.com",
        "connectivitycheck.gstatic.com", "connectivitycheck.android.com", "clients3.google.com",
        // Apple push
        "push.apple.com",
        // Samsung push
        "push.samsungosp.com",
        // Meta: Facebook / Messenger / Instagram / WhatsApp realtime channels
        "edge-mqtt.facebook.com", "mqtt-mini.facebook.com", "z-m-gateway.facebook.com",
        "gateway.instagram.com", "edge-chat.instagram.com", "whatsapp.net",
        // Other messengers and browser push
        "chat.signal.org", "push.services.mozilla.com", "notify.windows.com",
    )

    fun isPushHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        if (h.split('.').any { it == "mqtt" || it.startsWith("mqtt-") }) return true
        // Numbered variants such as alt3-mtalk.google.com.
        if (h.endsWith("-mtalk.google.com")) return true
        return suffixes.any { h == it || h.endsWith(".$it") }
    }
}
