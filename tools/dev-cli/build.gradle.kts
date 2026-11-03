plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.engine)
    implementation(projects.core.application)
    implementation(projects.data.importers)
    implementation(projects.testkit)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.kitchenapp.tools.cli.MainKt")
}
