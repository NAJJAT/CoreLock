// Standalone debug test app used only to prove PrivacyGuard features end-to-end:
//  - the interception chain (Test App → VPN → MitmEngine → PayloadParser → UI)
//  - the per-app TLS fingerprint baseline (OkHttp vs a second TLS library)
// It is NOT part of the PrivacyGuard app and shares none of its code.
// Java-only, no Kotlin/Compose/KSP.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.privacyguard.testapp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.privacyguard.testapp"
        minSdk = 24
        targetSdk = 36
        // `-PtestappVersionCode=2` builds an "updated" app to test the baseline's
        // after-update path with a real `adb install -r`.
        versionCode = (findProperty("testappVersionCode") as String?)?.toInt() ?: 1
        versionName = "1.0"
    }

    buildTypes {
        // debug is what we install; network_security_config trusts user CAs so
        // PrivacyGuard's forged certificate is accepted and the payload is decrypted.
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

// Test tool only: no release variant exists, so it can never be published.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // A second, independent TLS stack: its ClientHello differs from Conscrypt's,
    // so the baseline must see a new fingerprint for the same app UID.
    implementation("org.bouncycastle:bctls-jdk18on:1.86")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
}
