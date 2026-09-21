// :ui —— UI 层 (从 :shared 切分, 依赖 core/data/foundation)。
//
// 职责: 全部 Compose UI —— ui 包 (commonMain + sharedUiMain 页面/组件) +
// help/image 的图像加载实现 (coil/skiko) + model 的封面烘焙 (WallpaperBaker/
// DefaultCoverBaker, 依赖 compose ImageBitmap) + composeResources (随本模块分发)。
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("legado.kmp.library")
    alias(libs.plugins.kotlin.serialization)
    id("legado.compose")
}

compose.resources {
    generateResClass = always
    // app/desktop 等消费方访问 Res 资源, 显式公开
    publicResClass = true
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: isMacHost
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false
val composeVersion = libs.versions.cmp.get()

// CPF fork 只发布 Android/iOS/OHOS 变体, 不发布 Desktop JVM 变体 (与 :shared 同款策略)。
// enableOhosTarget 时 settings 把 catalog 的 cmp/lifecycleMultiplatform 整体切到 fork 版本,
// 非 ohos 目标 (jvm/android) 的依赖解析也会拿到 fork 版本, 触发
// "KMP Dependencies Resolution Failure ... Unresolved platforms: [jvm]"。
// 对策: 非 ohos 配置把 fork 版本重写回官方基线主版本 (1.9.2-0.5.0-25 → 1.9.2,
// 2.9.4-0.5.0-25 → 2.9.4); ohos 配置保持 fork 版本 (root build.gradle.kts 的
// eachDependency 已对 ohos 配置统一强制 fork 版本)。
if (enableOhosTarget) {
    val forkCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val forkComposeVersion =
        forkCatalog.findVersion("composeMultiplatform-ohos").get().requiredVersion
    val forkLifecycleVersion =
        forkCatalog.findVersion("androidx-ohos").get().requiredVersion
    configurations.configureEach {
        if (name.contains("ohos", ignoreCase = true)) return@configureEach
        resolutionStrategy.eachDependency {
            if (requested.group.startsWith("org.jetbrains.compose") &&
                requested.version == forkComposeVersion
            ) {
                useVersion(forkComposeVersion.substringBefore("-"))
                because("CPF does not publish Desktop JVM variants; non-ohos targets use the official base version")
            }
            if (requested.group.startsWith("org.jetbrains.androidx") &&
                requested.version == forkLifecycleVersion
            ) {
                useVersion(forkLifecycleVersion.substringBefore("-"))
                because("CPF does not publish Desktop JVM variants; non-ohos targets use the official base version")
            }
        }
    }
}

// JVM+Android 共享依赖 (okhttp/quickjs 随 data/core 的 api 面传入, 本模块仅补 UI 侧)。
private val sharedLibs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun KotlinDependencyHandler.sharedJvmAndroidDeps() {
    api(sharedLibs.findLibrary("quick-chinese-transfer-core").get())
    api(project(":modules:quickjs"))
    api(sharedLibs.findLibrary("okhttp").get())
    implementation(sharedLibs.findLibrary("coil3-network-okhttp").get())
}

kotlin {
    jvm()

    android {
        namespace = "io.legado.ui"
        compileSdk = 37
        minSdk = 24
        // compose.resources 需要 assets 接入 (与 :shared 同款机制)
        androidResources.enable = true
    }

    if (enableIosTarget) {
        val configureNativeCinterops: KotlinNativeTarget.() -> Unit = {
            if (isMacHost) {
                // native 库 (quickjs/mbedtls) 由 scripts/build-ios-native.sh 预编译,
                // 产物在 :data/build/iosNativeLibs (与 :data 的 cinterop 同一套 native 库)
                val nativeLibDir = file("${rootProject.projectDir}/data/build/iosNativeLibs/${konanTarget.name}")
                binaries {
                    framework {
                        baseName = "shared"
                        isStatic = false
                        binaryOption("bundleId", "shutiao.reader.shared")
                    }
                    all {
                        linkerOpts("-L${nativeLibDir.absolutePath}", "-lquickjs", "-lmbedtls", "-lsqlite3")
                        optimized = false
                    }
                }
            }
        }
        iosArm64(configureNativeCinterops)
        iosSimulatorArm64(configureNativeCinterops)
    }

    if (enableOhosTarget) {
        // CPF fork 只发布 Android/iOS/OHOS 变体, 不发布 Desktop JVM (与 :shared 同款策略)
        pluginManager.apply("legado.kmp.ohos")
        // 出口模块: 本模块产出最终 liblegado_shared.so (stage 任务只 stage :ui 的产物)。
        // ohosArm64 target 已由 legado.kmp.ohos 注册, 用标准 targets.named API 配置产物。
        targets.named<KotlinNativeTarget>("ohosArm64") {
            binaries {
                sharedLib {
                    baseName = "legado_shared"
                    if (buildType == NativeBuildType.RELEASE) {
                        // 模块分割后 -O1 预期不再 OOM: 单体 :shared 时代 -O1 在 16GB 机器上触发
                        // LLVM codegen "LLVM ERROR: out of memory" 硬崩溃 (退出码 -1073741795),
                        // 当时退到 -O0 (clangNooptFlags.ohos_arm64=-O0); 现按模块分编, LLVM 单任务
                        // 输入大幅缩小。clangNooptFlags.ohos_arm64 默认即 -O1, 显式写出 + 并行
                        // LLVM codegen (-Xbackend-threads=4) + 函数/数据级 section (-ffunction-sections
                        // -fdata-sections, 配合链接期 --gc-sections 做 R8 对标死代码消除)。
                        optimized = false
                        freeCompilerArgs += "-Xbackend-threads=4"
                        freeCompilerArgs += "-Xoverride-konan-properties=clangNooptFlags.ohos_arm64=-O1 -ffunction-sections -fdata-sections"
                        linkerOpts("-s", "--gc-sections")
                    }
                    export("org.jetbrains.compose.export:export:$composeVersion")
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
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                api(project(":foundation"))
                api(project(":data"))
                api(project(":core"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
                api(libs.okio)
                implementation(libs.kotlinx.serialization.json)
                // KsoupHtmlAnnotatedString/ReadRssRoute 等用 ksoup 解析 HTML。
                // 标准 ksoup 无 ohosArm64 klib 而 ksoup-ohos 只有 ohosArm64 target, 两者都
                // 不能进 commonMain; 由 OhosTargetConventionPlugin 在 ohos 配置上把标准 ksoup
                // 替换为 :modules:ksoup-ohos, 其余平台照常解析标准 ksoup。
                implementation(libs.ksoup)
                api("org.jetbrains.compose.components:components-resources:$composeVersion")
            }
        }
        // Compose 基线源集 (四端共享)
        val sharedUiMain = create("sharedUiMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
                implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
                implementation("org.jetbrains.compose.material:material:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui:$composeVersion")
                implementation(libs.compose.lifecycle.runtime.multiplatform)
            }
        }
        // 鸿蒙无变体三方库隔离层 (与 :shared 同款)
        val nonOhosUiMain = create("nonOhosUiMain") {
            dependsOn(sharedUiMain)
            dependencies {
                implementation(libs.reorderable)
                implementation(libs.coil3.compose)
                implementation(libs.multiplatformMarkdown)
                implementation(libs.multiplatformMarkdown.coil3)
            }
        }
        // 三端 skiko 共享层 (jvm + iOS + 鸿蒙; Android 无 skiko)
        val skikoUiMain = create("skikoUiMain") {
            dependsOn(sharedUiMain)
        }
        // iOS+鸿蒙共用 UI 层
        val iosAndOhosUiMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("iosAndOhosUiMain").apply {
                dependsOn(sharedUiMain)
                dependsOn(skikoUiMain)
            }
        } else null

        androidMain {
            dependsOn(nonOhosUiMain)
            // 共享 JVM+Android 源码根 (与 :shared 同款模式)
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.core.ktx)
                implementation(libs.coil3.gif)
                implementation(libs.vectordrawable.animated)
                implementation(libs.compose.activity)
                // SVG 解码 (ImageProvider.android 同款)
                implementation(libs.androidsvg)
                implementation(libs.androidx.webkit)
            }
        }
        jvmMain {
            dependsOn(nonOhosUiMain)
            dependsOn(skikoUiMain)
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kxml2)
                implementation(libs.androidx.sqlite.bundled)
                // SvgRasterizer 的 jsvg 渲染 + skiko 编码
                implementation(libs.jsvg)
                implementation("org.jetbrains.skiko:skiko-awt:0.144.6")
            }
        }
        val nativeMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("nativeMain").apply {
                dependsOn(commonMain.get())
            }
        } else null

        if (enableIosTarget) {
            val iosMain = maybeCreate("iosMain").apply {
                dependsOn(nativeMain!!)
                dependsOn(nonOhosUiMain)
                dependsOn(iosAndOhosUiMain!!)
                dependencies {
                    implementation(libs.androidx.sqlite.framework)
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                    implementation(libs.coil3.network.ktor3)
                }
            }
            // KGP 不会自动把 iosArm64Main/iosSimulatorArm64Main 连到自定义的 iosMain,
            // 必须显式 dependsOn (否则 nativeMain 的 actual 进不了 iOS 编译; 与 :data 同款连接)。
            maybeCreate("iosArm64Main").apply {
                dependsOn(iosMain)
            }
            maybeCreate("iosSimulatorArm64Main").apply {
                dependsOn(iosMain)
            }
        }
        if (enableOhosTarget) {
            val ohosMain = maybeCreate("ohosMain").apply {
                dependsOn(nativeMain!!)
                dependsOn(sharedUiMain)
                dependsOn(iosAndOhosUiMain!!)
                dependencies {
                    implementation("androidx.sqlite:sqlite-framework:2.7.0-alpha01-0.3.0")
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                    // K/N 2.x link 检查: sharedLib.export 导出 compose export klib, 其依赖
                    // 必须声明为 API 依赖 (implementation 会被 linkReleaseSharedOhosArm64 拒绝:
                    // "exported in the releaseShared binary are not specified as API-dependencies")。
                    api("org.jetbrains.compose.export:export:$composeVersion")
                }
            }
            // KGP 不会自动把 ohosArm64Main 连到自定义的 ohosMain, 必须显式 dependsOn
            // (否则 ohosMain 的依赖/源码进不了 ohosArm64 编译; 与 :data 同款连接)。
            maybeCreate("ohosArm64Main").apply {
                dependsOn(ohosMain)
            }
        }

        // 测试 (从 :shared 迁移)
        val jvmAndAndroidTest = create("jvmAndAndroidTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.junit)
                implementation(libs.jetbrains.kotlin.test)
                implementation(libs.kotlin.reflect)
            }
        }
        matching { it.name == "androidHostTest" }.configureEach {
            dependsOn(jvmAndAndroidTest)
        }
        jvmTest {
            dependsOn(jvmAndAndroidTest)
        }
    }
}

if (enableOhosTarget) {
    // CPF fork CMP 1.9.2 的 sharedBounds/SharedTransitionLayout 仍带
    // @ExperimentalSharedTransitionApi (官方 1.11 已转正), 而 sharedUiMain 是四端共享源码,
    // 不能为鸿蒙单独加 @OptIn —— 只在这一条编译上开 opt-in (与旧 :shared 同款)。
    tasks.matching { it.name == "compileKotlinOhosArm64" }.configureEach {
        (this as? org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile)?.compilerOptions?.optIn
            ?.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
    }
}
