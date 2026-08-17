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
        // 两组坐标都要排: androidx.compose.material (Android 变体) 与
        // org.jetbrains.compose.material (CMP 元数据/桌面变体, 含 -desktop 后缀; mediamp 0.3.0
        // 的 POM 硬声明 material-icons-extended 传递依赖即走此组, 2026-08-18 实测 37MB 进 jpackage)。
        exclude(group = "androidx.compose.material", module = "material-icons-core")
        exclude(group = "androidx.compose.material", module = "material-icons-extended")
        exclude(group = "org.jetbrains.compose.material", module = "material-icons-core")
        exclude(group = "org.jetbrains.compose.material", module = "material-icons-core-desktop")
        exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended")
        exclude(
            group = "org.jetbrains.compose.material",
            module = "material-icons-extended-desktop"
        )

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
val ohosLibsDirX64 = layout.projectDirectory.dir("ohosApp/entry/libs/x86_64")
val ohosIncludeDirX64 = layout.projectDirectory.dir("ohosApp/entry/src/main/cpp/include/x86_64")
// 对标安卓 release 打包: 默认 stage release .so (LLVM 优化 + strip + gc-sections,
// 约 50-70MB); 需要带符号的调试库时用 -PohosBuildType=debug。
val ohosBuildType =
    providers.gradleProperty("ohosBuildType").orNull?.lowercase() ?: "release"
require(ohosBuildType == "release" || ohosBuildType == "debug") {
    "ohosBuildType must be 'release' or 'debug', but was '$ohosBuildType'."
}
// 指定要 stage 的 ABI (逗号分隔)。默认只打 arm64-v8a (CPF fork 生态库 ktor/room3/sqlite-framework
// 只有 ohosArm64 变体, 2026-08-16 实测 ohosX64 链接解析失败, 故 x86_64 需显式开启):
// -PohosAbis=x86_64 (模拟器联调, 等 CPF 发布 ohosX64 变体后可用)
val ohosAbis = providers.gradleProperty("ohosAbis").orNull
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.toSet()
    ?: setOf("arm64-v8a")
require(ohosAbis.isNotEmpty() && ohosAbis.all { it == "arm64-v8a" || it == "x86_64" }) {
    "ohosAbis must be a subset of [arm64-v8a, x86_64], but was: $ohosAbis"
}
val ohosBuildTypeCapitalized = ohosBuildType.replaceFirstChar { it.titlecase() }
val ohosSharedOutputDir =
    layout.projectDirectory.dir("shared/build/bin/ohosArm64/${ohosBuildType}Shared")
val ohosSharedLibrary = ohosSharedOutputDir.file("liblegado_shared.so")
val ohosSharedOutputDirX64 =
    layout.projectDirectory.dir("shared/build/bin/ohosX64/${ohosBuildType}Shared")
val ohosSharedLibraryX64 = ohosSharedOutputDirX64.file("liblegado_shared.so")

