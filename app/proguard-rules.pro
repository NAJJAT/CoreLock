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
# Destructuring, copy(), companions and object singletons are plain static calls,
# so they need no keep rules. (Global keeps for them, and for every @Composable,
# used to stop R8 from shrinking those members in every library as well.)

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
# Compose ships its own consumer R8 rules; keeping all of androidx.compose.**
# only made the APK larger.


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
# (javax.net.ssl / java.security are platform classes, not in the APK: no rules.)

-dontwarn sun.security.**

# BouncyCastle looks up some of its own classes by name; enterprise flavor only.
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
# OkHttp ships its own R8 rules.

-dontwarn okhttp3.**
-dontwarn okio.**


# ── AndroidX / Google libraries ───────────────────────────────────────────────
# Navigation uses string routes here (no reflection), and AndroidX libraries
# ship consumer rules for what they need.

-dontwarn androidx.**
-keep class androidx.core.app.CoreComponentFactory { *; }
-keep class androidx.startup.** { *; }


# ── Suppress common harmless warnings ────────────────────────────────────────

-dontwarn java.lang.instrument.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.errorprone.**

# ── Strip debug/info/verbose logging from release builds ─────────────────────
# Many of these calls include hostnames and package names (browsing history).
# Warnings and errors stay; they must never include hostnames.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ── SQLCipher (JNI callbacks into these classes) ─────────────────────────────
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }
