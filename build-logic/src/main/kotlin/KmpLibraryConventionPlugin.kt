package io.legado.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        // target 由各模块自行声明 (避免与 androidLibrary 的 compileSdk/minSdk 重复配置),
        // 这里只统一 JVM 基线与编译器开关。
        extensions.configure<KotlinMultiplatformExtension> {
            // 必须与 :desktop / :app 的 JVM 21 一致, 否则消费方内联 shared 的 inline 函数时
            // 报 "Cannot inline bytecode built with JVM target 21 into ... target 17"
            jvmToolchain(21)
            // expect/actual class 仍是 Beta, 各模块都在用 (LongSparseArrayCompat / AppDatabase 等)
            compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }
}
