pluginManagement {
    includeBuild("build-logic")
    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        mavenLocal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        mavenLocal()
    }
}
rootProject.name = "legado"

include(":app")
include(":modules:js-api")
include(":modules:quickjs")
include(":modules:quickjs-android-native")
include(":modules:quickjs-processor")
include(":shared")
include(":desktop")
