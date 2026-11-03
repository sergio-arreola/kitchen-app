import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    android {
        namespace = "dev.kitchenapp.data.importers"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:application"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
