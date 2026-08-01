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

val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        mavenLocal()
    }
    // 鸿蒙开关打开时整体切到 CPF 分支工具链: 只有它带 ohosArm64。
    // 必须改版本目录而不是 pluginManagement.resolutionStrategy —— 后者的改写发生在
    // "插件已在 classpath" 校验之后, 会让 :app 的 alias() 请求与根项目版本对不上而失败。
    if (enableOhosTarget) {
        versionCatalogs.create("libs") {
            // 与 libs.versions.toml 的 kotlin-ohos / composeMultiplatform-ohos 保持一致
            version("kotlin", "2.2.21-0.4.0")
            version("composeMultiplatform", "1.9.2-0.4.0")
        }
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
if (enableOhosTarget) {
    include(":modules:ksoup-ohos")
}
