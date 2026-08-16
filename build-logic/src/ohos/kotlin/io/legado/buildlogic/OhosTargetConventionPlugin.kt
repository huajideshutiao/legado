package io.legado.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.File

/**
 * 给 :shared 添加 CPF 的 ohosArm64 / ohosX64 target (arm64 真机 + x86_64 模拟器双 ABI)。
 * 只有 CPF 分支 KGP 才有这个 DSL, 所以本文件放在 src/ohos, 由开关决定是否参与编译。
 */
class OhosTargetConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val rendererBackend = rootProject.findProperty("rendererBackend")?.toString()
            ?: "fusion-renderer"
        require(rendererBackend == "fusion-renderer") {
            "Legado OHOS only supports CPF fusion rendering; rendererBackend must be 'fusion-renderer', " +
                "but was '$rendererBackend'."
        }
        val composeExport = extensions.getByType<VersionCatalogsExtension>().named("libs")
            .findVersion("composeMultiplatform-ohos").get().requiredVersion
        val cinteropDir = File(projectDir, "src/cinterop")

        // arm64-v8a 与 x86_64 共用同一套 sharedLib/cinterop 配置 (K/N 各编一套 ABI 产物)。
        fun KotlinNativeTarget.configureOhosSharedLib() {
            binaries {
                sharedLib {
                    baseName = "legado_shared"
                    if (buildType == NativeBuildType.RELEASE) {
                        // 本机 16GB 内存 (IDE 常驻) 跑不动全量 LTO: DevirtualizationAnalysis
                        // 对 compose/skiko 全量导出项目 OOM (e548f91a4e 因此关掉 optimized)。
                        // 体积精简改走链接期手段: -s strip 本地符号表/.debug (约 38MB,
                        // 动态导出符号 .dynsym 保留, ArkTS dlopen/dlsym 不受影响) +
                        // --gc-sections 死代码消除 (对标 R8 未开混淆的精简)。
                        // 注意: linkerOpts 直传 ld.lld, 不能用 GNU ld 的 -Wl, 前缀。
                        optimized = false
                        linkerOpts("-s", "--gc-sections")
                    }
                    export("org.jetbrains.compose.export:export:$composeExport")
                    linkerOpts("-lz")
                    linkerOpts(
                        "-lnative_drawing",
                        "-limage_source",
                        "-lpixelmap",
                        "-lpixelmap_ndk.z",
                        "-lnative_window",
                        "-lace_napi.z",
                        "-lhilog_ndk.z",
                        "-lhitrace_ndk.z",
                        "-luv",
                        "-lunwind",
                        "-licu",
                    )
                }
            }
            compilations.getByName("main").cinterops.apply {
                create("quickjs") {
                    defFile(File(cinteropDir, "quickjs.def"))
                    includeDirs(File(cinteropDir, "quickjs-ng"))
                }
                create("mbedtls") {
                    defFile(File(cinteropDir, "mbedtls.def"))
                    includeDirs(
                        File(cinteropDir, "mbedtls/include"),
                        File(cinteropDir, "mbedtls"),
                    )
                }
            }
        }

        extensions.configure<KotlinMultiplatformExtension> {
            ohosArm64 { configureOhosSharedLib() }
            ohosX64 { configureOhosSharedLib() }
        }
    }
}
