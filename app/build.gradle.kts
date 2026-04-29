plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
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

configurations.configureEach {
    // FIXED: legacy-preference-v14 pulls ancient appcompat/vectordrawable 1.0.0 and breaks AGP 9 manifest merge.
    exclude(group = "androidx.legacy", module = "legacy-preference-v14")
}

// ── Rust NDK Build ─────────────────────────────────────────────────────────────
// Requires:
//   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
//   cargo install cargo-ndk
//
// The task is optional: if cargo-ndk is not on PATH the build proceeds without
// the native .so and the Kotlin JA3/bloom fallback is used automatically.

val rustDir = rootProject.projectDir.resolve("rust")
val jniLibsDir = project.projectDir.resolve("src/main/jniLibs")

val buildRust by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile Rust core to Android shared libraries via cargo-ndk"

    workingDir = rustDir
    isIgnoreExitValue = true   // don't fail if cargo-ndk is absent

    val targets = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    val targetFlags = targets.flatMap { listOf("-t", it) }

    commandLine = listOf("cargo", "ndk") +
        targetFlags +
        listOf("--android-platform", "24", "--output-dir", jniLibsDir.absolutePath, "build", "--release")

    doFirst {
        // Only run if cargo-ndk is available
        val cargoNdk = try {
            ProcessBuilder("cargo", "ndk", "--version")
                .start().waitFor() == 0
        } catch (_: Exception) { false }
        if (!cargoNdk) {
            logger.warn("⚠️  cargo-ndk not found — skipping Rust build. Kotlin fallback active.")
            commandLine = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
                listOf("cmd", "/c", "exit", "0")
            } else {
                listOf("true")
            }   // no-op command
        }
    }
}

tasks.named("preBuild") { dependsOn(buildRust) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!rootProject.file("flutter/.android").exists()) {
        exclude("**/FlutterMainActivity.kt")
        exclude("**/FlutterBridge.kt")
    }
}

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
            buildConfigField("boolean", "MITM_AVAILABLE", "true")
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

    // Exclude Flutter-dependent sources until `cd flutter && flutter pub get` is run.
    // Once the Flutter module is set up, these files compile as part of :flutter dependency.

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Existing dependencies
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
    // MDM / Device Admin
    implementation("androidx.legacy:legacy-preference-v14:1.0.0")
    implementation("androidx.enterprise:enterprise-feedback:1.1.0")
    ksp(libs.androidx.room.compiler)

    // ==================== MITM DEPENDENCIES ====================

    // Bouncy Castle (X.509 certificate generation for MITM)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")

    // OkHttp (SIEM shipping for MITM payloads)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Kotlinx Serialization (JSON serialization for MITM payloads)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // ==================== END MITM DEPENDENCIES ====================

    // Flutter UI module — active once `cd flutter && flutter pub get` has been run
    if (rootProject.file("flutter/.android/include_flutter.groovy").exists()) {
        implementation(project(":flutter"))
    }

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}




