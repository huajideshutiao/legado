package io.legado.buildlogic

import com.android.build.api.dsl.CompileOptions
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Android 模块公共 JVM 版本约定 (JDK 21) —— androidApplication / androidBenchmark 等约定插件共用,
 * 原先在两插件内逐行重复的 Kotlin 工具链 + Java 编译选项配置统一收敛到此。
 */

/**
 * Kotlin Android 扩展的 JDK 21 约定: jvmToolchain(21) + compilerOptions.jvmTarget = JVM_21。
 *
 * 在模块 apply kotlin-android 插件之后调用; 扩展未就绪时 (findByType 为 null) 静默跳过,
 * 与原先各插件内联写法的语义一致。
 */
fun Project.configureKotlinAndroidJvm21() {
    extensions.findByType(KotlinAndroidProjectExtension::class.java)?.apply {
        jvmToolchain(21)
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
}

/**
 * AGP compileOptions 的 Java 源/目标兼容版本 21。
 *
 * 在 `extensions.configure<XxxExtension> { compileOptions { ... } }` 块内调用
 * (ApplicationExtension / TestExtension 等均适用)。
 */
fun CompileOptions.configureJavaCompat21() {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
