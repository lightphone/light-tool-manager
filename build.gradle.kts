import java.util.Properties
import kotlin.apply
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
}

// JitPack sets JITPACK=true and VERSION=<tag or commit> when it builds this repo.
// It serves multi-module builds under com.github.<org>.<repo>, so publish with that
// group to keep the Gradle module metadata consistent with what consumers request.
val isJitPack = providers.environmentVariable("JITPACK").orNull == "true"
val jitPackVersion = providers.environmentVariable("VERSION").orNull

allprojects {
    group = if (isJitPack) "com.github.lightphone.light-tool-manager" else "com.thelightphone.toolmanager"
    val toolManagerVersion = "0.0.19"
    version = providers.gradleProperty("toolmanager.version").orNull
        ?.also { logger.warn("Using local version to build tool manager: $it") }
        ?: jitPackVersion?.takeIf { isJitPack }
        ?: toolManagerVersion

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            // JitPack only needs publishToMavenLocal, and has no signing keys.
            if (!isJitPack) {
                publishToMavenCentral()
                signAllPublications()
            }

            pom {
                name.set(project.name)
                description.set("Tool Manager: a local dash for getting stuff on and off your Light Phone.")
                inceptionYear.set("2026")
                url.set("https://github.com/lightphone/light-tool-manager")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/lightphone/light-tool-manager/blob/main/LICENSE")
                        distribution.set("https://github.com/lightphone/light-tool-manager/blob/main/LICENSE")
                    }
                }

                developers {
                    developer {
                        id.set("thelightphone")
                        name.set("The Light Phone")
                        url.set("https://github.com/lightphone")
                    }
                }

                scm {
                    url.set("https://github.com/lightphone/light-tool-manager")
                    connection.set("scm:git:git://github.com/lightphone/light-tool-manager.git")
                    developerConnection.set("scm:git:ssh://git@github.com/lightphone/light-tool-manager.git")
                }
            }
        }
    }
}