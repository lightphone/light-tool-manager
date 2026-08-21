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
    // KGP's default auto-downloaded Node.js version tracks current Node releases, which need a
    // glibc/libstdc++ newer than JitPack's build container ships — that container failed with
    // missing GLIBC_2.25/2.27/2.28 and CXXABI_1.3.11 trying to run a Node 24 binary. Node 16.x is
    // the last LTS line built against an old enough glibc (>= 2.17) to run there; pinning both the
    // classic JS and Wasm targets' Node installs to it avoids depending on whatever KGP's default
    // happens to be. This only affects which Node binary is downloaded/used for npm/webpack — it
    // doesn't change what Kotlin/JS or Kotlin/Wasm code compiles to.
    // NodeJsPlugin/WasmNodeJsPlugin are applied per-subproject (composeApp, shared), not to the
    // root project, but the actual NodeJsEnvSpec/WasmNodeJsEnvSpec extension they share is
    // root-level singleton state — hence checking `plugins.withType` here (inside allprojects,
    // so it fires wherever the plugin actually lands) but configuring `rootProject.the<...>()`.
    plugins.withType<NodeJsPlugin> {
        rootProject.the<NodeJsEnvSpec>().version.set("18.20.4")
    }
    plugins.withType<WasmNodeJsPlugin> {
        rootProject.the<WasmNodeJsEnvSpec>().version.set("18.20.4")
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