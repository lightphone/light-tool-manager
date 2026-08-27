plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    `maven-publish`
    alias(libs.plugins.vanniktechMavenPublish)
}

android {
    namespace = "com.thelightphone.toolmanager"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        publishLibraryVariants("release")
    }

    sourceSets {
        androidMain.dependencies {
            api(projects.shared)
        }
    }
}
