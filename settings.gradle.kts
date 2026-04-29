pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "privacyguard"
include(":app")

// Flutter add-to-app module.
// Enable by running: cd flutter && flutter pub get
// This generates flutter/.android/include_flutter.groovy and wires the :flutter project in.
val flutterInclude = file("flutter/.android/include_flutter.groovy")
if (flutterInclude.exists()) {
    apply(from = flutterInclude)
}
