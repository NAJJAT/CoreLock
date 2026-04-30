// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register("dependencyUpdates") {
    group = "help"
    description = "Generates a lightweight dependency version report from gradle/libs.versions.toml."

    doLast {
        val catalog = rootProject.file("gradle/libs.versions.toml")
        val reportDir = layout.buildDirectory.dir("dependencyUpdates").get().asFile
        val reportFile = reportDir.resolve("report.txt")
        reportDir.mkdirs()

        val versions = linkedMapOf<String, String>()
        var inVersions = false
        catalog.forEachLine { raw ->
            val line = raw.trim()
            when {
                line == "[versions]" -> inVersions = true
                line.startsWith("[") -> inVersions = false
                inVersions && "=" in line -> {
                    val key = line.substringBefore("=").trim()
                    val value = line.substringAfter("=").trim().trim('"')
                    versions[key] = value
                }
            }
        }

        val body = buildString {
            appendLine("PrivacyGuard Dependency Version Report")
            appendLine("======================================")
            appendLine()
            appendLine("Current versions from gradle/libs.versions.toml:")
            versions.forEach { (name, version) ->
                appendLine("- $name = $version")
            }
            appendLine()
            appendLine("Note: The external Ben Manes dependencyUpdates plugin currently crashes on this Gradle 9.4 project.")
            appendLine("This local task keeps ./gradlew dependencyUpdates available and records the version catalog state.")
        }

        reportFile.writeText(body)
        println(body)
        println("Report written to: ${reportFile.absolutePath}")
    }
}
