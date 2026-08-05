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
            // 版本从 gradle/libs.versions.toml 读取 (单数据源, 无硬编码)
            val tomlText = file("gradle/libs.versions.toml").readText()
            version("kotlin", tomlVersionOf(tomlText, "kotlin-ohos"))
            version("composeMultiplatform", tomlVersionOf(tomlText, "composeMultiplatform-ohos"))
        }
    }
}

/** 从 libs.versions.toml 读取版本键值 (settings 阶段无 catalog 访问, 直接解析文本)。 */
private fun tomlVersionOf(toml: String, key: String): String {
    val match = Regex("""^\s*$key\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
        .find(toml)
        ?: error("gradle/libs.versions.toml 缺少版本键: $key")
    return match.groupValues[1]
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
