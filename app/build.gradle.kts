plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import java.util.Properties

val debugSignatureSha256 = "E1D461FFE96AC468E338CA1DC251C926DC42F3293618632D7819D4A5C9829A43"

fun propertyOrEnv(name: String): String? =
    providers.gradleProperty(name).orNull ?: System.getenv(name)

fun loadLocalReleaseProperties(): Properties {
    val props = Properties()
    val root = rootProject.projectDir
    val candidates = listOf(
        root.resolve("release-signing.local.properties"),
        root.resolve("release-signing.properties"),
    )
    candidates.firstOrNull { it.exists() }?.inputStream()?.use { props.load(it) }
    return props
}

val localReleaseProperties = loadLocalReleaseProperties()

fun propertyEnvOrFile(name: String): String? =
    propertyOrEnv(name) ?: localReleaseProperties.getProperty(name)

val releaseStoreFilePath = propertyEnvOrFile("PRIVACYGUARD_RELEASE_STORE_FILE")
val releaseStorePassword = propertyEnvOrFile("PRIVACYGUARD_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = propertyEnvOrFile("PRIVACYGUARD_RELEASE_KEY_ALIAS")
val releaseKeyPassword = propertyEnvOrFile("PRIVACYGUARD_RELEASE_KEY_PASSWORD")
val releaseSignatureSha256 = propertyEnvOrFile("PRIVACYGUARD_RELEASE_SIGNATURE_SHA256")

val releaseSigningConfigured = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
    releaseSignatureSha256,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.privacyguard.app"
    flavorDimensions += "distribution"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.privacyguard.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "APP_SIGNATURE_SHA256", "\"\"")
        buildConfigField("boolean", "IS_RELEASE_SIGNING_CONFIGURED", "false")
        buildConfigField("String", "APP_SIGNING_MODE", "\"Unknown\"")
        buildConfigField("boolean", "MITM_AVAILABLE", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            buildConfigField(
                "String",
                "APP_SIGNATURE_SHA256",
                "\"$debugSignatureSha256\""
            )
            buildConfigField("boolean", "IS_RELEASE_SIGNING_CONFIGURED", releaseSigningConfigured.toString())
            buildConfigField("String", "APP_SIGNING_MODE", "\"Debug certificate\"")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            buildConfigField(
                "String",
                "APP_SIGNATURE_SHA256",
                "\"${releaseSignatureSha256 ?: debugSignatureSha256}\""
            )
            buildConfigField("boolean", "IS_RELEASE_SIGNING_CONFIGURED", releaseSigningConfigured.toString())
            buildConfigField(
                "String",
                "APP_SIGNING_MODE",
                if (releaseSigningConfigured) "\"Configured release keystore\"" else "\"Debug fallback\""
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    productFlavors {
        create("consumer") {
            dimension = "distribution"
            buildConfigField("boolean", "MITM_AVAILABLE", "false")
        }
        create("enterprise") {
            dimension = "distribution"
            applicationIdSuffix = ".enterprise"
            versionNameSuffix = "-enterprise"
            buildConfigField("boolean", "MITM_AVAILABLE", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
