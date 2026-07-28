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

// Res 类生成开关: Auto 模式靠"依赖图里出现 compose.components.resources 访问器坐标"来判定,
// 而本模块为绕开 fork 未发布 jvm 变体的问题改用显式坐标声明 (见 commonMain 依赖处注释),
// Auto 探测不到会 SKIP 掉 generateResourceAccessors*, 导致 legado.shared.generated.resources.Res 无法解析。
// always 直接放行生成, 与 Auto 命中时行为一致。
compose.resources {
    generateResClass = always
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
    val isIdea = System.getProperty("idea.active") == "true"
    val officialVersion = "1.8.2"
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
            // @JsApi 注解 (BaseSource/CacheManager 等 JS 面标注), 全 target 微型模块
            // api: 注解出现在 shared 公开类型上, app 端 KSP 需解析到该注解才能生成分派表
            api(project(":modules:js-api"))
            // PageAnim.kt @IntDef 注解需要; androidx.annotation 1.10.0 已 KMP 发布
            implementation(libs.androidx.annotation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            // okio 已 KMP 发布; commonMain 用 okio.IOException (KmpCallback.onFailure 公开签名引用, 故 api)
            api(libs.okio)
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
            // 例外: components-resources (资源加载, 非 UI 代码) 放 commonMain, 供 Res 类生成与运行时加载
            // ohos target 默认关闭 (-PenableOhosTarget=false), 启用时需引入 composeMain 中间源集隔离
            // 官方文档: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources-setup.html
            //
            // 用显式坐标而非 compose.components.resources 访问器: 访问器解析出 fork 插件版本 1.8.2.99-alipay,
            // 而该 fork 只发布 root pom/module + -android/-ohosarm64, 无 -jvm/-desktop 变体。
            // KGP 的 IdeBinaryDependencyResolver 为中间源集 (jvmAndAndroidMain/sharedUiMain/nonOhosUiMain)
            // 建 detachedConfiguration 解析依赖, 而 detached 不属于 project.configurations 容器,
            // settings.gradle.kts 的 configurations.all { eachDependency/force } 对其完全不生效
            // → IDE sync 报 "Could not resolve components-resources:1.8.2.99-alipay"。
            // 声明期钉版是唯一能覆盖 detachedConfiguration 的位置 (从源头就不产生 -alipay 坐标)。
            api("org.jetbrains.compose.components:components-resources:1.8.2")
        }
        // sharedUiMain: Compose UI 共享源集, android/jvm/ios/ohos 均继承
        // 不含 reorderable/coil3/multiplatformMarkdown/ui-tooling-preview/material-icons-extended 依赖
        // (五库未发布 ohosArm64 变体) 使用 these 库的代码通过 expect/actual 抽离, actual 实现在 nonOhosUiMain
        // ohosMain 提供 stub actual (Compose 原生/降级实现)
        val sharedUiMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // runtime/foundation/material/ui 保持使用 compose.* 访问器 (fork 版): ohosArm64 需要 fork 的
                // -ohosarm64 变体。但在 IDE 环境下, DetachedConfiguration 会无视 settings.gradle.kts
                // 的 force 规则尝试解析 -alipay 版的 jvm 变体(不存在), 导致 IDE 报红。
                // 解决方案: IDE 环境下硬钉官方版本, 编译环境下(如鸿蒙打包)保持访问器。
                if (isIdea) {
                    implementation("org.jetbrains.compose.runtime:runtime:$officialVersion")
                    implementation("org.jetbrains.compose.foundation:foundation:$officialVersion")
                    implementation("org.jetbrains.compose.material:material:$officialVersion")
                    implementation("org.jetbrains.compose.ui:ui:$officialVersion")
                } else {
                    implementation(compose.runtime)
                    implementation(compose.foundation)
                    implementation(compose.material)
                    implementation(compose.ui)
                }

                // material3/ui-tooling 用显式官方坐标而非 compose.material3/compose.uiTooling 访问器:
                // 二者在 settings.gradle.kts 已被全局 force 到官方版 (fork 未发布对应制品), 声明期直接钉版
                // 语义等价, 且能覆盖 KGP IdeBinaryDependencyResolver 的 detachedConfiguration
                // (绕过 settings 规则, 详见 commonMain 的 components-resources 注释)
                implementation("org.jetbrains.compose.material3:material3:1.8.2")
                // @Preview 注解依赖 (ui-tooling 含多平台渲染支持)
                implementation("org.jetbrains.compose.ui:ui-tooling:1.8.2")
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
        // skikoUiMain: desktop (jvm) 与鸿蒙共用的 skia 直调源集。
        // 两端 Compose 均由 skiko 渲染, org.jetbrains.skia.* 包名/签名一致, 故 Codec 逐帧
        // 动图解码等实现只写一份; 不放 sharedUiMain 是因 androidMain 也继承它, 而 Android 端
        // Compose 映射 androidx.compose (无 skiko), 会找不到 org.jetbrains.skia 符号。
        // iosMain 同样坐在 skiko 上但不继承: iOS 走 Coil3 管线自带动图与缓存 (见 ios actual 注释)。
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
                // Android 新 TextContextMenuProvider 的长期修复：1.11 在 SelectionContainer 复制后
                // 主动释放选区，避免平台 ActionMode 关闭前闪出仅“全选”的过渡菜单。
                implementation(libs.compose.foundation.android)
            }
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
            // jvmMain 继承 nonOhosUiMain (间接继承 sharedUiMain), 获取 reorderable/coil3/markdown actual
            dependsOn(nonOhosUiMain)
            // skiko 直调 (Codec 逐帧动图解码): 与鸿蒙共用 skikoUiMain 一份实现
            // skiko 由 compose desktop 变体传递引入 (ui-graphics-desktop → org.jetbrains.skiko:skiko), 无需显式声明
            dependsOn(skikoUiMain)
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
                // Coil3 Ktor3 网络后端: 发布 iosArm64/iosSimulatorArm64 klib (Maven Central 已核),
                // pom 依赖 ktor-client-core 3.1.0 与本源集 Ktor 版本一致; 桥接 IosHttpProvider 的 Ktor HttpClient
                // (desktop/android 走 jvmAndAndroidMain 的 coil-network-okhttp 后端, 故本依赖 iosMain 专属不放 nonOhosUiMain)
                implementation(libs.coil3.network.ktor3)
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
            // skiko 直调 (Codec 逐帧动图解码): 与 desktop 共用 skikoUiMain 一份实现
            // (本源集下方已显式声明 libs.skiko 依赖)
            dependsOn(skikoUiMain)
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
