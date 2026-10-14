package com.privacyguard.platform.android

import android.content.Context
import androidx.core.app.NotificationCompat
import com.privacyguard.app.R

/**
 * The only way PrivacyGuard builds a notification (V-09). Alerts name apps and
 * hosts, so every notification is VISIBILITY_PRIVATE with a public version that
 * says nothing but "unlock to see": on a locked phone where the user chose to hide
 * sensitive notification content, that is all that shows.
 *
 * Android decides when the public version is used: with "show all notification
 * content" on the lock screen (the system setting), the full text still shows.
 * That choice is the user's; this makes sure "hide sensitive content" is honoured.
 */
object PrivateNotifications {

    fun builder(context: Context, channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.notif_public_text))
                    .build()
            )
}
