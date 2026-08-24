import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("legado.kmp.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
    id("legado.compose")
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

compose.resources {
    generateResClass = always
    // CMP 1.6+ 默认 Res 为 internal; desktop 模块需要访问 shared 资源
    // (控制栏深浅色图标 ic_daytime/ic_brightness), 显式公开
    publicResClass = true
}

// 2026-08-04: 改 composeResources 后编译偶发报 Unresolved reference(上游 Gradle VFS watcher 漏事件
// + KGP 2.3.20 增量不重编生成访问器)。应对: 删 shared/build/generated/compose/resourceGenerator 后重编。
// VFS watch 与增量编译已恢复默认(偶发, 不为偶发牺牲性能)。
// CMP 资源访问器 (commonMainResourceAccessors) 由 generateResourceAccessorsForCommonMain 在
// 编译任务图内生成 (compileKotlinJvm 直接 dependsOn 它)。KGP 2.3.20 的增量编译 (ABI 快照引擎)
val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
// 非 mac 也可显式开启 (-PenableIosTarget=true): Kotlin/Native 的 Windows 分发自带 Apple platform klib,
// klib 编译 (语法/类型/签名校验) 不需要 Xcode; 只有 link 成 framework 才必须 mac。
val enableIosTarget = providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: isMacHost
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false
val composeVersion = libs.versions.cmp.get()

// CPF fork 只发布 Android/iOS/OHOS 变体, 不发布 Desktop JVM 变体 (与 desktop/build.gradle.kts
// 同款策略)。enableOhosTarget 时 settings 把 catalog 的 cmp/lifecycleMultiplatform 整体切到
// fork 版本, 非 ohos 目标 (jvm/android) 的依赖解析也会拿到 fork 版本, 触发
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

val nativeInteropSourcePatterns = listOf(
    "io/legado/app/help/crypto/MbedTls*.native.kt",
    "io/legado/app/help/crypto/MbedTlsOps.native.kt",
    "io/legado/app/model/script/*.native.kt",
)
val nativeInteropSourceRoot = file("src/nativeMain/kotlin")

// cinterop 对不完整 typedef (如 typedef struct JSContext JSContext; 仅前向声明) 只生成
// cnames.structs.* 包别名, 顶层类型名靠这里生成的 typealias 补齐。iOS/鸿蒙各 stage 任务共用。
fun generateCNamesAliases(outputRoot: File) {
    val quickJsAliases = outputRoot.resolve(
        "io/legado/app/napi/quickjs/CNamesAliases.kt"
    )
    quickJsAliases.parentFile.mkdirs()
    quickJsAliases.writeText(
        """
        @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

        package io.legado.app.napi.quickjs

        typealias JSContext = cnames.structs.JSContext
        typealias JSRuntime = cnames.structs.JSRuntime
        """.trimIndent() + "\n"
    )
    val mbedTlsAliases = outputRoot.resolve(
        "io/legado/app/nativecrypto/mbedtls/CNamesAliases.kt"
    )
    mbedTlsAliases.parentFile.mkdirs()
    mbedTlsAliases.writeText(
        """
        @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

        package io.legado.app.nativecrypto.mbedtls

        typealias mbedtls_md_info_t = cnames.structs.mbedtls_md_info_t
        """.trimIndent() + "\n"
    )
}

val stageNativeInteropForIos = if (enableIosTarget) {
    tasks.register<Sync>("stageNativeInteropForIos") {
        from(nativeInteropSourceRoot) {
            include(*nativeInteropSourcePatterns.toTypedArray())
        }
        into(layout.buildDirectory.dir("generated/nativeInterop/iosLeaf"))
        doLast {
            generateCNamesAliases(
                layout.buildDirectory.dir("generated/nativeInterop/iosLeaf").get().asFile
            )
        }
    }
} else null
fun registerOhosInteropStage(taskName: String, outputDirName: String): TaskProvider<Sync>? =
    if (enableOhosTarget) {
        tasks.register<Sync>(taskName) {
            from(nativeInteropSourceRoot) {
                include(*nativeInteropSourcePatterns.toTypedArray())
            }
            into(layout.buildDirectory.dir(outputDirName))
            doLast {
                generateCNamesAliases(
                    layout.buildDirectory.dir(outputDirName).get().asFile
                )
            }
        }
    } else null

// staged 的 nativeInterop 桥文件与别名是架构无关的 Kotlin 源, 只编 ohosArm64 (真机)
// (x86_64 模拟器: CPF fork 生态库无 ohosX64 变体, 2026-08-16 实测链接失败, 不再声明目标)。
val stageNativeInteropForOhos =
    registerOhosInteropStage("stageNativeInteropForOhos", "generated/nativeInterop/ohosArm64Main")

// CPF fork 的 room3 runtime 停在 alpha01: RoomOpenDelegate/Migration 的回调非 suspend,
// SQL 扩展名为 execSQL; 官方 room3-compiler 生成 suspend override + executeSQL, 两者编不过。
// 差异恰好是两条纯文本变换, 故 build 阶段从 KSP 生成物派生, 不再入库手写副本 (易漂移)。
abstract class DeriveOhosRoomImpl : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val generatedSources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun derive() {
        val sources = generatedSources.files.sortedBy { it.name }
        check(sources.any { it.name == "AppDatabase_Impl.kt" }) {
            "KSP 未产出 AppDatabase_Impl.kt; 本任务须在 kspKotlinOhosArm64 之后执行"
        }
        val outRoot = outputDirectory.get().asFile
        outRoot.deleteRecursively()
        val outDir = outRoot.resolve("io/legado/app/data")
        outDir.mkdirs()
        sources.forEach { source ->
            val derived = source.readText()
                // 只剥 override 上的 suspend, 其余 suspend 原样保留
                .replace("override suspend fun ", "override fun ")
                .replace("import androidx.sqlite.executeSQL", "import androidx.sqlite.execSQL")
                .replace("connection.executeSQL(", "connection.execSQL(")
            // 落 .ohos.kt: 源集 exclude 按相对路径匹配, 同名 .kt 会把派生件一起排掉
            outDir.resolve(source.name.removeSuffix(".kt") + ".ohos.kt").writeText(HEADER + derived)
        }
    }

    private companion object {
        // 措辞刻意不含 "override suspend fun" / "executeSQL" 这两个连续子串:
        // verifyOhosRoomDerived 用 contains 查残留, 头注释写原文会自伤误报
        const val HEADER = "// 由 :shared:deriveOhosRoomImpl 从 KSP 生成物派生, 勿手改 " +
            "(剥 override 上的 suspend 修饰; SQL 扩展名改 fork 的 execSQL)。\n"
    }
}

