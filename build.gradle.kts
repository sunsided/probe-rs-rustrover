import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.markusmayer.proberover"
// publishLocalRepo builds 0.1.0.<yyyyMMdd>.<HHmmss>, so every local build counts as an update for the IDE.
val publishingLocally = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "publishLocalRepo" }
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0").let { base ->
    if (publishingLocally) base + "." + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")) else base
}

// RustRover 2026.3 runs on Java 25.
kotlin {
    jvmToolchain(25)
    // Use the platform interfaces' default methods directly instead of generating bridges for each of them,
    // which the Plugin Verifier would report as overriding deprecated and experimental API.
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}

val rustRoverPath = providers.gradleProperty("rustRoverPath").orNull

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Platform tests (headless RustRover) need the IntelliJ test framework from the JetBrains Maven repository.
// Run them with -PplatformTests=true; plain unit tests don't need it.
val platformTests = providers.gradleProperty("platformTests").map(String::toBoolean).getOrElse(false)

sourceSets.test {
    kotlin { if (!platformTests) exclude("dev/markusmayer/proberover/*InRustRoverTest.kt") }
}

dependencies {
    intellijPlatform {
        if (rustRoverPath != null) local(rustRoverPath) else rustRover("2026.3")
        bundledPlugins("com.jetbrains.rust", "com.intellij.nativeDebug")
        pluginVerifier()
        if (platformTests) testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        name = "probe-rs Debugging"
        version = project.version.toString()
        changeNotes = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.map { md ->
            // The latest release's section, as simple HTML.
            md.substringAfter("\n## ").substringAfter("\n").substringBefore("\n## ").trim().lines()
                .joinToString("\n", "<ul>\n", "\n</ul>") { "<li>" + it.removePrefix("- ").trim().replace(Regex("`([^`]+)`"), "<code>$1</code>") + "</li>" }
        }
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            if (rustRoverPath != null) {
                local(file(rustRoverPath))
            } else {
                // RustRover, and CLion, which bundles the Rust plugin and Native Debugging Support.
                create(IntelliJPlatformType.RustRover, "2026.3")
                create(IntelliJPlatformType.CLion, "2026.3")
            }
        }
        // With a local IDE, its bundled plugins are all we need; don't ask the Marketplace.
        if (rustRoverPath != null) freeArgs = listOf("-offline")
        // The DAP driver base class is @ApiStatus.Internal, which is known and reported. Fail only on real breakage.
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
    }
    // Signing and Marketplace publishing read their secrets from the environment (see .github/workflows/build.yml).
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Pre-releases like 0.2.0-beta.1 go to the "beta" channel.
        channels = provider { listOf(project.version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

// A plugin repository on disk for your own IDE: Settings → Plugins → ⚙ → Manage Plugin Repositories →
// file:///…/plugin-repo/updatePlugins.xml. Then each `./gradlew publishLocalRepo` shows up as a plugin update.
val localPluginRepo = providers.gradleProperty("localPluginRepo")
    .getOrElse(System.getProperty("user.home") + "/.local/share/probe-rs-rustrover/plugin-repo")

tasks.register("publishLocalRepo") {
    group = "intellij platform"
    description = "Builds the plugin and publishes it to a local plugin repository (file://) for your own IDE"
    val zip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val pluginVersion = project.version.toString()
    val sinceBuild = "263"
    inputs.file(zip)
    doLast {
        val repo = file(localPluginRepo).apply { mkdirs() }
        // Keep a few older zips: the IDE caches updatePlugins.xml and may still offer the previous version.
        repo.listFiles { f -> f.name.endsWith(".zip") }?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val target = zip.get().asFile.copyTo(repo.resolve(zip.get().asFile.name), overwrite = true)
        repo.resolve("updatePlugins.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<plugins>
            |  <plugin id="dev.markusmayer.proberover" url="file://${target.absolutePath}" version="$pluginVersion">
            |    <idea-version since-build="$sinceBuild"/>
            |  </plugin>
            |</plugins>
            |""".trimMargin(),
        )
        logger.lifecycle("Published $pluginVersion to file://${repo.resolve("updatePlugins.xml").absolutePath}")
    }
}
