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
            // 键名必须与 toml 当前别名一致: 38b4c7fdf5 把 composeMultiplatform 重命名为 cmp,
            // 若仍写旧键名, 覆盖失效, plugins 块 alias 会解析回官方 1.11.1 compose 插件
            // (无 ohosArm64/融合渲染支持, 不注入 skiko-ohosarm64-fusionrenderer,
            // 资源生成器也会生成 1.11 才有的 ResourceContentHash 注解导致 ohos 编译失败)
            version("cmp", tomlVersionOf(tomlText, "composeMultiplatform-ohos"))
            // CPF fork 的 CMP 配套 fork lifecycle: 官方 2.10.0 无 ohosArm64 变体,
            // 必须用 fork 版 (androidx-ohos, eazytec nexus 上 klib 齐全)
            version("lifecycleMultiplatform", tomlVersionOf(tomlText, "androidx-ohos"))
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
// Android Baseline Profile 生成模块 (com.android.test + androidx.baselineprofile, 仅 Android)
include(":benchmark")
include(":modules:js-api")
include(":modules:quickjs")
include(":modules:quickjs-android-native")
include(":modules:quickjs-processor")
include(":shared")
include(":desktop")
if (enableOhosTarget) {
    include(":modules:ksoup-ohos")
}