val stageOhosNativeLibraries by tasks.registering(Copy::class) {
    group = "ohos"
    description = "Build and stage CPF-KMP-CMP OHOS shared library and generated API header."
    if ("arm64-v8a" in ohosAbis) {
        dependsOn(":shared:link${ohosBuildTypeCapitalized}SharedOhosArm64")
    }
    if ("x86_64" in ohosAbis) {
        dependsOn(":shared:link${ohosBuildTypeCapitalized}SharedOhosX64")
    }
    into(layout.projectDirectory.dir("ohosApp"))
    if ("arm64-v8a" in ohosAbis) {
        from(ohosSharedLibrary) {
            into("entry/libs/arm64-v8a")
        }
        from(ohosSharedOutputDir) {
            include("*.h")
            into("entry/src/main/cpp/include/arm64-v8a")
        }
    }
    if ("x86_64" in ohosAbis) {
        from(ohosSharedLibraryX64) {
            into("entry/libs/x86_64")
        }
        from(ohosSharedOutputDirX64) {
            include("*.h")
            into("entry/src/main/cpp/include/x86_64")
        }
    }
    doFirst {
        val missing = buildList {
            if ("arm64-v8a" in ohosAbis) {
                if (!ohosSharedLibrary.asFile.isFile) add(ohosSharedLibrary.asFile)
                if (ohosSharedOutputDir.asFile.listFiles { f -> f.extension == "h" }.orEmpty()
                        .isEmpty()
                ) {
                    add(ohosSharedOutputDir.file("<generated-api-header>.h").asFile)
                }
            }
            if ("x86_64" in ohosAbis) {
                if (!ohosSharedLibraryX64.asFile.isFile) add(ohosSharedLibraryX64.asFile)
                if (ohosSharedOutputDirX64.asFile.listFiles { f -> f.extension == "h" }.orEmpty()
                        .isEmpty()
                ) {
                    add(ohosSharedOutputDirX64.file("<generated-api-header>.h").asFile)
                }
            }
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
        val missing = buildList {
            if ("arm64-v8a" in ohosAbis) {
                if (!ohosLibsDir.file("liblegado_shared.so").asFile.isFile) add(ohosLibsDir.asFile)
                if (ohosIncludeDir.asFile.listFiles { f -> f.extension == "h" }.orEmpty()
                        .isEmpty()
                ) {
                    add(ohosIncludeDir.asFile)
                }
            }
            if ("x86_64" in ohosAbis) {
                if (!ohosLibsDirX64.file("liblegado_shared.so").asFile.isFile) add(ohosLibsDirX64.asFile)
                if (ohosIncludeDirX64.asFile.listFiles { f -> f.extension == "h" }.orEmpty()
                        .isEmpty()
                ) {
                    add(ohosIncludeDirX64.asFile)
                }
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing HarmonyOS native artifacts in ${missing.joinToString()}."
            )
        }
    }
}

// iOS 版本号与安卓端对齐 (app/build.gradle.kts):
// versionCode = 10000 + git 提交数, versionName = "3." + 构建时刻 yy.MMddHH (GMT+8)。
// 同步目标: iosApp/project.yml 的 info.properties (xcodegen generate 会用它重写 Info.plist)
// 与 iosApp/Info.plist 两处, 保证双写一致。
// 已挂进 iosApp Xcode 构建的 preBuildScripts, 每次构建自动执行; 也可手动 ./gradlew syncIosVersion。
val syncIosVersion by tasks.registering {
    group = "ios"
    description =
        "Sync iOS CFBundleShortVersionString/CFBundleVersion with Android versionName/versionCode."
    doLast {
        val commits = providers.exec {
            commandLine("git", "rev-list", "HEAD", "--count")
        }.standardOutput.asText.get().trim().toInt()
        val versionCode = 10000 + commits
        val versionName = "3." + java.time.format.DateTimeFormatter
            .ofPattern("yy.MMddHH")
            .withZone(java.time.ZoneId.of("GMT+8"))
            .format(java.time.Instant.now())

        fun rewrite(path: File, transform: (String) -> String) {
            val updated = transform(path.readText())
            if (updated != path.readText()) path.writeText(updated)
        }
        rewrite(rootProject.file("iosApp/project.yml")) {
            it.replace(
                Regex("CFBundleShortVersionString:\\s*\"[^\"]*\""),
                "CFBundleShortVersionString: \"$versionName\"",
            ).replace(
                Regex("CFBundleVersion:\\s*\"[^\"]*\""),
                "CFBundleVersion: \"$versionCode\"",
            )
        }
        rewrite(rootProject.file("iosApp/Info.plist")) {
            it.replace(
                Regex("(<key>CFBundleShortVersionString</key>\\s*<string>)[^<]*(</string>)"),
                "$1$versionName$2",
            ).replace(
                Regex("(<key>CFBundleVersion</key>\\s*<string>)[^<]*(</string>)"),
                "$1$versionCode$2",
            )
        }
        logger.lifecycle("iOS version synced: versionName=$versionName, versionCode=$versionCode")
    }
}
