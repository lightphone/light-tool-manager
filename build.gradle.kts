import java.util.Properties
import kotlin.apply
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

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
}

allprojects {
    // https://github.com/jitpack/jitpack.io/issues/7637#issuecomment-3738464498
    if (System.getenv("JITPACK") == "true") {
        plugins.withType<NodeJsPlugin> {
            rootProject.the<NodeJsEnvSpec>().download.set(false)
        }
        plugins.withType<WasmNodeJsPlugin> {
            rootProject.the<WasmNodeJsEnvSpec>().download.set(false)
        }
    }

    // JitPack sets GROUP/VERSION env vars (e.g. GROUP=com.github.lightphone.light-tool-manager)
    // during its build so published artifacts land under its own coordinate space. Falling back
    // to our own values keeps local builds and the GitHubPackages publish below unaffected. This
    // matters beyond cosmetics: without it, a JitPack-built module's POM would declare its
    // dependency on a sibling module (e.g. client-android -> shared-android) using OUR groupId,
    // which JitPack never published under — breaking transitive resolution for consumers.
    group = System.getenv("GROUP") ?: "com.thelightphone.toolmanager"
    version = System.getenv("VERSION") ?: "0.0.6-dirty"

    val localProperties = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/lightphone/light-tool-manager")
                    credentials {
                        username = localProperties.getProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
                        password = localProperties.getProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}