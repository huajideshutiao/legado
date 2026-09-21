package io.legado.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * 给各共享模块添加 CPF 的 ohosArm64 target (真机); x86_64 模拟器因 CPF fork 生态库
 * 无 ohosX64 变体不再声明 (2026-08-16 实测链接失败)。
 * 只有 CPF 分支 KGP 才有这个 DSL, 所以本文件放在 src/ohos, 由开关决定是否参与编译。
 *
 * 职责:
 * 1. 注册 ohosArm64 target (模块脚本无法直接用该 DSL)。
 * 2. 鸿蒙模式下把标准 ksoup 替换为本地 ksoup-ohos project module:
 *    commonMain 源码 (EncodingDetect/HtmlFormatter/JsoupExtensions 等) 直接引用 ksoup API,
 *    但标准 com.fleeksoft.ksoup:ksoup 没有 ohosArm64 klib, 而 ksoup-ohos 只有 ohosArm64
 *    target —— 两者都不能进 commonMain (前者 ohos 解析失败, 后者 jvm/android 解析失败)。
 *    故 commonMain 统一声明标准 ksoup, 仅在 ohos 相关配置上用 dependencySubstitution
 *    替换为 :modules:ksoup-ohos, 其余平台照常解析标准 ksoup。
 *
 * sharedLib 产物 (liblegado_shared.so) 由出口模块 :ui 配置, cinterop (quickjs/mbedtls,
 * def 文件在 data/src/cinterop) 由 :data 配置 —— 本插件不注入, 见各自 build.gradle.kts。
 */
class OhosTargetConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // 仅对含 ohos 的配置替换 ksoup (jvm/android 等配置保持标准 ksoup)。
        // 排除 ksoup-ohos 自身: 它不依赖标准 ksoup, 且 substitution 会与自身 project
        // 依赖成环 (虽无实际依赖, 避免配置期意外)。
        if (name != "ksoup-ohos") {
            configurations.configureEach {
                if (name.contains("ohos", ignoreCase = true)) {
                    resolutionStrategy.dependencySubstitution {
                        substitute(module("com.fleeksoft.ksoup:ksoup"))
                            .using(project(":modules:ksoup-ohos"))
                    }
                }
            }
        }

        extensions.configure<KotlinMultiplatformExtension> {
            ohosArm64 {}
        }
    }
}
