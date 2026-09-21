package io.legado.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * 只含 Kotlin/Native 目标的 KMP 模块约定 (无 Android 目标)。
 *
 * 与 [KmpLibraryConventionPlugin] 的区别是不应用 `com.android.kotlin.multiplatform.library`:
 * AGP 9 要求声明该插件的模块必须给出 compileSdk, 而纯 native 模块 (如 :modules:ksoup-ohos,
 * 只有 ohosArm64 目标, 无任何 Android 源码与依赖) 挂 Android 插件既无意义又会触发该校验。
 */
class KmpNativeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)
            compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }
}
