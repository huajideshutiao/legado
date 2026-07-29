import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("legado.kmp.library")
    // Book/ReadConfig @Serializable + LocalDateAsGsonSerializer.Serializer() 编译器插件, 随 Book 下沉带入
    alias(libs.plugins.kotlin.serialization)
    // K5-c Phase 5: @Database 下沉 commonMain, shared 模块需要 room 插件配置 schema 输出
    alias(libs.plugins.room)
    // K5-c Phase 5: shared 模块 KSP 处理 @Database 生成 AppDatabase_Impl 等代码
    alias(libs.plugins.ksp)
    // KMP UI 共享: shared 启用 Compose Multiplatform, commonMain 承载 app/desktop 共享 Composable
    // (用户指示: app 模块 Compose 化尽量复用, KMP 最佳范式)
    id("legado.compose")
}

// K5-c Phase 5: Room schema 输出目录 (从 app/schemas 迁移到 shared/schemas)
// 内容零 diff: 实体字段/@Database entities/version 不变, 仅文件位置迁移
room {
    schemaDirectory("$projectDir/schemas")
}

// Res 类生成开关: Auto 模式靠"依赖图里出现 compose.components.resources 访问器坐标"来判定,
// 而本模块为绕开 fork 未发布 jvm 变体的问题改用显式坐标声明 (见 commonMain 依赖处注释),
// Auto 探测不到会 SKIP 掉 generateResourceAccessors*, 导致 legado.shared.generated.resources.Res 无法解析。
// always 直接放行生成, 与 Auto 命中时行为一致。
compose.resources {
    generateResClass = always
    // 图标保留一份源文件：非 Android 目标由 Compose Resources 打包，Android 目标
    // 通过 android.sourceSets.main.res 直接编译为原生 drawable，避免 APK 同时出现
    // res/drawable XML 与 assets/composeResources 图标副本。
    customDirectory(
        sourceSetName = "nonAndroidIconMain",
        directoryProvider = providers.provider { layout.projectDirectory.dir("src/sharedIconResources") },
    )
}

// iOS/macOS 产物只能在 macOS 主机编译：非 macOS 不注册 iOS target，从源头避免创建任务、
// 解析配置及下载 iOS 依赖。enableIosTarget 仅作为 macOS 主机上的显式开关。
val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val enableIosTarget = isMacHost &&
    (providers.gradleProperty("enableIosTarget").orNull?.toBoolean() ?: true)
val enableOhosTarget = providers.gradleProperty("enableOhosTarget").orNull?.toBoolean() ?: false

// CPF 0.4.0 的 root metadata 只发布 Android/iOS/OHOS 变体，Desktop JVM 继续使用同基线的
// JetBrains 1.9.2 平台制品。仅在 JVM 配置上切换版本，不影响 OHOS 的 fusion-renderer 变体选择。
configurations.configureEach {
    if (name.contains("jvm", ignoreCase = true)) {
        resolutionStrategy.eachDependency {
            if (requested.group.startsWith("org.jetbrains.compose") &&
                requested.version == libs.versions.composeMultiplatform.get()
            ) {
                useVersion("1.9.2")
                because("CPF 0.4.0 does not publish Desktop JVM variants")
            }
        }
    }
}

