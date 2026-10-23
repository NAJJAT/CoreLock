plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
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

// A release artifact must never be signed with the debug key: AppSecurityMonitor's
// tamper check trusts APP_SIGNATURE_SHA256, so a debug-signed release would vouch
// for any rebuilt APK. Fail the build instead of silently falling back.
gradle.taskGraph.whenReady {
    // Only tasks that produce a signed artifact (assembleXRelease, bundleXRelease,
    // packageXRelease, signXReleaseBundle); R8, lint and resource tasks still run.
    val artifactTask = Regex("^(assemble|bundle|package|sign)[A-Za-z]*Release(Bundle)?$")
    val releaseArtifactTasks = allTasks.filter { task ->
        task.project == project && artifactTask.matches(task.name)
    }
    if (releaseArtifactTasks.isNotEmpty() && !releaseSigningConfigured) {
        throw GradleException(
            "Release signing is not configured. Set PRIVACYGUARD_RELEASE_STORE_FILE, " +
                "PRIVACYGUARD_RELEASE_STORE_PASSWORD, PRIVACYGUARD_RELEASE_KEY_ALIAS, " +
                "PRIVACYGUARD_RELEASE_KEY_PASSWORD and PRIVACYGUARD_RELEASE_SIGNATURE_SHA256 " +
                "(Gradle properties, environment, or release-signing.local.properties outside VCS). " +
                "Blocked tasks: ${releaseArtifactTasks.joinToString { it.path }}"
        )
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
        versionName = "1.0.0"
        buildConfigField("String", "APP_SIGNATURE_SHA256", "\"\"")
        buildConfigField("boolean", "IS_RELEASE_SIGNING_CONFIGURED", "false")
        buildConfigField("String", "APP_SIGNING_MODE", "\"Unknown\"")
        // Opened in the browser from Settings › About; the app never fetches it itself.
        // A store build can point it at its listing instead.
        buildConfigField("String", "RELEASES_URL", "\"https://github.com/NAJJAT/CoreLock/releases\"")

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
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // No META-INF/version-control-info.textproto (git commit) in the APK (V-15).
            vcsInfo.include = false
            // No debug fallback: without a release key the artifact tasks fail (see taskGraph check above).
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            buildConfigField(
                "String",
                "APP_SIGNATURE_SHA256",
                "\"${releaseSignatureSha256.orEmpty()}\""
            )
            buildConfigField("boolean", "IS_RELEASE_SIGNING_CONFIGURED", releaseSigningConfigured.toString())
            buildConfigField("String", "APP_SIGNING_MODE", "\"Configured release keystore\"")
            // ARM only: drops the x86 and x86_64 copies of the native libraries
            // (SQLCipher). 32-bit ARM stays so older and low-end phones can install it.
            // Debug builds keep every ABI for emulators.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    productFlavors {
        // The only flavor: the consumer flavor was removed (owner's decision, 2026-10-23).
        // Kept as a flavor so the application ID (.enterprise), task names and the
        // src/enterprise source set stay as they are on installed phones and in CI.
        create("enterprise") {
            dimension = "distribution"
            applicationIdSuffix = ".enterprise"
            // No version suffix: as the only app, users see plain "1.0.0".
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric tests read the app's strings (notifications, channels).
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // Bouncy Castle 1.78+ ships the same license files in bcprov, bcpkix and bcutil.
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    // Per-app language (AppCompatDelegate.setApplicationLocales), back-ported below Android 13.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // SQLCipher: the Room database holds DNS history, connection logs and (enterprise)
    // decrypted payloads, so it is encrypted at rest (BRD NFR-S-06).
    implementation("net.zetetic:sqlcipher-android:4.19.1@aar")
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)
    // kotlinx-serialization-core reaches the app transitively (lifecycle) at 1.7.3, and
    // the test APK is pinned to the app's versions. room-testing's schema serializers are
    // built against 1.8.x and fail with AbstractMethodError on 1.7.3, so align via the BOM.
    implementation(platform(libs.kotlinx.serialization.bom))

    // ==================== MITM DEPENDENCIES ====================
    // MITM code lives in src/enterprise, so its libraries are enterprise-only.

    // Bouncy Castle (X.509 certificate generation for MITM)
    // 1.78+ fixes CVE-2024-29857, CVE-2024-30171 and CVE-2024-30172.
    "enterpriseImplementation"("org.bouncycastle:bcpkix-jdk18on:1.86")
    "enterpriseImplementation"("org.bouncycastle:bcprov-jdk18on:1.86")

    // OkHttp (SIEM shipping for MITM payloads; also used by shared code)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlinx Serialization (JSON serialization for MITM payloads)
    "enterpriseImplementation"(libs.kotlinx.serialization.json)

    // ==================== END MITM DEPENDENCIES ====================

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Exported Room schemas (committed) let MigrationTestHelper check every
// migration against the real old schema; the plugin also adds them to
// androidTest assets.
room {
    schemaDirectory("$projectDir/schemas")
}

// OSV scan in CI (V-14): what ships in the release APKs, in the gradle.lockfile format
// osv-scanner reads. gradle/verification-metadata.xml also lists the build tools (AGP,
// lint, their Netty and Bouncy Castle), whose advisories never reach a phone.
tasks.register("osvLockfile") {
    val classpaths = listOf("enterpriseReleaseRuntimeClasspath")
    val out = layout.buildDirectory.file("osv/gradle.lockfile")
    outputs.file(out)
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("resolves the release classpaths when it runs")
    doLast {
        val usedBy = sortedMapOf<String, MutableSet<String>>()
        classpaths.forEach { name ->
            configurations.getByName(name).incoming.resolutionResult.allComponents
                .map { it.id }
                .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
                .forEach { usedBy.getOrPut("${it.group}:${it.module}:${it.version}") { sortedSetOf() } += name }
        }
        check(usedBy.isNotEmpty()) { "no release dependencies resolved" }
        out.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "# Generated by :app:osvLockfile for osv-scanner; not a Gradle lock.\n" +
                    usedBy.entries.joinToString("") { (coord, names) -> "$coord=${names.joinToString(",")}\n" } +
                    "empty=\n"
            )
        }
        logger.lifecycle("osvLockfile: ${usedBy.size} components in ${out.get().asFile}")
    }
}
