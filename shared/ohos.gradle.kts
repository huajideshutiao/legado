import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

val kotlin = extensions.getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()

kotlin.apply {
    ohosArm64 {
        binaries {
            sharedLib {
                baseName = "legado_shared"
                if (buildType == NativeBuildType.RELEASE) {
                    optimized = false
                }
                export(libs.compose.multiplatform.export)
                linkerOpts("-lz")
                val rendererBackend = rootProject.findProperty("rendererBackend")?.toString()
                    ?: "fusion-renderer"
                require(rendererBackend == "fusion-renderer") {
                    "Legado OHOS only supports CPF fusion rendering; rendererBackend must be 'fusion-renderer', " +
                        "but was '$rendererBackend'."
                }
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
        compilations.getByName("main").cinterops {
            create("quickjs") {
                defFile(file("src/cinterop/quickjs.def"))
                includeDirs(file("${projectDir}/src/cinterop/quickjs-ng"))
            }
            create("mbedtls") {
                defFile(file("src/cinterop/mbedtls.def"))
                includeDirs(
                    file("${projectDir}/src/cinterop/mbedtls/include"),
                    file("${projectDir}/src/cinterop/mbedtls"),
                )
            }
        }
    }
}
