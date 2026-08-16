// Top-level build file where you can add configuration options common to all sub-projects/modules.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val isHarmonyMode = providers.gradleProperty("enableOhosTarget").getOrNull() == "true"
// 统一版本源: 全部从 libs.versions.toml 读取 (settings 在 enableOhosTarget 时已将 catalog 的
// kotlin/composeMultiplatform 键切换为 ohos 版本; 配套库经 VersionCatalogsExtension 的
// findVersion 读取 (与 build-logic 同款模式, 避免生成访问器的点分歧义)。禁止硬编码 CPF 版本
// (2026-08 曾硬编码 0.4.0 导致 compose 库解析旧版触发 CAdapter NPE)
private val ohosCatalog =
    extensions.getByType<VersionCatalogsExtension>().named("libs")

private fun ohosVersion(key: String): String =
    ohosCatalog.findVersion(key).get().requiredVersion
val ohosDependencyVersions = mapOf(
    "org.jetbrains.kotlinx:kotlinx-coroutines-core" to ohosVersion("coroutines-ohos"),
    "org.jetbrains.kotlinx:atomicfu" to ohosVersion("atomicfu-ohos"),
    "com.squareup.okio:okio" to ohosVersion("okio-ohos"),
    "org.jetbrains.kotlinx:kotlinx-serialization-core" to ohosVersion("serialization-ohos"),
    "org.jetbrains.kotlinx:kotlinx-serialization-json" to ohosVersion("serialization-ohos"),
    "androidx.room3:room3-common" to ohosVersion("room-ohos"),
    "androidx.room3:room3-runtime" to ohosVersion("room-ohos"),
)

subprojects {
    configurations.all {
        val isOhosConfiguration = name.contains("ohos", ignoreCase = true)
        // 彻底移除 Material Icons 依赖，满足“只允许用共享 XML”的要求
        exclude(group = "androidx.compose.material", module = "material-icons-core")
        exclude(group = "androidx.compose.material", module = "material-icons-extended")

        resolutionStrategy.eachDependency {
            if (isHarmonyMode && requested.group == "org.jetbrains.kotlin") {
                useVersion(ohosVersion("kotlin-ohos"))
            }
            if (isHarmonyMode && isOhosConfiguration && !name.startsWith(
                    "ksp",
                    ignoreCase = true
                )
            ) {
                val coordinate = "${requested.group}:${requested.name}"
                ohosDependencyVersions[coordinate]?.let { useVersion(it) }
                if (requested.group == "io.ktor") {
                    useVersion(ohosVersion("ktor-ohos"))
                }
                if (requested.group.startsWith("org.jetbrains.compose")) {
                    useVersion(ohosVersion("composeMultiplatform-ohos"))
                }
                if (requested.group == "org.jetbrains.androidx") {
                    useVersion(ohosVersion("androidx-ohos"))
                }
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

val ohosLibsDir = layout.projectDirectory.dir("ohosApp/entry/libs/arm64-v8a")
val ohosIncludeDir = layout.projectDirectory.dir("ohosApp/entry/src/main/cpp/include/arm64-v8a")
// 对标安卓 release 打包: 默认 stage release .so (LLVM 优化 + strip + gc-sections,
// 约 50-70MB); 需要带符号的调试库时用 -PohosBuildType=debug。
val ohosBuildType =
    providers.gradleProperty("ohosBuildType").orNull?.lowercase() ?: "release"
require(ohosBuildType == "release" || ohosBuildType == "debug") {
    "ohosBuildType must be 'release' or 'debug', but was '$ohosBuildType'."
}
val ohosBuildTypeCapitalized = ohosBuildType.replaceFirstChar { it.titlecase() }
val ohosSharedOutputDir =
    layout.projectDirectory.dir("shared/build/bin/ohosArm64/${ohosBuildType}Shared")
val ohosSharedLibrary = ohosSharedOutputDir.file("liblegado_shared.so")

val stageOhosNativeLibraries by tasks.registering(Copy::class) {
    group = "ohos"
    description = "Build and stage CPF-KMP-CMP OHOS shared library and generated API header."
    dependsOn(":shared:link${ohosBuildTypeCapitalized}SharedOhosArm64")
    into(layout.projectDirectory.dir("ohosApp"))
    from(ohosSharedLibrary) {
        into("entry/libs/arm64-v8a")
    }
    from(ohosSharedOutputDir) {
        include("*.h")
        into("entry/src/main/cpp/include/arm64-v8a")
    }
    doFirst {
        val generatedHeaders =
            ohosSharedOutputDir.asFile.listFiles { file -> file.extension == "h" }.orEmpty()
        val missing = buildList {
            if (!ohosSharedLibrary.asFile.isFile) add(ohosSharedLibrary.asFile)
            if (generatedHeaders.isEmpty()) add(ohosSharedOutputDir.file("<generated-api-header>.h").asFile)
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing CPF OHOS outputs: ${missing.joinToString()}. " +
                    "Run with -PenableOhosTarget=true, rendererBackend=fusion-renderer " +
                    "and ohosBuildType=$ohosBuildType."
            )
        }
    }
}

val verifyOhosNativeLibraries by tasks.registering {
    group = "verification"
    description = "Verify that the CPF OHOS fusion-renderer artifacts have been staged."
    dependsOn(stageOhosNativeLibraries)
    doLast {
        val stagedHeaders =
            ohosIncludeDir.asFile.listFiles { file -> file.extension == "h" }.orEmpty()
        val missingLibrary = !ohosLibsDir.file("liblegado_shared.so").asFile.isFile
        if (missingLibrary || stagedHeaders.isEmpty()) {
            throw GradleException(
                "Missing HarmonyOS native artifacts in ${ohosLibsDir.asFile} or ${ohosIncludeDir.asFile}."
            )
        }
    }
}