// 过渡期保险: 派生产物与 KSP 原件的文件集/DAO 集/@Database 版本号必须一致, 且派生产物
// 不得残留 fork 不兼容写法。漏改本会在 compileKotlinOhosArm64 才炸, 这里提前拦并打印差异。
abstract class VerifyOhosRoomDerived : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val generatedSources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val derivedSources: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val generated = generatedSources.files.associateBy { it.name }
        val derived = derivedSources.files
            .associateBy { it.name.removeSuffix(".ohos.kt") + ".kt" }
        val problems = mutableListOf<String>()
        val report = mutableListOf<String>()
        (generated.keys - derived.keys).sorted().takeIf { it.isNotEmpty() }?.let {
            problems += "KSP 有而派生缺: $it (KSP 新增了不兼容生成物, 对齐收集/exclude 通配)"
        }
        (derived.keys - generated.keys).sorted().takeIf { it.isNotEmpty() }?.let {
            problems += "派生有而 KSP 无: $it (残留旧派生件)"
        }
        report += "files=" + derived.keys.sorted()
        val genImpl = generated["AppDatabase_Impl.kt"]?.readText()
        val derImpl = derived["AppDatabase_Impl.kt"]?.readText()
        if (genImpl == null || derImpl == null) {
            problems += "AppDatabase_Impl 缺失 (KSP=${genImpl != null}, 派生=${derImpl != null})"
        } else {
            val genDaos = DAO_IMPL.findAll(genImpl).map { it.value }.toSortedSet()
            val derDaos = DAO_IMPL.findAll(derImpl).map { it.value }.toSortedSet()
            if (genDaos != derDaos) {
                problems += "DAO 集不一致: KSP ${genDaos.size} 个 $genDaos, 派生 ${derDaos.size} 个 $derDaos"
            }
            // 版本号取 RoomOpenDelegate 首参 (= @Database version)。两侧都取不到视为等价,
            // 只在两侧不同时 fail, 避免上游改生成格式时误伤。
            val genVersion = DB_VERSION.find(genImpl)?.groupValues?.get(1)
            val derVersion = DB_VERSION.find(derImpl)?.groupValues?.get(1)
            if (genVersion != derVersion) {
                problems += "@Database version 不一致: KSP $genVersion, 派生 $derVersion"
            }
            report += "version=${genVersion ?: "unknown"}"
            report += "dao=${genDaos.size} $genDaos"
        }
        derived.forEach { (name, file) ->
            val text = file.readText()
            if (text.contains("override suspend fun")) problems += "$name 残留 override suspend fun"
            if (text.contains("executeSQL")) problems += "$name 残留 executeSQL"
        }
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(report.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException(
                "鸿蒙 Room 派生产物校验失败:\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
    }

    private companion object {
        val DAO_IMPL = Regex("""\b\w+Dao_Impl\b""")
        val DB_VERSION = Regex("""RoomOpenDelegate\(\s*(\d+)""")
    }
}

