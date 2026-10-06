// Standalone debug test app used only to prove the PrivacyGuard interception chain
// end-to-end (Test App → VPN → MitmEngine → PayloadParser → UI).
// It is NOT part of the PrivacyGuard app and shares none of its code.
// Java-only, no Kotlin/Compose/KSP — the smallest thing that can send an HTTPS POST.
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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // debug is what we install; network_security_config trusts user CAs so
        // PrivacyGuard's forged certificate is accepted and the payload is decrypted.
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
