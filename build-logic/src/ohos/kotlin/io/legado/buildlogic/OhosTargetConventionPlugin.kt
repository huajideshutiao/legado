package io.legado.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.File

/**
 * 给 :shared 添加 CPF 的 ohosArm64 target。
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

        extensions.configure<KotlinMultiplatformExtension> {
            ohosArm64 {
                binaries {
                    sharedLib {
                        baseName = "legado_shared"
                        if (buildType == NativeBuildType.RELEASE) {
                            optimized = false
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
        }
    }
}
