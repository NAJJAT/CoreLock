package com.privacyguard.app.core.sensors

import android.content.pm.ApplicationInfo

/**
 * What an app is for, as far as camera and microphone go: messengers and
 * video-call apps are expected to use both, camera apps the camera, a game or a
 * flashlight neither. Uses the category the developer declares to Android
 * ([ApplicationInfo.category]) plus known package lists.
 */
enum class SensorPurpose(val label: String) {
    COMMUNICATION("a messaging or calling app"),
    CAMERA_APP("a camera or photo app"),
    RECORDER("a recording app"),
    ASSISTANT("a voice assistant"),
    BROWSER("a web browser"),
    SYSTEM("part of the phone's system"),
    /** A job that has nothing to do with camera or microphone. */
    UNRELATED("an app that has no clear need for it"),
    UNKNOWN("an app");

    /** Is using [sensor] in the foreground part of this kind of app's normal job? */
    fun expects(sensor: SensorType): Boolean = when (this) {
        COMMUNICATION, ASSISTANT, BROWSER, SYSTEM -> true
        CAMERA_APP -> true          // video recording uses the microphone too
        RECORDER -> sensor == SensorType.MIC
        UNRELATED -> false
        UNKNOWN -> true             // no evidence either way: do not raise severity
    }

    companion object {
        private val communication = listOf(
            "com.whatsapp", "org.telegram.", "org.thoughtcrime.securesms", "org.signal.", "com.facebook.orca",
            "com.skype.", "us.zoom.", "com.microsoft.teams", "com.google.android.apps.meetings",
            "com.google.android.apps.tachyon", "com.discord", "com.viber.", "jp.naver.line", "com.tencent.mm",
            "com.snapchat.android", "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc",
            "com.facebook.katana", "im.vector.app", "ch.threema", "com.wire", "com.samsung.android.incallui",
            "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        )
        private val camera = listOf(
            "com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera",
            "com.samsung.android.aremoji", "com.adobe.", "com.google.ar.lens", "com.google.android.apps.photos",
            "com.camscanner", "com.microsoft.office.officelens",
        )
        private val recorders = listOf("com.sec.android.app.voicenote", "com.google.android.apps.recorder")
        private val assistants = listOf(
            "com.google.android.googlequicksearchbox", "com.samsung.android.bixby", "com.google.android.apps.googleassistant",
            "com.amazon.dee.app", "com.samsung.android.svoiceime", "com.google.android.tts",
        )
        private val browsers = listOf(
            "com.android.chrome", "org.mozilla.", "com.microsoft.emmx", "com.brave.browser",
            "com.opera.", "com.sec.android.app.sbrowser", "com.duckduckgo.",
        )
        private val unrelatedWords = listOf(
            "flashlight", "torch", "wallpaper", "calculator", "cleaner", "booster", "battery", "launcher",
            "compass", "weather", "clock", "alarm", "ringtone", "fonts", "theme", "solitaire", "sudoku",
        )

        /**
         * [category] is [ApplicationInfo.category] (API 26+; -1 when undefined), [isSystem]
         * whether the app is preinstalled.
         */
        fun of(packageName: String, category: Int, isSystem: Boolean): SensorPurpose {
            fun inList(list: List<String>) = list.any { packageName == it || packageName.startsWith(it) }
            return when {
                inList(assistants) -> ASSISTANT
                inList(communication) -> COMMUNICATION
                inList(recorders) -> RECORDER
                inList(camera) -> CAMERA_APP
                inList(browsers) -> BROWSER
                unrelatedWords.any { it in packageName.lowercase() } -> UNRELATED
                category == ApplicationInfo.CATEGORY_GAME -> UNRELATED
                category == ApplicationInfo.CATEGORY_SOCIAL -> COMMUNICATION
                category == ApplicationInfo.CATEGORY_IMAGE || category == ApplicationInfo.CATEGORY_VIDEO -> CAMERA_APP
                category == ApplicationInfo.CATEGORY_AUDIO -> RECORDER
                category == ApplicationInfo.CATEGORY_NEWS || category == ApplicationInfo.CATEGORY_MAPS -> UNRELATED
                isSystem -> SYSTEM
                else -> UNKNOWN
            }
        }
    }
}