val ohosRoomDerivedDir = layout.buildDirectory.dir("generated/ohosRoomDerived/kotlin")

// KSP 里 fork 不兼容的那几个生成物。AutoMigration 用通配: DB 版本号变动新增 AutoMigration
// 时无需改列表 (源集 exclude 用同一通配, 两处必须一致, 否则原件与派生件同时进编译)。
fun ohosRoomKspSources(): ConfigurableFileTree {
    val kspDir = layout.buildDirectory
        .dir("generated/ksp/ohosArm64/ohosArm64Main/kotlin").get().asFile
    return fileTree(kspDir).apply {
        include(
            "io/legado/app/data/AppDatabase_Impl.kt",
            "io/legado/app/data/AppDatabase_AutoMigration_*_Impl.kt",
        )
    }
}

val deriveOhosRoomImpl = if (enableOhosTarget) {
    tasks.register<DeriveOhosRoomImpl>("deriveOhosRoomImpl") {
        generatedSources.from(ohosRoomKspSources())
        outputDirectory.set(ohosRoomDerivedDir)
        // KSP 生成目录是普通路径 (不带任务产出信息), 依赖须显式声明。用任务名而非
        // tasks.matching: 名字对不上时立刻报 "task not found", 不会静默退化成无依赖。
        dependsOn("kspKotlinOhosArm64")
    }
} else null

val verifyOhosRoomDerived = if (enableOhosTarget) {
    tasks.register<VerifyOhosRoomDerived>("verifyOhosRoomDerived") {
        generatedSources.from(ohosRoomKspSources())
        derivedSources.from(fileTree(ohosRoomDerivedDir.get().asFile))
        reportFile.set(layout.buildDirectory.file("reports/ohosRoomDerived/summary.txt"))
        dependsOn(deriveOhosRoomImpl!!)
    }
} else null

// ohosArm64 只存在于 CPF 分支 KGP, 本脚本无法静态引用, 交给 build-logic 的约定插件声明。
if (enableOhosTarget) {
    pluginManager.apply("legado.kmp.ohos")
}

