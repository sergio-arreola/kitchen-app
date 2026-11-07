plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.app.sharedUi)
    implementation(projects.core.application)
    implementation(projects.data.database)
    implementation(projects.data.importers)
    implementation(compose.desktop.currentOs)
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "dev.kitchenapp.desktop.MainKt"
    }
}
