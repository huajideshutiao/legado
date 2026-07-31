plugins {
    id("legado.kmp.library")
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = isMacHost &&
    ((project.findProperty("enableIosTarget") ?: "true").toString() == "true")
val enableOhosTarget = (project.findProperty("enableOhosTarget") ?: "false").toString() == "true"

kotlin {
    jvm()
    androidLibrary {
        namespace = "com.script.jsdispatch.annotation"
        compileSdk = 36
        minSdk = 26
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
    }
}
