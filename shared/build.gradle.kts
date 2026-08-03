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
        compileSdk = 36
        minSdk = 26
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
                }
            }
            maybeCreate("ohosArm64Main").apply {
                dependsOn(maybeCreate("ohosMain"))
                kotlin.srcDir(layout.buildDirectory.dir("generated/nativeInterop/ohosArm64Main"))
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
        }
    }
}

tasks.matching {
    it.name == "copyDebugComposeResourcesToAndroidAssets" ||
        it.name == "copyReleaseComposeResourcesToAndroidAssets"
}.configureEach {
    doFirst {
        outputs.files.forEach { output -> project.delete(output) }
    }
}

if (enableOhosTarget) {
    tasks.configureEach {
        if (name == "kspKotlinOhosArm64") {
            dependsOn("composeGenerateKnRenderBackendOhosArm64")
        }
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
}

dependencies {
    add("kspCommonMainMetadata", libs.room.compiler)
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    if (enableIosTarget) {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
}
