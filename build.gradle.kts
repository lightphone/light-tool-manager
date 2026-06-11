import java.util.Properties
import kotlin.apply

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
    group = "com.thelightphone.filemanager"
    version = "0.0.4"

    val localProperties = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/lightphone/light-file-manager")
                    credentials {
                        username = localProperties.getProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
                        password = localProperties.getProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}