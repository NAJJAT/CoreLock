# ─────────────────────────────────────────────────────────────────────────────
# PrivacyGuard ProGuard / R8 rules
# ─────────────────────────────────────────────────────────────────────────────

# Keep line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep generic signatures (Kotlin/Coroutines reflection, Room TypeConverters)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod


# ── Android components (manifest-declared, instantiated by the OS) ────────────

-keep class com.privacyguard.platform.android.PrivacyVpnService { *; }
-keep class com.privacyguard.app.MainActivity { *; }
-keep class com.privacyguard.app.FlutterMainActivity { *; }
-keep class com.privacyguard.app.PrivacyGuardApplication { *; }
-keep class com.privacyguard.app.tile.PrivacyGuardTileService { *; }
-keep class com.privacyguard.app.vpn.BootReceiver { *; }
-keep class com.privacyguard.app.core.utils.NotificationDismissReceiver { *; }
-keep class com.privacyguard.mdm.DeviceAdminReceiver { *; }
-keep class com.privacyguard.mdm.MdmConfigReceiver { *; }
-keep class com.privacyguard.app.vpn.mitm.PayloadInspectorActivity { *; }


# ── WorkManager workers ───────────────────────────────────────────────────────

-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.privacyguard.app.workers.** { *; }


# ── ViewModels (constructed via ViewModelProvider / factory reflection) ────────

-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# ViewModel factories defined in the project
-keep class **ViewModelFactory { *; }


# ── Room Database ─────────────────────────────────────────────────────────────

# All @Entity, @Dao, and @Database classes; Room generates code using reflection
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.TypeConverter class * { *; }

# Room-generated _Impl classes
-keep class **_Impl { *; }
-keep class **_Impl$* { *; }

# Keep the full data/db package to be safe (entities, DAOs, DB)
-keep class com.privacyguard.app.data.db.** { *; }
-keep class com.privacyguard.app.core.app.** { *; }


# ── Kotlin ────────────────────────────────────────────────────────────────────

# data class copy() / componentN() methods used in destructuring and StateFlow updates
-keepclassmembers class * {
    ** component*();
    ** copy(...);
}

# Kotlin companion objects accessed by name
-keepclassmembers class * {
    public static ** Companion;
}

# object singletons (Kotlin object declarations)
-keepclassmembers class * {
    public static ** INSTANCE;
}

# Kotlin metadata (needed for reflection, Coroutines, and Compose)
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.jvm.internal.**


# ── Kotlin Coroutines ─────────────────────────────────────────────────────────

-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**


# ── Kotlin Serialization ──────────────────────────────────────────────────────

-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** serializer();
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }
-dontwarn kotlinx.serialization.**


# ── Jetpack Compose ───────────────────────────────────────────────────────────

# Compose Compiler generates stable class markers; keep them
-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn androidx.compose.**


# ── JNI — Rust native bridge ──────────────────────────────────────────────────
# The Rust .so looks up these methods by exact JNI name.
# Renaming them breaks the JNI link silently.

-keep class com.privacyguard.core.native_engine.RustBridge {
    native <methods>;
    public static <methods>;
}


# ── VPN / packet engine core ──────────────────────────────────────────────────

# These classes have their package names baked into the manifest / OS calls
-keep class com.privacyguard.core.** { *; }
-keep class com.privacyguard.vpn.** { *; }

# Filter / session engine — hot path, no obfuscation
-keep class com.privacyguard.core.filter.** { *; }
-keep class com.privacyguard.core.session.** { *; }
-keep class com.privacyguard.core.packet.** { *; }
-keep class com.privacyguard.core.tls.** { *; }
-keep class com.privacyguard.vpn.forwarder.** { *; }
-keep class com.privacyguard.vpn.firewall.** { *; }
-keep class com.privacyguard.vpn.inspector.** { *; }
-keep class com.privacyguard.vpn.mitm.** { *; }
-keep class com.privacyguard.vpn.tunnel.** { *; }


# ── MITM / TLS engine ────────────────────────────────────────────────────────

# X.509 / SSLContext / KeyManager classes loaded by class name at runtime
-keep class javax.net.ssl.** { *; }
-keep class java.security.** { *; }
-keep class sun.security.** { *; }
-dontwarn sun.security.**

# BouncyCastle (used by CaManager / CertForger if present on device)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**


# ── MDM / enterprise ─────────────────────────────────────────────────────────

-keep class com.privacyguard.mdm.** { *; }


# ── Preferences / SharedPreferences ──────────────────────────────────────────

-keep class com.privacyguard.app.data.local.preferences.** { *; }


# ── Enum classes (serialized by name in SharedPreferences, JSON, and DB) ──────

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ── OkHttp / networking (used by CtMonitor, DoH, SIEM shipper) ───────────────

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }


# ── AndroidX / Google libraries ───────────────────────────────────────────────

-dontwarn androidx.**
-keep class androidx.core.app.CoreComponentFactory { *; }
-keep class androidx.startup.** { *; }

# Navigation component (routes stored as strings, looked up by reflection)
-keep class androidx.navigation.** { *; }


# ── Suppress common harmless warnings ────────────────────────────────────────

-dontwarn java.lang.instrument.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.errorprone.**
