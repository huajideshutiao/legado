plugins {
    id("legado.kmp.library")
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
// 非 mac 也可显式开启 (-PenableIosTarget=true): klib 编译不需要 Xcode, 只有 link 才需要 (同 shared)
val enableIosTarget = (project.findProperty("enableIosTarget")?.toString()
    ?: isMacHost.toString()) == "true"
val enableOhosTarget = (project.findProperty("enableOhosTarget") ?: "false").toString() == "true"

kotlin {
    jvm()
    androidLibrary {
        namespace = "com.script.jsdispatch.annotation"
        compileSdk = 36
        minSdk = 24
    }

    if (enableIosTarget) {
        iosArm64()
        iosSimulatorArm64()
    }

    // 仅在明确启用时添加 ohos target
    if (enableOhosTarget) {
        // 使用这种方式避开标准编译器不支持 ohosArm64 的报错
        targets.findByName("ohosArm64") ?: try {
            this::class.java.getMethod("ohosArm64").invoke(this)
        } catch (e: Exception) {
        }
        // x86_64 变体 (鸿蒙 x86_64 模拟器), 同款反射规避
        targets.findByName("ohosX64") ?: try {
            this::class.java.getMethod("ohosX64").invoke(this)
        } catch (e: Exception) {
        }
    }
}