// JVM+Android 共享依赖 (原自定义 jvmAndAndroidMain 源集依赖; 源集已删, 共享代码改由
// jvmMain / androidMain 两个模板源集显式挂同一源码根 src/jvmAndAndroidMain/kotlin)。
// 依赖随之两目标各自声明, 统一走本 helper 防止两份漂移; okhttp/quickjs 用 api,
// desktop 模块经 shared jvm target 仍可见 (与旧源集 api 面一致)。
private val sharedLibs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun KotlinDependencyHandler.sharedJvmAndroidDeps() {
    api(sharedLibs.findLibrary("quick-chinese-transfer-core").get())
    implementation(sharedLibs.findLibrary("hutool-crypto").get())
    api(project(":modules:quickjs"))
    api(sharedLibs.findLibrary("okhttp").get())
    implementation(sharedLibs.findLibrary("coil3-network-okhttp").get())
    implementation(sharedLibs.findLibrary("nanohttpd-nanohttpd").get())
    implementation(sharedLibs.findLibrary("nanohttpd-websocket").get())
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "io.legado.shared"
        compileSdk = 37
        minSdk = 24
        // 新版 AGP KMP library 插件默认不处理 Android assets/resources, compose.resources
        // 的 copy*ComposeResourcesToAndroidAssets 任务因此拿不到 outputDirectory (配置校验失败,
        // 生成的 composeResources/ 资产也不会进 AAR)。开启后才会有 Sources.assets 供 CMP 接线。
        androidResources.enable = true
    }

    if (enableIosTarget) {
        val configureNativeCinterops: KotlinNativeTarget.() -> Unit = {
            // quickjs-ng / mbedtls 的 C 目标码由 scripts/build-ios-native.sh 预编译 (cinterop 只编 .def 内 wrapper),
            // 按 konanTarget 名分目录, 缺 .a 时 link 阶段报未定义符号。
            val nativeLibDir = file("${projectDir}/build/iosNativeLibs/${konanTarget.name}")
            binaries {
                framework {
                    baseName = "shared"
                    isStatic = false
                    // 不给则 Info.plist 的 CFBundleIdentifier 回落成 bundle name "shared" 并告警
                    binaryOption("bundleId", "shutiao.reader.shared")
                }
                all {
                    linkerOpts("-L${nativeLibDir.absolutePath}", "-lquickjs", "-lmbedtls")
                    // release 开 DevirtualizationAnalysis 全量 LTO 减体积,
                    // 内存峰值高: CI (ios.yml) 会把 gradle 堆临时提到 10g (本机不跑 iOS 链接)。
                    // 死代码剥离不自己传: Apple ld 不认 GNU 的 --gc-sections (硬失败), 且
                    // K/N 链 framework 时已自带 -dead_strip (konan Linker.kt)。
                    if (buildType == NativeBuildType.RELEASE) {
                        optimized = true
                    }
                }
            }
            // KGP 的 klib 跨平台编译要求目标不含任何 cinterop (见 KotlinNativeTarget
            // .crossCompilationOnCurrentHostSupported); 且 cinterop 本身在非 mac 上无法运行。
            // 非 mac host 跳过声明, 以便在 Windows 上跑 compileKotlinIosArm64 做语法/签名校验。
            if (isMacHost) {
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
                    create("nskeyvalueobserving") {
                        defFile(file("src/cinterop/nskeyvalueobserving.def"))
                    }
                }
            }
        }
        iosArm64(configureNativeCinterops)
        iosSimulatorArm64(configureNativeCinterops)
    }

    sourceSets {
        // ┌─ 层级总览 (条件源集仅在对应目标开启时创建; 详见各源集处注释) ────────────────
        // commonMain ─┬─ androidMain ─────────────┐ (两者各自显式挂同一源码根
        //             ├─ jvmMain ─────────────────┘  src/jvmAndAndroidMain, 原 jvmAndAndroidMain 源集)
        //             ├─ sharedUiMain (Compose 基线, 四端共享)
        //             │   ├─ nonOhosUiMain (coil3/reorderable/markdown, fork 生态无 ohos 变体)
        //             │   │   ├─ androidMain / jvmMain / iosMain
        //             │   ├─ skikoUiMain (零依赖 Skiko 层: jvm + iOS + 鸿蒙; Android 无 skiko)
        //             │   │   ├─ jvmMain
        //             │   │   └─ iosAndOhosUiMain
        //             │   └─ iosAndOhosUiMain (iOS+鸿蒙共享 UI; 名含 ohos 供 fork 版本判定)
        //             │       ├─ iosMain ── iosArm64Main / iosSimulatorArm64Main
        //             │       └─ ohosMain ─ ohosArm64Main
        //             └─ nativeMain (iOS+鸿蒙 非 UI 公共层)
        //                 ├─ iosMain
        //                 └─ ohosMain
        // 测试: commonTest ─ jvmAndAndroidTest ─ jvmTest / androidHostTest
        // 另: commonMain 按构建条件挂 ohosCompatMain / nonOhosCompatMain (room3 注解兼容);
        //     非 mac 构建时 iosMain 挂 iosWindowsCheckMain (cinterop 不可生成的 stub)。
        // └───────────────────────────────────────────────────────────────────────────
        commonMain {
            if (enableOhosTarget) {
                kotlin.srcDir("src/ohosCompatMain/kotlin")
            } else {
                // 非鸿蒙构建的 room3 旧名注解兼容 (TypeConverters/TypeConverter), 见
                // src/nonOhosCompatMain/kotlin/androidx/room3/TypeConvertersCompat.kt 注释。
                kotlin.srcDir("src/nonOhosCompatMain/kotlin")
            }
            dependencies {
                implementation(libs.kotlin.stdlib)
                api(project(":modules:js-api"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
                api(libs.okio)
                api(libs.room.common)
                api(libs.room.runtime)
                if (enableOhosTarget) {
                    implementation(project(":modules:ksoup-ohos"))
                } else {
                    implementation(libs.ksoup)
                }
                implementation(libs.kotlinx.serialization.json)
                api("org.jetbrains.compose.components:components-resources:$composeVersion")
            }
        }
        val sharedUiMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
                implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
                implementation("org.jetbrains.compose.material:material:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui:$composeVersion")
                // 书架 DB 流门控 (repeatOnLifecycle); ohos 依赖链不含本源集, 不受 fork 影响
                implementation(libs.compose.lifecycle.runtime.multiplatform)
            }
        }
        // 鸿蒙无变体三方库隔离层: coil3-compose / reorderable / multiplatformMarkdown。
        // fork 生态补齐这些库的 ohosArm64 变体后, 本源集即可并回 sharedUiMain 删除。
        val nonOhosUiMain by creating {
            dependsOn(sharedUiMain)
            dependencies {
                implementation(libs.reorderable)
                implementation(libs.coil3.compose)
                implementation(libs.multiplatformMarkdown)
                implementation(libs.multiplatformMarkdown.coil3)
            }
        }
        // 三端 skiko 共享层 (jvm + iOS + 鸿蒙): 零依赖 (skiko 经 sharedUiMain 的 compose-ui
        // 传递解析, fork 版本按目标编译期解析, 本源集无自身依赖故不落 fork 重写范围),
        // 承载只依赖 Skia 的跨端实现 (如 DefaultCoverBaker)。
        // Android 无 skiko (Android Compose 走 android.graphics), 不参与本层。
        val skikoUiMain by creating {
            dependsOn(sharedUiMain)
        }
        androidMain {
            dependsOn(nonOhosUiMain)
            // 共享 JVM+Android 源码根 (原自定义 jvmAndAndroidMain 源集):
            // 自定义中间源集被 IDE 退回 common 分析上下文导致误报, 删源集后由
            // jvmMain / androidMain 两个模板源集显式挂同一源码根, 编译语义不变,
            // IDE 按原生模板源集分析 (okhttp / JVM stdlib 均可见)。
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kotlinx.coroutines.android)
                api(libs.androidx.documentfile)
                implementation(libs.core.ktx)
                implementation(libs.coil3.gif)
                implementation(libs.compose.activity)
                // SVG 解码 (ImageProvider.android.kt 内联 SvgDecode 依赖 androidsvg)
                implementation(libs.androidsvg)
                // AndroidWebView slot 的夜间模式 (原 WebViewUtil.applyCommonSettings → setDarkeningAllowed)
                implementation(libs.androidx.webkit)
            }
        }
        jvmMain {
            dependsOn(nonOhosUiMain)
            dependsOn(skikoUiMain)
            // 同 androidMain: 挂共享 JVM+Android 源码根 (原自定义 jvmAndAndroidMain 源集)。
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies {
                sharedJvmAndroidDeps()
                implementation(libs.kxml2)
                implementation(libs.androidx.sqlite.bundled)
                // SVG 栅格化 (SvgRasterizer): 纯 Java 轻量渲染器, 替代 Android 端 SvgUtils 兜底
                implementation(libs.jsvg)
            }
        }
        val nativeMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("nativeMain").apply {
                dependsOn(commonMain.get())
                kotlin.exclude(
                    *nativeInteropSourcePatterns.toTypedArray(),
                )
                dependencies {
                    implementation(libs.ktor.server.core)
                    implementation(libs.ktor.server.cio)
                    implementation(libs.ktor.server.websockets)
                }
            }
        } else null

        // iOS + 鸿蒙共用 UI 层: 两端唯一的 UI 公共祖先是 sharedUiMain (鸿蒙进不了 nonOhosUiMain,
        // 那层的 coil3/reorderable/markdown 无 ohosArm64 变体), 故在此承载两端共享的 UI 实现;
        // 挂 skikoUiMain 让只依赖 Skia 的实现三端 (jvm+iOS+鸿蒙) 单份维护。
        // 名字带 ohos 是必需的: 根脚本与本脚本的 CPF fork 版本重写都按配置名是否含 "ohos" 判定,
        // 本源集的 metadata 配置必须落进 ohos 分支, 否则解析到无 ohosArm64 变体的官方依赖。
        //
        // # iOS/OHOS 同名 actual 对的合并边界 (2026-08 全量 diff 结论, 勿再当重复清理)
        // - 17 对是真平台分歧 (Apple framework 直调 vs napi 桥), 不可合;
        // - Room 三件套 (DatabaseMigrations/AppDatabaseDefaults/Migration84To85) 仅差
        //   CPF fork 非 suspend 回调签名, fork 对齐官方 Room3 前卡死;
        // - LocalIPv4Addresses 被 platform.darwin / platform.linux 的 cinterop 包名分裂;
        // - NativeKryptoOps / ImageBitmapLoader 是真实架构差异 (napi 回退 / bg 缓存 / http 栈);
        // - 常量对 (AppConst/AppShortcuts/字体表/UA) 与 buildNativeProxyClient (proxy 成员
        //   只在各端 actual builder 上, nativeMain 不可见) 各留 leaf actual 是正常形态。
        val iosAndOhosUiMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("iosAndOhosUiMain").apply {
                dependsOn(sharedUiMain)
                dependsOn(skikoUiMain)
            }
        } else null

        if (enableIosTarget) {
            val iosMain = maybeCreate("iosMain").apply {
                dependsOn(nativeMain!!)
                dependsOn(nonOhosUiMain)
                dependsOn(iosAndOhosUiMain!!)
                dependencies {
                    implementation(libs.androidx.sqlite.framework)
                    implementation(libs.coil3.network.ktor3)
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                }
                // 非 mac: nskeyvalueobserving cinterop (需 Xcode sysroot) 无法生成,
                // KVO 观察器 (依赖其协议类型) 排除, 由 iosWindowsCheckMain 源根的
                // 同签名 stub 类顶替; MbedTlsOps/MbedTlsCipherOps/registerNativeJsEngines
                // 的 stub actual 一并挂此源根 (expect 在 nativeMain, 同一编译单元可配对)。
                // mac 上编译真实实现, 不挂此源根。
                if (!isMacHost) {
                    kotlin.exclude(
                        "io/legado/app/help/media/AvPlayerBufferingObserver.ios.kt",
                        "io/legado/app/help/media/AvPlayerItemStatusObserver.ios.kt",
                    )
                    kotlin.srcDir("src/iosWindowsCheckMain/kotlin")
                }
            }
            // 显式 dependsOn 会让 KGP 回退到 pre-1.9.20 默认边 (只连 commonMain),
            // 中间源集 nativeMain/iosMain 不会自动挂到 leaf, 须手工连。
            maybeCreate("iosArm64Main").apply {
                dependsOn(iosMain)
                kotlin.srcDir(layout.buildDirectory.dir("generated/nativeInterop/iosLeaf"))
                // 非 mac: quickjs/mbedtls cinterop 无法生成, staged 的 interop 桥文件
                // (依赖 C 符号) 一并排除, klib 校验覆盖其余源码 (mac 上由 cinterop 提供符号)
                if (!isMacHost) {
                    kotlin.exclude(*nativeInteropSourcePatterns.toTypedArray())
                }
            }
            maybeCreate("iosSimulatorArm64Main").apply {
                dependsOn(iosMain)
                kotlin.srcDir(layout.buildDirectory.dir("generated/nativeInterop/iosLeaf"))
                if (!isMacHost) {
                    kotlin.exclude(*nativeInteropSourcePatterns.toTypedArray())
                }
            }
        }
        if (enableOhosTarget) {
            maybeCreate("ohosMain").apply {
                dependsOn(nativeMain!!)
                dependsOn(sharedUiMain)
                dependsOn(iosAndOhosUiMain!!)
                dependencies {
                    // 官方 androidx.sqlite:sqlite-framework 无 ohosArm64 变体 (cinterop 解析失败),
                    // 必须用 CPF fork 发布的版本 (带 ohosArm64 cinterop-klib), 与 iosMain 的官方版区分
                    implementation("androidx.sqlite:sqlite-framework:2.7.0-alpha01-0.3.0")
                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.cio)
                    // K/N 2.x link 检查: OhosTargetConventionPlugin 的 sharedLib.export 导出
                    // compose export klib, 其依赖必须声明为 API 依赖 (implementation 会被
                    // linkDebugSharedOhosArm64 拒绝: "exported in the debugShared binary are
                    // not specified as API-dependencies")
                    api("org.jetbrains.compose.export:export:${libs.versions.composeMultiplatform.ohos.get()}")
                }
            }
            maybeCreate("ohosArm64Main").apply {
                dependsOn(maybeCreate("ohosMain"))
                kotlin.srcDir(layout.buildDirectory.dir("generated/nativeInterop/ohosArm64Main"))
                // KSP 对动态创建源集不自动接线, 显式注册生成目录 (DAO Impl/AppDatabaseConstructor 等)
                kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/ohosArm64/ohosArm64Main/kotlin"))
                // deriveOhosRoomImpl 的派生产物 (AppDatabase_Impl.ohos.kt 等)。刻意用普通路径而非
                // 任务产出 Provider: 后者会让同源集的 kspKotlinOhosArm64 也隐式依赖派生任务,
                // 与 "派生依赖 KSP" 成环; 依赖改由 compileKotlinOhosArm64 显式声明。
                kotlin.srcDir(ohosRoomDerivedDir)
                // 排除 KSP 生成的 fork 不兼容文件 (suspend override + executeSQL 旧名), 改用
                // deriveOhosRoomImpl 派生的同源集副本 (引用 internal 的 DAO Impl, 必须同编译单元)。
                // exclude 只按相对路径匹配, 派生件是 .ohos.kt 故不受影响。
                kotlin.exclude(
                    "io/legado/app/data/AppDatabase_Impl.kt",
                    "io/legado/app/data/AppDatabase_AutoMigration_*_Impl.kt",
                )
            }
            // 只编 ohosArm64: x86_64 模拟器因 CPF fork 生态库无 ohosX64 变体无法链接
            // (2026-08-16 实测), 不再声明 ohosX64Main 编译单元。
        }

        val jvmAndAndroidTest by creating {
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

// APK 语言目录过滤已移至 app/build.gradle.kts 的 merge{Variant}Assets 任务 (见该文件注释):
// shared 的 copy*ComposeResourcesToAndroidAssets 输出只是 AAR 内资产, 最终合并发生在 app 模块
// merge{Variant}Assets (从 AAR 复制进合并输出), 在此删除会被 merge 覆盖, 故此处仅保留
// doFirst 清空输出以强制每次重建 (防止增量复用残留), 不在此做过滤。
tasks.matching {
    it.name == "copyDebugComposeResourcesToAndroidAssets" ||
        it.name == "copyReleaseComposeResourcesToAndroidAssets"
}.configureEach {
    doFirst {
        outputs.files.forEach { output -> project.delete(output) }
    }
}

if (enableIosTarget) {
    tasks.matching {
        it.name == "compileKotlinIosArm64" || it.name == "compileKotlinIosSimulatorArm64" ||
            it.name == "kspKotlinIosArm64" || it.name == "kspKotlinIosSimulatorArm64"
    }.configureEach {
        stageNativeInteropForIos?.let { dependsOn(it) }
    }
}

if (enableOhosTarget) {
    tasks.matching {
        it.name == "compileKotlinOhosArm64" || it.name == "kspKotlinOhosArm64"
    }.configureEach {
        stageNativeInteropForOhos?.let { dependsOn(it) }
    }
    // 派生只挂 compile: 它本身 dependsOn kspKotlinOhosArm64, 反挂到 ksp 会成环。
    // verify 已 dependsOn 派生, 两个都列出只为让任务图意图显式。
    tasks.matching { it.name == "compileKotlinOhosArm64" }.configureEach {
        deriveOhosRoomImpl?.let { dependsOn(it) }
        verifyOhosRoomDerived?.let { dependsOn(it) }
    }
}

dependencies {
    add("kspCommonMainMetadata", libs.room.compiler)
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    if (enableIosTarget) {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
        // native 端 @JsApi 分派表生成器 (生成 NativeGeneratedDispatch, 与 Room 生成物共存于
        // leaf 源集; jsapi.native=true 经 ksp 全局 arg 传给 JsApiProcessor, 该 arg 对 Room
        // 处理器无副作用, 处理器本身只挂在 native 目标上)
        add("kspIosArm64", project(":modules:quickjs-processor"))
        add("kspIosSimulatorArm64", project(":modules:quickjs-processor"))
    }
    if (enableOhosTarget) {
        // CPF 鸿蒙 room3 fork 只发布到 3.0.0-alpha01-0.3.0 (runtime/klib API 是 alpha01 时代,
        // RoomOpenDelegate/Migration/Callback 非 suspend、无 ColumnTypeConverters、无 clearAllTables 等)。
        // 官方 room3-compiler 全版本 (alpha01/alpha06/3.0.1) 均生成 suspend override + sqlite 扩展调用,
        // 与 fork 非 suspend 基类不兼容; 只能让 KSP 照常生成 (DAO 实现可用), 不兼容的
        // AppDatabase_Impl + AutoMigration 由 deriveOhosRoomImpl 做两条文本变换后派生进同源集
        // (见该任务与 ohosArm64Main 的 kotlin.exclude; 派生件与生成物同源集编译, 才能引用
        // internal 的 DAO Impl)。alpha01 编译器生成的 DAO 实现
        // 可直接编译, 故 kspOhosArm64 用 alpha01; 源码侧的 3.0.1 新注解由 ohosCompatMain 兼容声明
        // 补齐 (见 OhosRoom3Compat.kt), alpha01 旧注解 (TypeConverters/TypeConverter) 由
        // Book.kt/AppDatabase.kt 双注册 + 非鸿蒙构建的 nonOhosCompatMain 声明补齐。
        add("kspOhosArm64", "androidx.room3:room3-compiler:3.0.0-alpha01")
        add("kspOhosArm64", project(":modules:quickjs-processor"))
    }
}

// native 目标 KSP 全局 arg: 告知 JsApiProcessor 输出 native 形态 (仅对 quickjs-processor 生效,
// Room 处理器忽略未知 arg; Android/JVM 目标未挂 quickjs-processor, 不受影响)
// jsapi.nativeTargets: 生成器额外接管的对象类型类 (Connection.Response/StrResponse/BaseSource/
// QueryTTF/JsURL), 生成闭包按 NATIVE_JS_FACTORY_BY_CLASS 分区注入桥的对应 JS 工厂函数
// (阶段 2 规整方法; 属性/REF 返回等无法模板化者仍由手写桥保留特例)
if (enableIosTarget || enableOhosTarget) {
    ksp {
        arg("jsapi.native", "true")
        arg(
            "jsapi.nativeTargets",
            "org.jsoup.Connection.Response,io.legado.app.help.http.StrResponse,io.legado.app.data.entities.BaseSource," +
                "io.legado.app.model.analyzeRule.QueryTTF,io.legado.app.utils.JsURL"
        )
    }
}