kotlin {
    // NoStackTraceException 系 expect/actual class(栈抑制为 JVM 专属),压 Beta 告警
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    android {
        namespace = "io.legado.shared"
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        androidResources {
            enable = true
        }
        withHostTest {}
    }

    // KMP 跨端 target (iOS/鸿蒙) 按 gradle 属性条件启用。
    if (enableIosTarget) {
        // KP3: 仅启用 iosArm64 + iosSimulatorArm64, 暂不启用 iosX64 (老 x86 模拟器)
        // 原因: androidx.annotation 1.10.0 / sqlite-bundled 不发布 iosX64 变体, 启用会 KMP 依赖解析失败
        // 现代 Mac 均为 ARM 架构, iosSimulatorArm64 已覆盖; iosX64 后续如需可单独升级 androidx 依赖
        // KP6: iOS 端 JS 引擎改用 quickjs cinterop (替换 JavaScriptCore), 与 Android/Desktop 端
        // quickjs 引擎统一 (全平台 quickjs)。cinterop 编译 quickjs-ng C 源码,
        // 单一数据源: def 文件 src/cinterop/quickjs.def, C 源码目录 src/cinterop/quickjs-ng/
        // (iOS / 鸿蒙 linuxArm64 共用同一份, 升级 quickjs-ng 时只需更新 src/cinterop/ 一处)
        // 两个 target 共用同一份 .def 和 C 源码 (iosArm64 真机 / iosSimulatorArm64 模拟器 ABI 不同,
        // 但 C 源码相同, cinterop 各自编译为对应 ABI 的框架)
        // mbedTLS 3.6 LTS cinterop 与 quickjs 同模式: C 源单一数据源 src/cinterop/mbedtls/,
        // 三 target (iosArm64/iosSimulatorArm64/ohosArm64) 共用, 供 native 加解密 actual (第二阶段接业务)
        // includeDirs: include/ 是 -I 根 (mbedtls/xxx.h), mbedtls/ 根目录让 MBEDTLS_CONFIG_FILE 可被找到
        val configureNativeCinterops: KotlinNativeTarget.() -> Unit = {
            // iosApp/project.yml 集成 shared.framework: baseName "shared" 对应 OTHER_LDFLAGS "-framework shared"
            // 与 FRAMEWORK_SEARCH_PATHS 的 build/bin/ios*/debugFramework 路径
            // isStatic = false: project.yml 的 dependencies 用 embed: true + codeSign: true, 且
            // LD_RUNPATH_SEARCH_PATHS 含 @executable_path/Frameworks — 均为动态 framework 集成方式
            // (静态 framework 已链入可执行文件, 不能 embed/codeSign)
            // 注: link 任务当前在 mac 上仍会因 quickjs/mbedtls 缺预编译 .a 而未定义符号失败
            // (cinterop 只编 def 内 wrapper, 不编 quickjs-ng/ 与 mbedtls/ 的 C 源), 属已知前置
            binaries {
                framework {
                    baseName = "shared"
                    isStatic = false
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
                create("nskeyvalueobserving") {
                    defFile(file("src/cinterop/nskeyvalueobserving.def"))
                }
            }
        }
        iosArm64(configureNativeCinterops)
        iosSimulatorArm64(configureNativeCinterops)
    }
    if (enableOhosTarget) {
        // CPF-KMP-CMP 0.4.0 正式 ohosArm64 target。UI 走 rendererBackend=fusion-renderer：
        // Compose 绘制录制到 ArkUI RenderNode，由系统完成 GPU 合成，不再创建 XComponent/EGL 自渲染面。
        // KP6: 鸿蒙端 JS 引擎改用 quickjs cinterop (替换原 JSVM-API dlopen/dlsym stub), 与 iOS / Android /
        // Desktop 端 quickjs 引擎统一 (全平台 quickjs)。cinterop 编译 quickjs-ng C 源码,
        // 单一数据源: def 文件 src/cinterop/quickjs.def, C 源码目录 src/cinterop/quickjs-ng/
        // (与 iOS 端共用同一份, 升级 quickjs-ng 时只需更新 src/cinterop/ 一处)
        ohosArm64 {
            // KP4: 输出 liblegado_shared.so 供 ohosApp/entry/src/main/cpp/legado_napi.cpp 链接
            // (LegadoNativeExports.kt 用 @CName 导出 C ABI, napi 桥接层 dlopen/dlsym 调用)
            // baseName "legado_shared" → 产物 liblegado_shared.so (linux 命名规范: lib<baseName>.so)
            // 根 Gradle staging 任务会将产物复制到鸿蒙 entry；CMake 缺失时直接失败
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
            // KP6: quickjs cinterop 配置 (与 iOS 端 iosArm64/iosSimulatorArm64 块语法一致)
            // 编译 quickjs-ng C 源码为 ohosArm64 静态库, 链接进 liblegado_shared.so
            // 生成 io.legado.app.napi.quickjs.* Kotlin 绑定供 OhosJsEngine 调用
            compilations.getByName("main").cinterops {
                create("quickjs") {
                    defFile(file("src/cinterop/quickjs.def"))
                    includeDirs(file("${projectDir}/src/cinterop/quickjs-ng"))
                }
                // mbedTLS cinterop (与 iOS 端同一份 def/C 源, 见上方 configureNativeCinterops 注释)
                // .c 编译进 liblegado_napi.so (ohosApp/entry/src/main/cpp/CMakeLists.txt),
                // liblegado_shared.so 的未定义符号在 napi 模块 dlopen 组内解析
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

    // 默认层级由 legado.kmp.library 统一应用；此处只追加项目特有的共享层。
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            // @JsApi 注解 (BaseSource/CacheManager 等 JS 面标注), 全 target 微型模块
            // api: 注解出现在 shared 公开类型上, app 端 KSP 需解析到该注解才能生成分派表
            api(project(":modules:js-api"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            // okio 已 KMP 发布; commonMain 用 okio.IOException (KmpCallback.onFailure 公开签名引用, 故 api)
            api(libs.okio)
            // data/entities Room 注解（@Entity/@PrimaryKey/@ColumnInfo/@Index）；room-common 已 KMP 发布
            // K5-c Phase 5: @Database 下沉 commonMain, 需要 room-runtime (RoomDatabase 基类)
            // room-ktx (协程支持) Android 专属, 不在 commonMain 暴露, 移到 androidMain
            api(libs.room.common)
            api(libs.room.runtime)
            // HtmlFormatter/EscapeUtils 的 HTML 解析（ksoup 已 KMP 发布，公开签名不泄漏其类型）
            // 原 modules:jsoup-compat 已合并到 shared commonMain/jvmAndAndroidMain (KMP 最佳范式: 单一 KMP 共享模块)
            implementation(libs.ksoup)
            // kotlinx-serialization 基础设施（KS_JSON / RuleStringSerializer / RulePolymorphicSerializer）下沉 commonMain
            // Phase A: 建立 KMP 替代 Gson 之基础，原 GsonExtensions / RuleStringAdapter / Rule.jsonDeserializer 不动
            implementation(libs.kotlinx.serialization.json)
            // 原 modules:rjpath 已合并到 shared commonMain (com.github.jershell.rjpath 包), 无需外部 project 依赖
            // http 纯核（StrResponse/OkHttpProxyClientProvider/OkHttpExceptionInterceptor/DecompressInterceptor/OkHttpUtils 主体）
            // KP5: commonMain 中 OkHttp 类型已抽象为 Kmp* 接口 (KmpHttpClient/KmpResponse 等), actual 在 jvmAndAndroidMain
            // 用 typealias 映射到 okhttp3.*。commonMain 不再直接引用 okhttp3.* 类型, 故 okhttp 依赖移到 jvmAndAndroidMain
            // (OkHttp 5.x 不发布 iosArm64/linuxArm64 等 Kotlin/Native 变体, 留 commonMain 会 KMP 依赖解析失败)
            // KMP UI 共享: Compose Multiplatform UI 依赖隔离到 sharedUiMain 源集 (UI 代码与业务逻辑分离)
            // 历史: Compose Multiplatform 曾不发布 linuxArm64 变体, 现已切换 ohosArm64 (fork 提供), 分离保留作架构隔离
            // CPF 0.4.0 已发布标准平台与 OHOS 变体，统一使用插件访问器，不再按 IDE/目标分叉坐标。
            api(compose.components.resources)
        }
        // sharedUiMain: Compose UI 共享源集, android/jvm/ios/ohos 均继承
        // 不含 reorderable/coil3/multiplatformMarkdown/ui-tooling-preview 依赖
        // (四库未发布 ohosArm64 变体) 使用这些库的代码通过 expect/actual 抽离, actual 实现在 nonOhosUiMain
        // ohosMain 提供 stub actual (Compose 原生/降级实现)
        val sharedUiMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.ui)
            }
        }
        // nonOhosUiMain: 承载 reorderable/coil3/multiplatformMarkdown 依赖和 actual 实现
        // android/jvm/ios 继承此源集; 平台专属 Preview 注解依赖分别放 androidMain/jvmMain，避免进入 iOS cinterop
        val nonOhosUiMain by creating {
            dependsOn(sharedUiMain)
            dependencies {
                // 拖拽排序: sh.calvin.reorderable 3.1.0
                implementation(libs.reorderable)
                // Coil3 KMP 图片加载
                implementation(libs.coil3.compose)
                // Markdown 渲染 (mikepenz/multiplatform-markdown-renderer)
                implementation(libs.multiplatformMarkdown)
                implementation(libs.multiplatformMarkdown.m3)
                implementation(libs.multiplatformMarkdown.coil3)
            }
        }
        // Desktop 继续允许直接调用 Skia Codec；OHOS UI 已切换 ArkUI 融合渲染，
        // 不再继承任何以 Skia 自绘为语义的共享源集。
        val skikoUiMain by creating {
            dependsOn(sharedUiMain)
        }
        // android 与桌面 jvm 共享 java.* 实现与测试（附录 J：桌面=JVM）
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // ChineseUtils 公开签名泄漏 TransType（unLoad/loadDict 入参、消费方直接引用），故 api
                api(libs.quick.chinese.transfer.core)
                // JsEncodeUtils 摘要/HMac 走 hutool（纯 JVM）；公开签名不泄漏 hutool 类型
                implementation(libs.hutool.crypto)
                // KP1.1: QuickJsJsEngine/QuickJsJsTypes 下沉 jvmAndAndroidMain, 委托 modules:quickjs
                // (KMP 化后 commonMain 暴露 QuickJsEngine/QuickJsContext/ScriptBindings 等)
                // 用 api 让 app 模块 (依赖 shared) 也能直接 import com.script.quickjs.* (QuickJsSharedJsScopeProvider 需要)
                // js-dispatch 运行时已合并进 quickjs 模块 (com.script.jsdispatch 包名不变), 无跨模块依赖
                api(project(":modules:quickjs"))
                // KP5: OkHttp 5.x 仅发布 common + android + jvm 变体, 不发布 Kotlin/Native 变体
                // commonMain 已用 Kmp* 接口抽象 okhttp3.* 类型, jvmAndAndroidMain actual 用 typealias 映射
                // 故 okhttp 依赖放 jvmAndAndroidMain (Android + 桌面 JVM 共用), iOS/鸿蒙用 Ktor 替代
                api(libs.okhttp)
                // Coil3 OkHttp 网络后端: 仅发 jvm/android 变体 (无 iosArm64/linuxArm64),
                // 必须放 jvmAndAndroidMain; 若放 sharedUiMain 会因 iosMain 继承而 iOS 依赖解析失败
                implementation(libs.coil3.network.okhttp)
                // Web 服务下沉: HttpServer/WebSocketServer 基于 NanoHTTPD(纯 Java, Android + 桌面 JVM 共用)
                implementation(libs.nanohttpd.nanohttpd)
                implementation(libs.nanohttpd.websocket)
            }
        }
        androidMain {
            dependsOn(jvmAndAndroidMain)
            // androidMain 继承 nonOhosUiMain (间接继承 sharedUiMain), 获取 reorderable/coil3/markdown actual
            dependsOn(nonOhosUiMain)
            dependencies {
                // Dispatchers.Main 的 actual 依赖 android 平台 dispatcher
                implementation(libs.kotlinx.coroutines.android)
                // DocumentUtils 方法签名公开泄漏 DocumentFile 类型, 用 api 让消费者可解析
                api(libs.androidx.documentfile)
                // room-ktx (Room 协程支持) Android 专属, 桌面 jvm target 无此 variant
                implementation(libs.room.ktx)
                // FileDownloader.android/NotificationProgress.android 用 androidx.core.net.toUri
                // 和 androidx.core.app.NotificationCompat/NotificationManagerCompat
                implementation(libs.core.ktx)
                // Coil3 GIF decoder (AnimatedImageDecoder API28+ / GifDecoder Movie), 仅 Android variant
                implementation(libs.coil3.gif)
                // Android 新 TextContextMenuProvider 的长期修复：1.11 在 SelectionContainer 复制后
                // 主动释放选区，避免平台 ActionMode 关闭前闪出仅“全选”的过渡菜单。
                implementation(libs.compose.foundation.android)
                // activity-compose: PlatformBackHandler.android actual 委托
                // androidx.activity.compose.BackHandler, 拦截系统返回键/返回手势
                implementation(libs.compose.activity)
                // Android 原生 @Preview；该制品没有 Kotlin/Native 变体，不能放进 iOS 继承的中间源集。
                implementation(compose.components.uiToolingPreview)
            }
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
            // jvmMain 继承 nonOhosUiMain (间接继承 sharedUiMain), 获取 reorderable/coil3/markdown actual
            dependsOn(nonOhosUiMain)
            // Desktop 的 Codec 逐帧动图解码继续使用 Skiko。
            dependsOn(skikoUiMain)
            dependencies {
                // Desktop 没有 Android 内置 XmlPull 与系统 SQLite，分别补齐 JVM 实现。
                implementation("net.sf.kxml:kxml2:2.3.0")
                implementation(libs.androidx.sqlite.bundled)
                // Desktop 原生 @Preview；限制在 JVM 源集，避免污染 iOS cinterop 配置。
                implementation(compose.components.uiToolingPreview)
            }
        }
        // 图标资源只由非 Android 目标继承；Android 直接将同一目录编译为原生 drawable。
        // 这样源码仍是单一数据源，同时避免 Android APK 再携带一整套 Compose drawable assets。
        val nonAndroidIconMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(nonAndroidIconMain)

        // Native 中间源集只在 iOS 或鸿蒙 target 实际启用时创建，避免普通 Windows/Linux 构建
        // 注册无消费者的 Native 源集及其依赖。
        val nativeMain = if (enableIosTarget || enableOhosTarget) {
            maybeCreate("nativeMain").apply {
                dependencies {
                    // Ktor server (CIO engine): iOS/鸿蒙 (nativeMain) 共用 Web 服务壳
                    // (替代 jvmAndAndroidMain 的 NanoHTTPD); CIO 3.1.0 发布 iosArm64/linuxArm64 变体, 纯 Kotlin 无系统库依赖
                    implementation("io.ktor:ktor-server-core:3.1.0")
                    implementation("io.ktor:ktor-server-cio:3.1.0")
                    implementation("io.ktor:ktor-server-websockets:3.1.0")
                }
            }
        } else {
            null
        }
        // iOS 源集仅在 macOS 且 iOS target 启用时创建，非 macOS 不注册其配置和依赖。
        if (enableIosTarget) maybeCreate("iosMain").apply {
            // iosMain 继承 nonOhosUiMain (间接继承 sharedUiMain), 获取 reorderable/coil3/markdown actual
            // iosMain -> nativeMain 由默认层级模板提供；这里只追加项目特有的 UI 层。
            dependsOn(nonOhosUiMain)
            dependsOn(nonAndroidIconMain)
            dependencies {
                // iOS 使用系统自带 SQLite，通过 NativeSQLiteDriver 接入 Room。
                implementation(libs.androidx.sqlite.framework)
                // iOS 加密 (AES/MD5/SHA/HMAC): krypto 4.0.10 纯 Kotlin KMP 实现
                // 替代 jvmAndAndroidMain 的 hutool; 字节级与 javax.crypto.Cipher 对拍
                implementation(libs.krypto)
                // Coil3 Ktor3 网络后端: 发布 iosArm64/iosSimulatorArm64 klib (Maven Central 已核),
                // pom 依赖 ktor-client-core 3.1.0 与本源集 Ktor 版本一致; 桥接 IosHttpProvider 的 Ktor HttpClient
                // (desktop/android 走 jvmAndAndroidMain 的 coil-network-okhttp 后端, 故本依赖 iosMain 专属不放 nonOhosUiMain)
                implementation(libs.coil3.network.ktor3)
                // iOS WebDav: Ktor 3.1.0 CIO 引擎 (纯 Kotlin, 无 OpenSSL/平台依赖)
                // 替代 jvmAndAndroidMain 的 OkHttp; 仅 iOS target 启用时拉取
                implementation("io.ktor:ktor-client-core:3.1.0")
                implementation("io.ktor:ktor-client-cio:3.1.0")
                implementation("io.ktor:ktor-client-websockets:3.1.0")
                implementation("io.ktor:ktor-client-auth:3.1.0")
                implementation("io.ktor:ktor-http-cio:3.1.0")
                implementation("io.ktor:ktor-network:3.1.0")
                implementation("io.ktor:ktor-network-tls:3.1.0")
                implementation("io.ktor:ktor-utils:3.1.0")
                implementation("io.ktor:ktor-serialization:3.1.0")
                implementation("io.ktor:ktor-events:3.1.0")
                implementation("io.ktor:ktor-sse:3.1.0")
                implementation("io.ktor:ktor-websocket-serialization:3.1.0")
            }
        }
        // iosSimulatorArm64Main / iosArm64Main 源集声明已移除
        // Kotlin 2.3.21 hierarchy template 自动处理 dependsOn(iosMain), 手写冗余
        // iosX64Main 同理已移除 (KP3: 不再启用 iosX64 target, 现代 Mac 均为 ARM 架构)
        val ohosMain = if (enableOhosTarget) maybeCreate("ohosMain").apply {
            // nativeMain 下沉: ohosMain dependsOn nativeMain (共用 Native actual, 如 ThreadBridge), 间接继承 commonMain
            dependsOn(requireNotNull(nativeMain))
            // ohosMain 直接继承 sharedUiMain, 鸿蒙端复用全部 Compose UI 代码
            // reorderable/coil3/multiplatformMarkdown 三个库未发布 ohosArm64 变体,
            // 通过 expect/actual 抽离使用点, ohosMain 提供 actual 实现 (Compose 原生/stub)
            dependsOn(sharedUiMain)
            dependsOn(nonAndroidIconMain)
            dependencies {
                // 鸿蒙 Room 2.8.x 仍没有 CPF 对应变体；数据库迁移到 Room3 前该依赖可能阻塞 OHOS link。
                implementation(libs.androidx.sqlite.bundled)
                // 导出 ComposeArkUIViewController / androidx_compose_ui_arkui_init 给 entry NAPI。
                api(libs.compose.multiplatform.export)
                // CPF 插件依据 rendererBackend 自动选择 skiko-ohosarm64-fusionrenderer；
                // 不显式添加 root skiko，避免将自渲染变体重新引入 OHOS 图。
                implementation(libs.kotlinx.datetime)
                // 鸿蒙端加密 (AES/MD5/SHA/HMAC): krypto 4.0.10 未发布 ohosArm64 变体,
                // 改用 @ohos.security.cryptoFramework napi 桥接 (NativeKryptoOps.ohos.kt → CryptoBridgeHandler.ets)
                // 鸿蒙端 WebDav: Ktor 3.1.0 CIO 引擎 (纯 Kotlin, 无系统库依赖)
                // 注: Ktor 上游官方库可能未发布 ohosArm64 变体, 若 KMP 依赖解析失败需切换 fork 版本
                implementation("io.ktor:ktor-client-core:3.1.0")
                implementation("io.ktor:ktor-client-cio:3.1.0")
                implementation("io.ktor:ktor-client-auth:3.1.0")
                implementation("io.ktor:ktor-http-cio:3.1.0")
                implementation("io.ktor:ktor-network:3.1.0")
                implementation("io.ktor:ktor-network-tls:3.1.0")
                implementation("io.ktor:ktor-utils:3.1.0")
                implementation("io.ktor:ktor-serialization:3.1.0")
                implementation("io.ktor:ktor-events:3.1.0")
                implementation("io.ktor:ktor-sse:3.1.0")
                // ktor-client-websockets / ktor-websocket-serialization: ohosArm64 变体未发布且项目零使用 WebSocket 客户端
                // 项目 WebSocket 全为服务端 (ktor-server-websockets, 见 KtorWebServerPlatform), 无需客户端模块
            }
        } else null
        // ohosArm64 target (鸿蒙) 编译时包含 ohosMain 源集代码
        // 仅在 ohosArm64 target 启用 (enableOhosTarget=true) 时注册, 否则 by getting 会找不到源集
        if (enableOhosTarget) {
            val ohosArm64Main by getting {
                dependsOn(requireNotNull(ohosMain))
            }
        }
        val jvmAndAndroidTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.junit)
                // kotlin.test KMP 标准测试框架 (JVM 底层转 JUnit4) + kotlin-reflect (字段数断言用 memberProperties)
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.jetbrains.kotlin:kotlin-reflect")
            }
        }
        getByName("androidHostTest") {
            dependsOn(jvmAndAndroidTest)
        }
        jvmTest {
            dependsOn(jvmAndAndroidTest)
        }
    }
}

