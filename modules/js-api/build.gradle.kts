import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.library)
}

// @JsApi 注解的独立微型 KMP 模块。
// 注解本身零依赖, 但 shared/commonMain (BaseSource/CacheManager) 需要在全 target 下可见,
// 而 modules:quickjs 只有 jvm/android target, 故把注解声明抽出来单独发布全 target 变体。
// target 集合与开关必须与 shared/build.gradle.kts 保持一致, 否则 KMP 依赖解析失败。
kotlin {
    jvmToolchain(17)

    androidTarget {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    jvm()

    val enableIosTarget = (project.findProperty("enableIosTarget") ?: "true").toString() == "true"
    val enableOhosTarget = (project.findProperty("enableOhosTarget") ?: "false").toString() == "true"

    if (enableIosTarget) {
        iosArm64()
        iosSimulatorArm64()
    }
    if (enableOhosTarget) {
        ohosArm64()
    }
}

android {
    compileSdk = rootProject.extra["compile_sdk_version"] as Int
    namespace = "com.script.jsdispatch.annotation"
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
