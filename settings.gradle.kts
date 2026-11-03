pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "kitchen-app"

include(":core:domain")
include(":core:engine")
include(":core:application")
include(":data:database")
include(":data:importers")
include(":testkit")
include(":tools:dev-cli")
include(":app:shared-ui")
include(":app:android")
include(":app:desktop")
