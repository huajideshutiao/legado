import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.library)
    // Book/ReadConfig @Serializable + LocalDateAsGsonSerializer.Serializer() 编译器插件, 随 Book 下沉带入
    alias(libs.plugins.kotlin.serialization)
    // K5-c Phase 5: @Database 下沉 commonMain, shared 模块需要 room 插件配置 schema 输出
    alias(libs.plugins.room)
    // K5-c Phase 5: shared 模块 KSP 处理 @Database 生成 AppDatabase_Impl 等代码
    alias(libs.plugins.ksp)
    // KMP UI 共享: shared 启用 Compose Multiplatform, commonMain 承载 app/desktop 共享 Composable
    // (用户指示: app 模块 Compose 化尽量复用, KMP 最佳范式)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// K5-c Phase 5: Room schema 输出目录 (从 app/schemas 迁移到 shared/schemas)
// 内容零 diff: 实体字段/@Database entities/version 不变, 仅文件位置迁移
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    jvmToolchain(17)

    // NoStackTraceException 系 expect/actual class(栈抑制为 JVM 专属),压 Beta 告警
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    jvm()

    // KMP 跨端 target (iOS/鸿蒙) 按 gradle 属性条件启用:
    // - KP3: iOS target 默认启用 (actual 已就绪), Windows 上 Kotlin/Native iOS 编译会失败属预期,
    //   但 gradle 配置需正确, macOS 环境可直接 ./gradlew :shared:compileKotlinIosArm64
    // - 关闭: -PenableIosTarget=false (Windows 本地开发 jvm/android 时建议关闭以加速)
    // - 鸿蒙启用: ./gradlew :shared:compileKotlinOhosArm64 -PenableOhosTarget=true
    // iosMain/ohosMain 源集代码已就绪, 启用 target 时参与编译验证; 不启用时被忽略, 不影响 jvm/android 构建
    // KP4: 鸿蒙 target 默认关闭 (需通过 -PenableOhosTarget=true 显式启用)
    // KP5: sharedUiMain 源集分离完成, Compose UI 代码已从 commonMain 迁出, linuxArm64 启用阻塞解除
    // 原阻塞原因: Compose Multiplatform 不发布 linuxArm64 变体, commonMain 含 Compose UI 代码会导致 KMP 依赖解析失败
    // 现状: Compose UI 隔离到 sharedUiMain, ohosMain 直接继承 sharedUiMain, 鸿蒙端复用全部 Compose UI 代码
    val enableIosTarget = (project.findProperty("enableIosTarget") ?: "true").toString() == "true"
    val enableOhosTarget = (project.findProperty("enableOhosTarget") ?: "false").toString() == "true"

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
        val configureQuickjsCinterop: KotlinNativeTarget.() -> Unit = {
            compilations.getByName("main").cinterops {
                create("quickjs") {
                    defFile(file("src/cinterop/quickjs.def"))
                    includeDirs(file("${projectDir}/src/cinterop/quickjs-ng"))
                }
            }
        }
        iosArm64(configureQuickjsCinterop)
        iosSimulatorArm64(configureQuickjsCinterop)
    }
    if (enableOhosTarget) {
        // 鸿蒙端 Compose 支持 (fork 版 compose-multiplatform 提供 ohosArm64 target)
        // 原方案用 linuxArm64 target 模拟鸿蒙 (因 Compose Multiplatform 不发布 linuxArm64 变体, 鸿蒙端 UI 用 ArkTS)
        // 现 fork 版 compose-multiplatform 1.8.2.99-alipay + skiko 0.9.4.2.40-alipay 提供 ohosArm64 变体,
        // 鸿蒙端可真正使用 Compose (skiko 渲染), 不再需要 ArkTS UI
        // krypto 4.0.10 / Ktor 3.1.0 CIO 上游官方库若未发布 ohosArm64 变体, 启用时可能 KMP 依赖解析失败
        // (留待后续 Compose UI 重写任务处理: 替换为鸿蒙 native 等价方案或 fork 版本)
        // KP6: 鸿蒙端 JS 引擎改用 quickjs cinterop (替换原 JSVM-API dlopen/dlsym stub), 与 iOS / Android /
        // Desktop 端 quickjs 引擎统一 (全平台 quickjs)。cinterop 编译 quickjs-ng C 源码,
        // 单一数据源: def 文件 src/cinterop/quickjs.def, C 源码目录 src/cinterop/quickjs-ng/
        // (与 iOS 端共用同一份, 升级 quickjs-ng 时只需更新 src/cinterop/ 一处)
        ohosArm64 {
            // KP4: 输出 liblegado_shared.so 供 ohosApp/entry/src/main/cpp/legado_napi.cpp 链接
            // (LegadoNativeExports.kt 用 @CName 导出 C ABI, napi 桥接层 dlopen/dlsym 调用)
            // baseName "legado_shared" → 产物 liblegado_shared.so (linux 命名规范: lib<baseName>.so)
            // CMakeLists.txt 中 LEGADO_SHARED_LIB_PATH 引用此产物, 不存在时退化为 mock
            binaries {
                sharedLib {
                    baseName = "legado_shared"
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
                // 鸿蒙端 Compose 渲染框架桥接 cinterop (libmykmp_framework.so, 参考 mykmp-antui/common/build.gradle.kts)
                // 头文件 antui_framework.h 声明 GetArkTsEnv / PostTaskByUVLooper 等 C ABI,
                // 链接 libmykmp_framework.so (从 ohosApp/antui_framework/src/main/cpp/ C++ 源码用 OHOS NDK + CMake 构建)
                create("mykmp_framework") {
                    defFile(project.file("src/ohosMain/nativeInterop/cinterop/antui_framework.def"))
                    compilerOpts("-I${project.projectDir.absolutePath}/src/ohosMain/nativeInterop/cinterop")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            // PageAnim.kt @IntDef 注解需要; androidx.annotation 1.10.0 已 KMP 发布
            implementation(libs.androidx.annotation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            // data/entities Room 注解（@Entity/@PrimaryKey/@ColumnInfo/@Index）；room-common 已 KMP 发布
            // K5-c Phase 5: @Database 下沉 commonMain, 需要 room-runtime (RoomDatabase 基类)
            // room-ktx (协程支持) Android 专属, 不在 commonMain 暴露, 移到 androidMain
            api(libs.room.common)
            api(libs.room.runtime)
            // KMP Room 2.8.x 配套: sqlite-bundled 提供 BundledSQLiteDriver (跨平台 SQLite)
            // commonMain 引入让 jvm/android/ios/ohos 均可直接用 BundledSQLiteDriver()
            // 见 https://developer.android.com/kotlin/multiplatform/room
            implementation(libs.androidx.sqlite.bundled)
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
            // 例外: compose.components.resources (资源加载, 非 UI 代码) 放 commonMain, 触发 generateComposeResClass
            // task 生成 Res 类 (task onlyIf 判定基于 commonMain 依赖图); ohos target 默认关闭 (-PenableOhosTarget=false),
            // 启用时需引入 composeMain 中间源集隔离 Compose 资源依赖 (参考 sharedUiMain 模式)
            // 官方文档: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources-setup.html
            api(compose.components.resources)
        }
        // sharedUiMain: Compose UI 共享源集, android/jvm/ios/ohos 均继承
        // 不含 reorderable/coil3/multiplatformMarkdown/ui-tooling-preview/material-icons-extended 依赖
        // (五库未发布 ohosArm64 变体) 使用这些库的代码通过 expect/actual 抽离, actual 实现在 nonOhosUiMain
        // ohosMain 提供 stub actual (Compose 原生/降级实现)
        val sharedUiMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                // MD2: Md2TextField 用 MD2 OutlinedTextField 标签浮动样式 (md3 形态不同)
                implementation(compose.material)
                implementation(compose.ui)
                // @Preview 注解依赖 (compose.uiTooling 含多平台渲染支持)
                implementation(compose.uiTooling)
                // ui-tooling-preview / material-icons-extended 移到 nonOhosUiMain (fork 未发布 ohosArm64 变体)
            }
        }
        // nonOhosUiMain: 承载 reorderable/coil3/multiplatformMarkdown/ui-tooling-preview/material-icons-extended 依赖和 actual 实现
        // android/jvm/ios 继承此源集; ohosMain 不继承, 只继承 sharedUiMain + 提供 stub actual
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
                // @Preview 注解 (fork 未发布 ohosArm64 变体, 仅 android/jvm/ios 使用)
                implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.8.2")
                // material-icons-extended 含 ~5000 图标 (fork 未发布 ohosArm64 变体, 仅 android/jvm/ios 使用)
                implementation(compose.materialIconsExtended)
            }
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
                // epublib 下沉: org.xmlpull.v1.XmlSerializer / XmlPullParserFactory 接口由 kxml2 提供
                // (Android 内置 kxml2 实现, 桌面 JVM 无 xmlpull API; 引入 kxml2 让两端行为一致, Android 端不冲突)
                // kxml2 纯 Java 实现, 仅发布 JVM 变体, 故放 jvmAndAndroidMain (Android + 桌面 JVM 共用)
                implementation("net.sf.kxml:kxml2:2.3.0")
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
            }
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
            // jvmMain 继承 nonOhosUiMain (间接继承 sharedUiMain), 获取 reorderable/coil3/markdown actual
            dependsOn(nonOhosUiMain)
            dependencies {
                // 桌面端 ResourceProvider.jvm 用 Icons.Filled.* 替代 painterResource(R.drawable.*)
                // material-icons-extended 含 ~5000 图标; core 只有 ~30, 不含 Search/MoreVert 等
                implementation(compose.materialIconsExtended)
            }
        }
        // Native 中间源集: iOS / 鸿蒙 (Kotlin/Native target) 共用 actual 实现
        // nativeMain dependsOn commonMain; iosMain/ohosMain dependsOn nativeMain 间接继承 commonMain
        // nativeMain 不依赖 sharedUiMain; iosMain 继承 nonOhosUiMain, ohosMain 继承 sharedUiMain
        val nativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Ktor server (CIO engine): iOS/鸿蒙 (nativeMain) 共用 Web 服务壳
                // (替代 jvmAndAndroidMain 的 NanoHTTPD); CIO 3.1.0 发布 iosArm64/linuxArm64 变体, 纯 Kotlin 无系统库依赖
                implementation("io.ktor:ktor-server-core:3.1.0")
                implementation("io.ktor:ktor-server-cio:3.1.0")
                implementation("io.ktor:ktor-server-websockets:3.1.0")
            }
        }
        // iOS / 鸿蒙源集: 仅在对应 target 启用时参与编译 (默认忽略)
        // 各 expect 的 actual 实现放 iosMain/ohosMain 目录, 启用 target 时编译验证
        val iosMain by creating {
            // iosMain 继承 nonOhosUiMain (间接继承 sharedUiMain), 获取 reorderable/coil3/markdown actual
            // nativeMain 下沉: iosMain dependsOn nativeMain (共用 Native actual, 如 ThreadBridge)
            dependsOn(nativeMain)
            dependsOn(nonOhosUiMain)
            dependencies {
                // iOS 加密 (AES/MD5/SHA/HMAC): krypto 4.0.10 纯 Kotlin KMP 实现
                // 替代 jvmAndAndroidMain 的 hutool; 字节级与 javax.crypto.Cipher 对拍
                implementation(libs.krypto)
                // iOS Compose material-icons: ResourceProvider.ios.kt 用 Icons.Filled.Help 占位
                // commonMain 的 compose.material3 不传递 material-icons, 需 iOS source set 显式声明
                // Compose Multiplatform compose 对象无 materialIconsCore 属性, 用 materialIconsExtended 替代
                // (jvmMain 显式声明 materialIconsExtended 同理)
                implementation(compose.materialIconsExtended)
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
        val ohosMain by creating {
            // nativeMain 下沉: ohosMain dependsOn nativeMain (共用 Native actual, 如 ThreadBridge), 间接继承 commonMain
            dependsOn(nativeMain)
            // ohosMain 直接继承 sharedUiMain, 鸿蒙端复用全部 Compose UI 代码
            // reorderable/coil3/multiplatformMarkdown 三个库未发布 ohosArm64 变体,
            // 通过 expect/actual 抽离使用点, ohosMain 提供 actual 实现 (Compose 原生/stub)
            dependsOn(sharedUiMain)
            dependencies {
                // skiko 0.9.4.2.40-alipay (鸿蒙端 Compose 渲染后端, 提供 ohosArm64 变体)
                implementation(libs.skiko)
                // kotlinx-datetime (compose-multiplatform 1.8.2.99-alipay 传递依赖, 显式声明)
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
        }
        // ohosArm64 target (鸿蒙) 编译时包含 ohosMain 源集代码
        // 仅在 ohosArm64 target 启用 (enableOhosTarget=true) 时注册, 否则 by getting 会找不到源集
        if (enableOhosTarget) {
            val ohosArm64Main by getting {
                dependsOn(ohosMain)
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
        androidUnitTest {
            dependsOn(jvmAndAndroidTest)
        }
        jvmTest {
            dependsOn(jvmAndAndroidTest)
        }
    }
}

android {
    compileSdk = rootProject.extra["compile_sdk_version"] as Int
    namespace = "io.legado.shared"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        // printOnDebug 的 android actual 走本模块 BuildConfig.DEBUG（与 app release 行为一致）
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkDependencies = true
    }
}

// K5-c Phase 5 / KP1.2: KSP 配置 - shared 模块处理 @Database 注解生成 AppDatabase_Impl 等
// KP1.2 已完成 17 个 DAO 的 suspend 迁移 (Room KMP 2.8.4 强制非 Android 平台 DAO 必须 suspend),
// 启用 kspJvm 让 Room 在 JVM target 生成 AppDatabase_Impl, 桌面端走 BundledSQLiteDriver 真实数据库。
// KP3: iOS target 默认启用, 追加 kspIosArm64/kspIosSimulatorArm64 让 Room 在 iOS 各 target 生成 AppDatabase_Impl
// (Room KMP 2.8.x 支持 Kotlin/Native); iosX64 不启用故无 kspIosX64
// fork 版 Compose 工具链接入: 鸿蒙 target 从 linuxArm64 切换为 ohosArm64, KSP 配置相应改为 kspOhosArm64
// 注意: ohosArm64 target 默认禁用 (通过 -PenableOhosTarget=true 启用), kspOhosArm64 方法仅在 target 启用时存在
dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    // KP3: iOS target 默认启用, 追加 kspIosArm64/kspIosSimulatorArm64 让 Room 在 iOS 各 target 生成 AppDatabase_Impl
    // (Room KMP 2.8.x 支持 Kotlin/Native); iosX64 不启用故无 kspIosX64
    // iOS target 关闭时 (-PenableIosTarget=false), kspIosArm64 configuration 不存在, 必须条件判断避免配置失败
    if ((project.findProperty("enableIosTarget") ?: "true").toString() == "true") {
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
    // add("kspIosX64", libs.room.compiler)  // KP3: iosX64 不启用 (androidx 依赖缺 iosX64 变体)
    // fork 版 Compose 工具链接入: ohosArm64 target 条件启用, kspOhosArm64 仅在 enableOhosTarget=true 时添加
    if ((project.findProperty("enableOhosTarget") ?: "false").toString() == "true") {
        add("kspOhosArm64", libs.room.compiler)  // fork 版 Compose 工具链: 替换原 kspLinuxArm64 (鸿蒙 Room 编译)
    }
}