// Compose Resources 1.7+ 将资源复制到 Android assets；其复制任务不会可靠清理
// 已删除/改名的旧产物。每次任务实际执行前重建自身输出，防止 SVG 等历史文件残留进 APK。
tasks.matching {
    it.name == "copyDebugComposeResourcesToAndroidAssets" ||
        it.name == "copyReleaseComposeResourcesToAndroidAssets"
}.configureEach {
    doFirst {
        outputs.files.forEach { output -> project.delete(output) }
    }
}

androidComponents {
    onVariants { variant ->
        // 与 JVM/iOS/OHOS 共用同一套 vector XML，但 Android 交给 AAPT 编译为 res，
        // 不再通过 Compose Resources 复制到 assets。
        variant.sources.res?.addStaticSourceDirectory("src/sharedIconResources")
    }
}

// K5-c Phase 5 / KP1.2: KSP 配置 - shared 模块处理 @Database 注解生成 AppDatabase_Impl 等
// KP1.2 已完成 17 个 DAO 的 suspend 迁移 (Room KMP 2.8.4 强制非 Android 平台 DAO 必须 suspend),
// 启用 kspJvm 让 Room 在 JVM target 生成 AppDatabase_Impl, 桌面端走 BundledSQLiteDriver 真实数据库。
// KP3: iOS target 默认启用, 追加 kspIosArm64/kspIosSimulatorArm64 让 Room 在 iOS 各 target 生成 AppDatabase_Impl
// (Room KMP 2.8.x 支持 Kotlin/Native); iosX64 不启用故无 kspIosX64
// CPF-KMP-CMP 工具链使用正式 ohosArm64 target，KSP 配置名为 kspOhosArm64。
// 注意: ohosArm64 target 默认禁用 (通过 -PenableOhosTarget=true 启用), kspOhosArm64 方法仅在 target 启用时存在
dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    // KP3: iOS target 默认启用, 追加 kspIosArm64/kspIosSimulatorArm64 让 Room 在 iOS 各 target 生成 AppDatabase_Impl
    // (Room KMP 2.8.x 支持 Kotlin/Native); iosX64 不启用故无 kspIosX64
    // iOS target 关闭时 (-PenableIosTarget=false), kspIosArm64 configuration 不存在, 必须条件判断避免配置失败
    if (enableIosTarget) {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
    // add("kspIosX64", libs.room.compiler)  // KP3: iosX64 不启用 (androidx 依赖缺 iosX64 变体)
    // fork 版 Compose 工具链接入: ohosArm64 target 条件启用, kspOhosArm64 仅在 enableOhosTarget=true 时添加
    if (enableOhosTarget) {
        add("kspOhosArm64", libs.room.compiler)
    }
}
