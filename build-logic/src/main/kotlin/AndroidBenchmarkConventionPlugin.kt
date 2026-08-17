package io.legado.buildlogic

import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android Baseline Profile 生成模块约定 (仅 :benchmark 使用):
 * - com.android.test + androidx.baselineprofile + kotlin-android 三插件按依赖顺序 apply;
 *   com.android.test 不得再在模块中显式声明版本 (已由 baselineprofile 插件带入 classpath)。
 * - self-instrumenting 开关在 gradle.properties 的 android.experimental.self-instrumenting
 *   (项目 android.newDsl=true 已移除 CommonExtension.experimentalProperties DSL)。
 */
class AndroidBenchmarkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        pluginManager.apply("androidx.baselineprofile")
        pluginManager.apply("org.jetbrains.kotlin.android")

        configureKotlinAndroidJvm21()

        extensions.configure<TestExtension> {
            compileSdk = 37
            defaultConfig {
                // BaselineProfileRule 设备要求: Android 13 (API 33)+ 或已 root 的 Android 9 (API 28)+
                minSdk = 28
                targetSdk = 36
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                configureJavaCompat21()
            }
            // 与 :app 的 flavor 对齐 (app 唯一 flavor: mode=app), 保证变体匹配
            flavorDimensions += listOf("mode")
            productFlavors {
                create("app") {
                    dimension = "mode"
                }
            }
            targetProjectPath = ":app"
        }
    }
}
