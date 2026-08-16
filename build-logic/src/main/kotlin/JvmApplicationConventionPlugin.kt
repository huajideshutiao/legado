package io.legado.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class JvmApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<KotlinJvmProjectExtension> {
            // 锁 vendor: 桌面端打包会把这条 toolchain 的 JDK jlink 进产物, 不锁则"发布版内嵌的
            // 是哪家 runtime"取决于构建机上恰好有什么 (本机曾被 foojay 自动装成 JBR 21,
            // 而 CI 是 Temurin) —— 同一份源码产出行为不同的包。Adoptium 与 CI 对齐。
            jvmToolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
                vendor.set(JvmVendorSpec.ADOPTIUM)
            }
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}
