// :modules:rjpath —— 内嵌第三方库 (vendored) 的独立承载模块。
//
// 承载 com.github.jershell.rjpath (JSONPath 引擎, MIT), 从 :foundation 移出以免第三方
// 源码与本项目代码同处一个源码树。来源与本地补丁见同包目录 VENDORED.md。
//
// 依赖面只有 kotlinx-serialization-json, 且用 api 暴露 —— 消费方 (:foundation 的
// JsonExtensions.shared.kt 等) 的公开签名里直接出现 JsonElement, 必须随本模块传递可见。
plugins {
    id("legado.kmp.library")
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: isMacHost
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false

kotlin {
    jvm()

    android {
        namespace = "com.github.jershell.rjpath"
        compileSdk = 37
        minSdk = 24
    }

    if (enableIosTarget) {
        iosArm64()
        iosSimulatorArm64()
    }

    if (enableOhosTarget) {
        pluginManager.apply("legado.kmp.ohos")
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                api(libs.kotlinx.serialization.json)
            }
        }
    }
}
