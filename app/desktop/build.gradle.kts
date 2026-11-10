plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val kitchenMacIcon = providers.gradleProperty("kitchenMacIcon").orNull

dependencies {
    implementation(projects.app.sharedUi)
    implementation(projects.core.application)
    implementation(projects.data.database)
    implementation(projects.data.importers)
    implementation(projects.testkit)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.desktop.currentOs)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "dev.kitchenapp.desktop.MainKt"

        nativeDistributions {
            packageName = "Kitchen"
            packageVersion = "12.7.0"
            modules("java.sql")

            macOS {
                bundleID = "dev.kitchenapp.desktop"
                kitchenMacIcon?.let { iconFile.set(project.file(it)) }
            }
        }
    }
}
