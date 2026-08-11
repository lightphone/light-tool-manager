plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
    `maven-publish`
}

android {
    namespace = "com.thelightphone.filemanager.server"
    compileSdk = 36
    defaultConfig {
        minSdk = 34
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        publishLibraryVariants("release")
    }

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        val jvmSharedTest by creating {
            dependsOn(commonTest.get())
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.get().dependsOn(jvmSharedMain)
        jvmTest.get().dependsOn(jvmSharedTest)

        jvmSharedMain.dependencies {
            api(projects.shared)
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.serverCallLogging)
            implementation(libs.ktor.serverContentNegotiation)
            implementation(libs.ktor.serializationKotlinxJson)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientJvm)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.kotlinx.datetime)
            api(libs.ktor.serverNetty)
        }

        jvmSharedTest.dependencies {
            implementation(libs.ktor.serverTestHost)
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.testJunit)
        }

        androidMain.get().dependencies {
            implementation(libs.androidx.annotation.jvm)
        }
    }
}

// Copy frontend JS build output into jvmShared resources so it's available on the classpath
val copyFrontend by tasks.registering(Copy::class) {
    dependsOn(":composeApp:jsBrowserDistribution")
    from("${project(":composeApp").layout.buildDirectory.get()}/dist/js/productionExecutable")
    into(layout.buildDirectory.dir("generated/frontend/static"))
}

// Add frontend output to JVM and Android resources
kotlin.sourceSets.getByName("jvmSharedMain") {
    resources.srcDir(copyFrontend.map { layout.buildDirectory.dir("generated/frontend") })
}

android.sourceSets.getByName("main") {
    resources.srcDir(copyFrontend.map { layout.buildDirectory.dir("generated/frontend") })
    resources.srcDir("src/jvmSharedMain/resources")
}

tasks.matching { it.name.startsWith("process") && it.name.endsWith("JavaRes") }.configureEach {
    dependsOn(copyFrontend)
}

