plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    application
}

application {
    mainClass.set("com.thelightphone.filemanager.MainKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(projects.server)
    implementation(projects.shared)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
}

// Task to copy frontend build output to server resources
val copyFrontendTask = tasks.register<Copy>("copyFrontend") {
    dependsOn(":composeApp:jsBrowserDistribution")
    from("${project(":composeApp").layout.buildDirectory.get()}/dist/js/productionExecutable")
    into("${layout.buildDirectory.get()}/resources/main/static")
}

// Make sure frontend is copied before server runs
tasks.named("processResources") {
    dependsOn(copyFrontendTask)
}
