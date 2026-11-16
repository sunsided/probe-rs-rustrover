import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.markusmayer.proberover"
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")

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
