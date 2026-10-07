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
 * 3. 同理把 compose runtime 替换为 CPF fork 版: commonMain 声明官方构件 (jvm/android 变体
 *    齐全, 供 :data 的 AnimeFilterList @Stable 等跨端注解解析), 而 ohos 编译需要 fork 的
 *    ohosArm64 变体 —— 官方构件没有, fork 构件没有 jvm 变体, 两端各取所需。
 * * sharedLib 产物 (liblegado_shared.so) 由出口模块 :ui 配置, cinterop (quickjs/mbedtls,
 * def 文件在 data/src/cinterop) 由 :data 配置 —— 本插件不注入, 见各自 build.gradle.kts。
 */
class OhosTargetConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // fork 版本号从 toml 直读 (settings 已把 libs catalog 的 cmp 键覆盖为 fork,
        // 插件内读 catalog 拿到的是 fork 值, 但语义上要的就是 fork 版本, 显式解析更可靠)
        val cmpOhosVersion = Regex("""^\s*composeMultiplatform-ohos\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(rootProject.file("gradle/libs.versions.toml").readText())
            ?.groupValues?.get(1)
            ?: error("gradle/libs.versions.toml 缺少版本键: composeMultiplatform-ohos")

        // 仅对含 ohos 的配置替换 ksoup (jvm/android 等配置保持标准 ksoup)。
        // 排除 ksoup-ohos 自身: 它不依赖标准 ksoup, 且 substitution 会与自身 project
        // 依赖成环 (虽无实际依赖, 避免配置期意外)。
        if (name != "ksoup-ohos") {
            configurations.configureEach {
                if (name.contains("ohos", ignoreCase = true)) {
                    resolutionStrategy.dependencySubstitution {
                        substitute(module("com.fleeksoft.ksoup:ksoup"))
                            .using(project(":modules:ksoup-ohos"))
                        substitute(module("com.darkrockstudios:epub4kmp-core"))
                            .using(project(":modules:epub4kmp-ohos"))
                        substitute(module("no.synth:kmp-zip-okio"))
                            .using(project(":modules:epub4kmp-ohos"))
                        substitute(module("org.jetbrains.compose.runtime:runtime"))
                            .using(module("org.jetbrains.compose.runtime:runtime:$cmpOhosVersion"))
                    }
                }
            }
        }

        extensions.configure<KotlinMultiplatformExtension> {
            ohosArm64 {}
        }
    }
}
