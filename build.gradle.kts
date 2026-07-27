// Top-level build file where you can add configuration options common to all sub-projects/modules.

// 跨子项目共享的编译参数 (K5-c Phase 5 起 compileSdk 统一从此处取, 替代原 buildscript ext{})
// 子项目用 rootProject.extra["compile_sdk_version"] as Int 引用
extra["compile_sdk_version"] = 36

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    // 桌面端入口模块需要
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
