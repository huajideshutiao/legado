package io.legado.buildlogic

import com.android.build.api.dsl.CompileOptions
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure

/**
 * Android 模块公共 JVM 版本约定 (JDK 21) —— androidApplication / androidBenchmark 等约定插件共用。
 *
 * AGP 9 起模块 Kotlin 编译由内置 Kotlin 承担: jvmTarget 自动跟随 compileOptions.targetCompatibility。
 * Kotlin 工具链不再有 KGP jvmToolchain() 代劳, 需经 java 工具链显式给到 21 —— daemon 常跑系统
 * JDK 17, javac 在其上无法 target 21 ("无效的源发行版：21")。
 */

/**
 * 项目 Java/Kotlin 编译工具链统一为 JDK 21 (foojay resolver 自动解析本机/缓存的 JDK 21)。
 */
fun Project.configureAndroidJvm21Toolchain() {
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
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
