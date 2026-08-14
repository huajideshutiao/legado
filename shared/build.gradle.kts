import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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
val composeVersion = libs.versions.composeMultiplatformOfficial.get()
val nativeInteropSourcePatterns = listOf(
    "io/legado/app/help/crypto/MbedTls*.native.kt",
    "io/legado/app/help/crypto/MbedTlsOps.native.kt",
    "io/legado/app/model/script/*.native.kt",
)
val nativeInteropSourceRoot = file("src/nativeMain/kotlin")
val stageNativeInteropForIos = if (enableIosTarget) {
    tasks.register<org.gradle.api.tasks.Sync>("stageNativeInteropForIos") {
        from(nativeInteropSourceRoot) {
            include(*nativeInteropSourcePatterns.toTypedArray())
        }
        into(layout.buildDirectory.dir("generated/nativeInterop/iosLeaf"))
    }
} else null
val stageNativeInteropForOhos = if (enableOhosTarget) {
    tasks.register<org.gradle.api.tasks.Sync>("stageNativeInteropForOhos") {
        from(nativeInteropSourceRoot) {
            include(*nativeInteropSourcePatterns.toTypedArray())
        }
        into(layout.buildDirectory.dir("generated/nativeInterop/ohosArm64Main"))
        doLast {
            val outputRoot = layout.buildDirectory
                .dir("generated/nativeInterop/ohosArm64Main")
                .get()
                .asFile
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
    }
} else null

// ohosArm64 只存在于 CPF 分支 KGP, 本脚本无法静态引用, 交给 build-logic 的约定插件声明。
if (enableOhosTarget) {
    pluginManager.apply("legado.kmp.ohos")
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
                }
                all {
                    linkerOpts("-L${nativeLibDir.absolutePath}", "-lquickjs", "-lmbedtls")
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
                // CMP 1.10+: androidx.compose.ui.tooling.preview.Preview 注解在 commonMain 可用
                // (org.jetbrains.compose.ui:ui-tooling-preview 的 commonMain 声明; android 变体
                // 转发到 androidx.compose.ui:ui-tooling-preview, 与 androidMain 的
                // components-ui-tooling-preview 解析到同一 androidx 构件, 无重复类)。
                // @Preview 函数合并进 sharedUiMain 后在此声明, IDE/desktop 插件按 FQN 识别。
                // 2026-08: CPF compose-ohos fork 无 ui-tooling-preview 的 ohosArm64 变体
                // (依赖解析失败), ohos target 排除; sharedUiMain 的 @Preview 在 ohos 由
                // ui-ohosarm64 自带的 tooling.preview 注解承担 (若有缺则报错时再补兼容层)。
                if (!enableOhosTarget) {
                    implementation("org.jetbrains.compose.ui:ui-tooling-preview:$composeVersion")
                }
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
        val skikoUiMain by creating {
            dependsOn(sharedUiMain)
        }
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(libs.quick.chinese.transfer.core)
                implementation(libs.hutool.crypto)
                api(project(":modules:quickjs"))
                api(libs.okhttp)
                implementation(libs.coil3.network.okhttp)
                implementation(libs.nanohttpd.nanohttpd)
                implementation(libs.nanohttpd.websocket)
            }
        }
        androidMain {
            dependsOn(jvmAndAndroidMain)
            dependsOn(nonOhosUiMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                api(libs.androidx.documentfile)
                implementation(libs.core.ktx)
                implementation(libs.coil3.gif)
                implementation(libs.compose.foundation.android)
                implementation(libs.compose.activity)
                implementation("org.jetbrains.compose.components:components-ui-tooling-preview:$composeVersion")
                // sharedUiMain 的 @Preview 渲染器 (ComposeViewAdapter 在 ui-tooling, 不在 ui-tooling-preview)。
                // androidLibrary 插件无 buildType, 无 debugImplementation; IDE 预览从 androidMain 源集声明的
                // 依赖取类路径 (AndroidGradleClassJarProvider.getModuleExternalLibraries), 顶层
                // add("androidRuntimeClasspath") 进不了该模型, 只能在此 implementation。改动后须 Gradle
                // sync (只编译不 sync 时 IDE 仍用旧模型); 随 AAR 发布但 release 构建被 R8 裁剪。
                implementation(libs.compose.ui.tooling.android)
                // SVG 解码 (ImageProvider.android.kt 内联 SvgDecode 依赖 androidsvg)
                implementation(libs.androidsvg)
                // AndroidWebView slot 的夜间模式 (原 WebViewUtil.applyCommonSettings → setDarkeningAllowed)
                implementation(libs.androidx.webkit)
            }
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
            dependsOn(nonOhosUiMain)
            dependsOn(skikoUiMain)
            dependencies {
                implementation("net.sf.kxml:kxml2:2.3.0")
                implementation(libs.androidx.sqlite.bundled)
                implementation("org.jetbrains.compose.components:components-ui-tooling-preview:$composeVersion")
                // SVG 栅格化 (SvgRasterizer): 纯 Java 轻量渲染器, 替代 Android 端 SvgUtils 兜底
                implementation("com.github.weisj:jsvg:2.0.0")
            }
        }
        val nativeMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("nativeMain").apply {
                dependsOn(commonMain.get())
                kotlin.exclude(
                    "io/legado/app/model/ImageProvider.native.kt",
                    *nativeInteropSourcePatterns.toTypedArray(),
                )
                dependencies {
                    implementation("io.ktor:ktor-server-core:3.1.0")
                    implementation("io.ktor:ktor-server-cio:3.1.0")
                    implementation("io.ktor:ktor-server-websockets:3.1.0")
                }
            }
        } else null

        if (enableIosTarget) {
            val iosImageProviderMain = maybeCreate("iosImageProviderMain").apply {
                dependsOn(commonMain.get())
                kotlin.srcDir("src/nativeMain/kotlin/io/legado/app/model")
                kotlin.include("ImageProvider.native.kt")
            }
            val iosMain = maybeCreate("iosMain").apply {
                dependsOn(nativeMain!!)
                dependsOn(iosImageProviderMain)
                dependsOn(nonOhosUiMain)
                dependencies {
                    implementation(libs.androidx.sqlite.framework)
                    implementation(libs.krypto)
                    implementation(libs.coil3.network.ktor3)
                    implementation("io.ktor:ktor-client-core:3.1.0")
                    implementation("io.ktor:ktor-client-cio:3.1.0")
                }
            }
            // 显式 dependsOn 会让 KGP 回退到 pre-1.9.20 默认边 (只连 commonMain),
            // 中间源集 nativeMain/iosMain 不会自动挂到 leaf, 须手工连。
            maybeCreate("iosArm64Main").apply {
                dependsOn(iosMain)
                kotlin.srcDir(layout.buildDirectory.dir("generated/nativeInterop/iosLeaf"))
            }
            maybeCreate("iosSimulatorArm64Main").apply {
                dependsOn(iosMain)
                kotlin.srcDir(layout.buildDirectory.dir("generated/nativeInterop/iosLeaf"))
            }
        }
        if (enableOhosTarget) {
            maybeCreate("ohosMain").apply {
                dependsOn(nativeMain!!)
                dependsOn(sharedUiMain)
                dependencies {
                    implementation("androidx.sqlite:sqlite-framework:2.7.0-alpha01-0.3.0")
                    implementation("io.ktor:ktor-client-core:3.1.0")
                    implementation("io.ktor:ktor-client-cio:3.1.0")
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
                // 排除 KSP 生成的 4 个 fork 不兼容文件 (suspend override + executeSQL 旧名),
                // 改用与生成物同源集 (ohosArm64Main) 的手写派生版 (AppDatabase_Impl.ohos.kt 等,
                // 见 src/ohosArm64Main/.../data/*.ohos.kt; 手写版引用 DAO Impl 等生成物,
                // 必须在 ohosArm64Main 编译单元内才可见)。exclude 只按相对路径匹配生成目录下的
                // 同名 .kt, 手写文件为 .ohos.kt 且位于 src/ 下, 不受 exclude 影响。
                kotlin.exclude(
                    "io/legado/app/data/AppDatabase_Impl.kt",
                    "io/legado/app/data/AppDatabase_AutoMigration_83_84_Impl.kt",
                    "io/legado/app/data/AppDatabase_AutoMigration_84_85_Impl.kt",
                    "io/legado/app/data/AppDatabase_AutoMigration_85_86_Impl.kt",
                )
            }
        }

        val jvmAndAndroidTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.junit)
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.jetbrains.kotlin:kotlin-reflect")
            }
        }
        matching { it.name == "androidHostTest" }.configureEach {
            dependsOn(jvmAndAndroidTest)
        }
        jvmTest {
            dependsOn(jvmAndAndroidTest)
            dependencies {
                // Pkcs7PaddingCompatibilityTest: 桌面端 JCE 补丁 (BouncyCastle) 路径守护,
                // 见 io.legado.desktop.help.DesktopCryptoProvider (仅测试类路径引入, 不进产物)
                implementation(libs.bcprov)
            }
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

if (enableOhosTarget) {
    // 0.4.0 时代 composeGenerateKnRenderBackendOhosArm64 生成任务已随 CPF 0.5.0 移除
    // (渲染后端元数据改为 OhosRenderBackendMetadataResolver 配置期解析), 无 dependsOn 需求
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
}

dependencies {
    add("kspCommonMainMetadata", libs.room.compiler)
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    if (enableIosTarget) {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
    if (enableOhosTarget) {
        // CPF 鸿蒙 room3 fork 只发布到 3.0.0-alpha01-0.3.0 (runtime/klib API 是 alpha01 时代,
        // RoomOpenDelegate/Migration/Callback 非 suspend、无 ColumnTypeConverters、无 clearAllTables 等)。
        // 官方 room3-compiler 全版本 (alpha01/alpha06/3.0.1) 均生成 suspend override + sqlite 扩展调用,
        // 与 fork 非 suspend 基类不兼容; 只能让 KSP 照常生成 (DAO 实现可用), 另由 ohosArm64Main
        // 手写 fork 适配层替换 4 个不兼容生成物 (AppDatabase_Impl + 3 AutoMigration, 见
        // ohosArm64Main 的 kotlin.exclude 与 src/ohosArm64Main/.../data/*.ohos.kt; 手写版与
        // 生成物同源集编译, 才能引用 internal 的 DAO Impl)。alpha01 编译器生成的 DAO 实现
        // 可直接编译, 故 kspOhosArm64 用 alpha01; 源码侧的 3.0.1 新注解由 ohosCompatMain 兼容声明
        // 补齐 (见 OhosRoom3Compat.kt), alpha01 旧注解 (TypeConverters/TypeConverter) 由
        // Book.kt/AppDatabase.kt 双注册 + 非鸿蒙构建的 nonOhosCompatMain 声明补齐。
        add("kspOhosArm64", "androidx.room3:room3-compiler:3.0.0-alpha01")
    }
}
