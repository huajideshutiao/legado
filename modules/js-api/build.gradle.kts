plugins {
    id("legado.kmp.library")
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = isMacHost &&
    ((project.findProperty("enableIosTarget") ?: "true").toString() == "true")
val enableOhosTarget = (project.findProperty("enableOhosTarget") ?: "false").toString() == "true"

// @JsApi 注解的独立微型 KMP 模块。
// 注解本身零依赖, 但 shared/commonMain (BaseSource/CacheManager) 需要在全 target 下可见,
// 而 modules:quickjs 只有 jvm/android target, 故把注解声明抽出来单独发布全 target 变体。
// target 集合与开关必须与 shared/build.gradle.kts 保持一致, 否则 KMP 依赖解析失败。
kotlin {
    jvm()
    android {
        namespace = "com.script.jsdispatch.annotation"
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    if (enableIosTarget) {
        iosArm64()
        iosSimulatorArm64()
    }
    if (enableOhosTarget) {
        ohosArm64()
    }
}
