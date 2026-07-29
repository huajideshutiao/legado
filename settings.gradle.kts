pluginManagement {
    includeBuild("build-logic")
    repositories {
        // CPF-KMP-CMP 统一工具链与鸿蒙适配制品。
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        mavenLocal()
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 放在公共仓库前，确保 ohosArm64 / fusion-renderer 变体优先由 CPF 仓解析。
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        mavenLocal()
        google()
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroupByRegex("com\\.github.*")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "legado"

include(":app")
// @JsApi 注解独立微型 KMP 模块 (全 target): shared/commonMain 的 BaseSource/CacheManager 需在
// iOS/鸿蒙 metadata 编译下可见, 而 modules:quickjs 仅 jvm/android target。
include(":modules:js-api")
include(":modules:quickjs")
include(":modules:quickjs-android-native")
// Rhino 备用实现源码保留但不注册 Gradle project，不参与编译、依赖解析或打包。
// include(":modules:rhino")
include(":modules:quickjs-processor")
include(":shared")
include(":desktop")
