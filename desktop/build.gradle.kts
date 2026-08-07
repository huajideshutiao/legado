import org.gradle.api.tasks.JavaExec
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    id("legado.jvm.application")
    // shared 模块 @Serializable 类 (Book/BookSource 等) 在桌面端展示需要序列化插件
    alias(libs.plugins.kotlin.serialization)
    // Compose Multiplatform 桌面端 (plan 附录J: desktop/jvm 走 CMP 桌面官方 JVM)
    id("legado.compose")
}

// CPF 的 root metadata 只发布 Android/iOS/OHOS 变体，Desktop JVM 继续使用同基线的
// JetBrains 平台制品 (与 shared/build.gradle.kts 同款 resolutionStrategy 对齐);
// 版本从 catalog 读取 (显式索引避免点分歧义), 禁止硬编码
val isHarmonyMode = providers.gradleProperty("enableOhosTarget").getOrNull() == "true"
// catalog 经 rootProject 的 VersionCatalogsExtension 访问 (与 build-logic 同款模式)
private val ohosCatalog =
    rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

private fun ohosVersion(key: String): String =
    ohosCatalog.findVersion(key).get().requiredVersion

val activeComposeVersion =
    if (isHarmonyMode) ohosVersion("composeMultiplatform-ohos") else "1.7.1"

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("org.jetbrains.compose") &&
            requested.version == activeComposeVersion
        ) {
            // CPF 基线 (如 1.9.2-0.5.0-25) 的 Desktop JVM 平台制品 = 主版本 (1.9.2)
            useVersion(activeComposeVersion.substringBefore("-"))
            because("CPF does not publish Desktop JVM variants")
        }
    }
}

// KP6+: 安装类型由编译期参数控制 (用户裁决: 不靠 runtime/ 目录嗅探, 改 BuildConfig)
// gradle property: -Plegado.installType=portable|installed|dev (默认 dev 保护开发流程)
//   portable  = 数据存 exe 同级 dataDir (便携版, 拷贝即迁移)
//   installed = 数据存系统推荐应用数据目录 (MSI 安装版)
//   dev       = 数据存项目工作目录 (开发期 :desktop:run)
val installType = (project.findProperty("legado.installType") as String?)
    ?.takeIf { it in setOf("portable", "installed", "dev") }
    ?: "dev"

val installTypeDir = file("build/generated/installType/kotlin/io/legado/desktop/")
val generateInstallType by tasks.registering {
    outputs.dir(installTypeDir)
    doLast {
        installTypeDir.mkdirs()
        file("${installTypeDir.path}/InstallType.kt").writeText(
            """package io.legado.desktop

/**
 * 安装类型 (编译期由 gradle property `legado.installType` 决定)。
 *
 * 用户裁决: 不靠运行时嗅探 runtime/ 目录 (用户可能安装到非指定目录导致误判),
 * 改由打包时传入 installType 控制 dataDir 定位:
 * - PORTABLE: 数据存 exe 同级 dataDir
 * - INSTALLED: 数据存系统推荐应用数据目录
 * - DEV: 数据存项目工作目录 (开发期)
 */
object InstallType {
    const val TYPE: String = "$installType"
    val IS_PORTABLE: Boolean = TYPE == "portable"
    val IS_INSTALLED: Boolean = TYPE == "installed"
    val IS_DEV: Boolean = TYPE == "dev"
}
""".trimIndent()
        )
    }
}

sourceSets {
    main {
        kotlin.srcDir("build/generated/installType/kotlin")
    }
}

tasks.named("compileKotlin").configure { dependsOn(generateInstallType) }

dependencies {
    // 引入 shared 模块 jvm target (传递 commonMain + jvmAndAndroidMain 全部 API)
    implementation(project(":shared"))
    // shared 模块 commonMain 已声明 kotlinx-serialization-json api, 但 jvm target 传递依赖可能不完整, 显式补
    implementation(libs.kotlinx.coroutines.core)
    // JVM 的 Dispatchers.Main 由本 artifact 经 ServiceLoader 注册到 EDT, 缺失则 withContext(Main) 抛异常
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    // Compose Multiplatform 桌面端 UI (桌面 swing 集成; shared 已用 compose.material, desktop 不引 md3)
    implementation(compose.desktop.currentOs)
    // Compose Multiplatform 资源运行时: 加载 shared/sharedIconResources/drawable/ 下的共享 vector XML 图标
    // (ResourceProvider.jvm.kt 用 painterResource(Res.drawable.xxx) 替代部分 Material Icons, 与 app 端视觉对齐)
    // desktop 单 JVM 模块 (kotlin.jvm 插件) 的 compose extension 不支持 .resources 属性
    // (仅 KMP multiplatform 插件下可用), 故不在此声明; 改由 shared sharedUiMain 用 api 暴露,
    // desktop 通过传递依赖访问 Res 类 / DrawableResource / painterResource 扩展函数
    // KP1.1: 桌面端 JS 引擎走 modules:quickjs 自研 JNI 桥 (KMP 化后 jvm target 暴露 commonMain API)
    // shared/jvmAndAndroidMain 已 api(project(':modules:quickjs')), 桌面端通过 shared 传递依赖可见;
    // 显式 implementation 确保 :desktop:run 之前 :modules:quickjs:jvmJar (含 buildJvmNativeLib) 被触发
    implementation(project(":modules:quickjs"))
    // Coil3 图片栈 (DesktopBookCover/ReviewListScreen 直接用 rememberAsyncImagePainter/ImageRequest):
    // shared 对 coil3 是 implementation 不外泄, desktop 显式声明; coil-compose 传递 api 出
    // coil(SingletonImageLoader)/coil-core(ImageRequest/DiskCache)/coil-compose-core(painter)
    implementation(libs.coil3.compose)
    // 桌面端 MP3 音频播放: jlayer 解码 MP3 → PCM, javax.sound.sampled.SourceDataLine 输出
    // javax.sound.sampled 是 JDK 内置, 无需额外依赖; jlayer 在 toml 单独声明
    implementation(libs.jlayer)
    // 桌面端视频播放: open-ani/mediamp (mediamp-mpv 后端)。替代自研 libmpv 直通渲染 +
    // mpv.exe 外部进程方案 (已删, 见 git 历史)。
    // - 渲染: libmpv render API → 独立 producer GL/D3D11 上下文 → 共享纹理环 → Skia 零拷贝
    //   (Windows: D3D11→Skia D3D12 共享; Linux: GLX share group; macOS: Metal), 视频区是普通
    //   Compose 层, 控制层/弹层自由叠加, 无 airspace 问题, 也不再有 Skia GL 状态缓存污染
    // - mpv runtime: mediamp-mpv 的 POM 只把 runtime 工件列在 dependencyManagement (版本锁
    //   定), 并不传递引入, 必须显式 runtimeOnly 声明 mediamp-mpv-runtime (聚合工件, 单一 JVM
    //   variant 无 OS/arch 属性, 带全部平台 natives, loader 运行时按当前 OS/arch 解包加载,
    //   无需用户安装 mpv); 想省体积可换 per-platform 工件 mediamp-mpv-runtime-windows-x64 等
    // - 防盗链: UriMediaData(uri, headers) 原生透传 User-Agent/Referer/http-header-fields
    implementation(libs.mediamp.mpv)
    runtimeOnly(libs.mediamp.mpv.runtime)
    // jna 保留: WindowsFileDialogs (jna-platform) / DesktopAppConfigAccessor / DesktopBattery /
    // DesktopWebViewEngines 直调 Win32; 视频侧 JNA 绑定已随自研渲染器删除。
    implementation("net.java.dev.jna:jna:5.17.0")
    // jna-platform 提供 Win32 COM 基础设施 (Ole32/Guid/HRESULT), 供 WindowsFileDialogs 直调
    // IFileDialog 取现代文件对话框 (AWT FileDialog 在 Windows 上是 comdlg32 旧版样式)。
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    // webp 编码: ImageIO SPI 插件 (jar 内置 win/linux/mac native writer; TwelveMonkeys 只读不写)
    implementation("com.github.gotson:webp-imageio:0.2.2")
    // 本地书格式: PDF 渲染 (对照 app 端 PdfRenderer 语义)
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    // 压缩包: 7z/tar/gz/bz2/xz (xz 库是 7z LZMA2 默认压缩方法的必需依赖, 非只为 .xz)
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    // rar4/rar5 (junrar 8.x 已支持 RAR5); slf4j-nop 消 junrar 传递依赖的无绑定警告
    implementation("com.github.junrar:junrar:8.0.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    // 内嵌浏览器引擎全部直调系统引擎 (Windows WebView2 / Linux webkit2gtk / macOS WKWebView),
    // 零随包 native。历史上 JavaFX WebView (OpenJFX 21 内嵌 2018 年 WebKit 606.1) 曾作为
    // 跨平台兜底, 因内核过老 (ES2017+ 缺失/无资源拦截/cookie 反射 hack) 达不到书源网页需求,
    // 已移除 —— 引擎不可用时直接回退系统浏览器 (见 help/webview/DesktopWebViewEngines)。
    // KP2-D: 桌面端 Room 事务支持
    // room-ktx 2.8.4 不发布 jvm 变体 (Android 专属), 桌面端改用 room-runtime 的 useWriterTransaction
    // shared.commonMain 已 api(libs.room.runtime), 桌面端通过传递依赖可见, 无需显式声明

    // 测试: WebView2 消息泵/环境/窗口创建闭环验证 (修复"startBrowser 首次调用打不开")
    testImplementation(libs.junit)
    // Compose UI 测试 (官方: compose.desktop.uiTestJUnit4, runComposeUiTest)
    testImplementation(compose.desktop.uiTestJUnit4)
}

// Compose Desktop 统一配置入口 (mainClass + nativeDistributions)
// 注: 不使用 Gradle application 插件, 避免与 compose.desktop.application{} 注册的 run task 冲突

// 桌面端编译目标 Java 21 (JvmApplicationConventionPlugin jvmToolchain(21)), 但 Compose 插件的
// run/jpackage 任务默认用 Gradle daemon 的 JVM (System.getProperty("java.home")), 不跟工具链走:
// daemon 是 17 时 :desktop:run 启动即抛 UnsupportedClassVersionError (class file v65)。
// 这里把 javaHome 显式指到 Java 21 工具链 launcher (与 compileKotlin 同一 JDK, 已在
// ~/.gradle/jdks 自动供给), 保证 run/打包统一用 21。
val desktopJavaHome = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}.map { it.metadata.installationPath.asFile.absolutePath }

// KP6: 把 legado_quickjs native 库纳入 jpackage 产物 (便携版 / MSI 安装版)
// 背景: jpackage 默认只把 classpath jar 打进 app 目录, 不会纳入 -Djava.library.path 指向的
// 外部 native 库; 打包后 System.load 找不到 legado_quickjs.dll, JS 引擎初始化失败。
// 方案: 用 appResourcesRootDir 声明资源根目录, copyQuickjsNativeToResources task 把
// modules/quickjs 构建产物 (legado_quickjs.dll/.so/.dylib) 复制进去, jpackage 会把该目录
// 内容复制到 app/{packageName}/ 下; Main.kt 通过 compose.application.resources.dir
// 系统属性定位并设置 legado.quickjs.lib, quickjs 模块 Platform.kt 属性1逻辑 System.load 加载。
val quickjsNativeDir = file("${rootProject.projectDir}/modules/quickjs/build/libs/jvm/native")
val composeResourcesDir = file("build/compose-resources")

val copyQuickjsNativeToResources by tasks.registering(Copy::class) {
    // 先触发 native 库构建 (cmake 编译 legado_quickjs.dll), 再复制到 appResourcesRootDir
    dependsOn(project(":modules:quickjs").tasks.named("buildJvmNativeLib"))
    from(quickjsNativeDir)
    // Compose Desktop 只纳入 appResourcesRootDir 下的子目录 (common/<OS>/[<OS>-<ARCH>]),
    // 直接放根目录会被 prepareAppResources 判为 NO-SOURCE 不复制
    // 官方文档: https://github.com/JetBrains/compose-multiplatform/blob/master/tutorials/Native_distributions_and_local_execution/README.md#packaging-resources
    val osName = when {
        OperatingSystem.current().isWindows -> "windows"
        OperatingSystem.current().isMacOsX -> "macos"
        OperatingSystem.current().isLinux -> "linux"
        else -> throw GradleException("Unsupported OS for native distribution")
    }
    into(file("${composeResourcesDir.path}/$osName"))
    // 只复制 native 库文件 (.dll/.so/.dylib), 避免复制其他构建产物
    include("*.dll", "*.so", "*.dylib")
}

compose.desktop {
    application {
        mainClass = "io.legado.desktop.MainKt"
        // 修复: 默认随 Gradle daemon JVM 走 (可能 17), 显式用 Java 21 工具链, 见上方 desktopJavaHome
        javaHome = desktopJavaHome.get()
        // KP6 修复: 删除 -Djava.library.path jvmArg。
        // 根因: Windows 绝对路径 C:\...\jvm\native 中的 \n 被 jpackage cfg 文件解析器
        // 当成换行符, 把 "native" 拆成下一行 "ative" 当主类名, 启动报
        // ClassNotFoundException: ative。且该路径是开发机绝对路径, 打包后在用户机器
        // 上根本不存在, 无意义。
        // native 库加载改走: appResourcesRootDir 纳入产物 + Main.kt 设
        // legado.quickjs.lib 系统属性 + Platform.kt 候选1 System.load 加载。
        // 开发期 :desktop:run 不需要 java.library.path, Platform.kt 候选3 会从当前
        // 工作目录向上递归找 modules/quickjs/build/libs/jvm/native/{dll|so|dylib}。
        jvmArgs += listOf(
            "-Xmx768m",                          // 提升堆上限避免大书架 OOM (原 512m 偏小)
            "-Xms128m",                          // 启动期小初始堆, 按需增长减少启动期内存申请开销
            "-XX:+UseG1GC",                      // JDK 17 默认即 G1, 显式声明更稳定
            "-XX:MaxGCPauseMillis=200",          // G1 目标停顿
            "-XX:TieredStopAtLevel=1",           // 仅 C1 编译, 启动期 JIT 时间降 30~50%
            "-XX:CompileThreshold=10000",        // 提高 JIT 阈值减少冷路径编译
            "-Xshare:auto",                      // 启用 CDS (classlist 已 dump, 失败自动回退)
            "-XX:+UseStringDeduplication",       // G1 字符串去重
            "-Dfile.encoding=UTF-8",             // Windows 默认 GBK, 显式声明 UTF-8 避免资源乱码
            // 反射访问 AWT 原生句柄 (任务栏按钮/DWM 卡片等经 WComponentPeer.getHWnd 拿 HWND):
            // Component.peer 字段在 java.awt (需 opens), getHWnd 在 sun.awt.windows (需 opens),
            // 缺任一都会 InaccessibleObjectException 被吞 → HWND 静默拿不到
            "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED",
        )

        // Compose Desktop 原生分发配置 (msi/deb/rpm) — 配合 .github/workflows 多端编译
        // CI 产物路径: desktop/build/compose/binaries/{msi,deb,rpm}/<package>-<version>.<ext>
        // 注意: packageVersion 必须 x.y.z[.w] 格式; CI 通过 sed 注入实际版本号 (统一与 Android 版本一致)
        nativeDistributions {
            // 声明目标格式, 由 CI 在对应 runner 上分别打包 (Windows→msi, Linux→deb/rpm, macOS→dmg, AppImage→便携版镜像)
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg, TargetFormat.AppImage)
            // KP6: 资源根目录, 内容会被 jpackage 复制到 app/{packageName}/ 下
            // copyQuickjsNativeToResources task 把 legado_quickjs.dll/.so/.dylib 复制进来,
            // Main.kt 通过 compose.application.resources.dir 定位并设置 legado.quickjs.lib 加载
            appResourcesRootDir = composeResourcesDir
            // 方案 A: jlink 精简 JRE - 补充 jpackage 自动检测 (jdeps) 可能遗漏的 JDK 模块
            // 这些模块通过反射或服务加载, jdeps 静态分析检测不到, 运行时需要:
            // - jdk.localedata: 国际化 (中文日期/数字格式)
            // - jdk.unsupported: sun.misc.Unsafe (反射/并发库)
            // - jdk.crypto.cryptoki / jdk.crypto.ec: HTTPS 加密 (PKCS11/ECC, OkHttp SSL 用)
            // - jdk.zipfs: ZIP 文件系统 (cbz/epub 解析)
            // 如运行时报 ClassNotFoundException / NoClassDefFoundError, 在此补对应模块
            modules(
                "jdk.localedata",
                "jdk.unsupported",
                "jdk.crypto.cryptoki",
                "jdk.crypto.ec",
                "jdk.zipfs",
            )
            // 默认版本号; CI 会用 sed 改为实际版本 (如 3.25.0722)
            packageVersion = "1.0.0"
            // 应用元数据 (从 shared 模块继承项目名, 这里给桌面端独立 packageName)
            packageName = "legado"
            description = "Legado desktop reader (Compose Multiplatform)"
            vendor = "gedoor"
            // Windows MSI 专属配置
            windows {
                menu = true
                dirChooser = true
                // 创建桌面快捷方式 + 开始菜单分组 (Legado)
                shortcut = true
                menuGroup = "Legado"
                // perMachine 默认 false → 安装到 %LOCALAPPDATA%, 不需要管理员权限
                // (当前 Compose 版本无显式 perMachine setter, 默认行为即为 per-user)
                // 升级时由 MSI 自身 UUID 识别, packageVersion 必须递增
                upgradeUuid = "7F5C4E2A-3B6D-4F8A-9C1E-1A2B3C4D5E6F"
            }
            // Linux deb/rpm 专属配置
            linux {
                // deb 包名必须小写, 走 packageName
                packageName = "legado"
                menuGroup = "Office"
                // deb 包 Maintainer 字段 (维护者邮箱)
                debMaintainer = "gedoor <gedoor@users.noreply.github.com>"
                // .desktop 文件 Categories 字段 (freedesktop.org 应用类别)
                appCategory = "Office"
                // RPM License 字段 (deb 无对应字段, 走 copyright 文件)
                rpmLicenseType = "GPL-3.0"
                // RPM Release 字段 (deb 包版本由 packageVersion 控制)
                appRelease = "1"
                // 不强制 root 安装 (deb 默认 /usr/bin, /usr/share)
            }
        }
    }
}

// KP1.1: :desktop:run 之前确保 :modules:quickjs 的 native 库已构建 (.dll/.so/.dylib)
// buildJvmNativeLib 探测系统 cmake + 编译器, 失败时只警告不失败 (Kotlin 编译不受影响, 仅 JS eval 运行时报错)
// 注: compose.desktop.application{} 创建的 run task 需在 afterEvaluate 中追加依赖
afterEvaluate {
    tasks.named("run").configure {
        dependsOn(project(":modules:quickjs").tasks.named("buildJvmNativeLib"))
        // 开发期 run 注入 debug 标志: 让 shared printStackTraceOnDebug 对齐 Android 的
        // BuildConfig.DEBUG 语义 (仅开发打栈); 打包产物不带该属性 = 静默
        if (this is JavaExec) {
            systemProperty("legado.debug", "true")
        }
    }
    // KP6: 打包 task 必须依赖 copyQuickjsNativeToResources, 确保 native 库先复制到
    // appResourcesRootDir, jpackage 才会纳入 app/{packageName}/ 目录, 打包后可加载
    // (copyQuickjsNativeToResources 自身 dependsOn buildJvmNativeLib, 保证 native 库已构建)
    // prepareAppResources 是 Compose Desktop 扫描 appResourcesRootDir 子目录(common/<OS>/[<OS>-<ARCH>])
    // 的 task, 必须在 copy 之后执行, 否则判 NO-SOURCE (根因: 直接放根目录不被识别)
    tasks.matching {
        it.name in listOf(
            "prepareAppResources",
            "createRuntimeImage", "createDistributable",
            "packageMsi", "packageExe", "packageDeb", "packageRpm",
            "packageDmg", "packageAppImage",
        )
    }.configureEach {
        dependsOn(copyQuickjsNativeToResources)
    }
}

// ============================================================
// ProGuard/R8 优化说明 (方案 C: 未实施, 风险大于收益)
// ============================================================
// Compose Multiplatform 桌面端不走 R8 (R8 是 Android 专属, 桌面端无 Android 编译插件)。
// ProGuard 可用于 JVM 应用, 但 Compose Multiplatform 大量依赖反射:
// - @Composable 函数通过 Compose 编译器插件生成 synthetic 方法, ProGuard 难以正确保留
// - kotlinx.serialization 用反射实例化数据类 (Book/BookSource 等)
// - Room KMP 生成的 DAO 实现类通过反射访问
// - quickjs JNI 桥通过反射查找 native 方法
// 配置 ProGuard 需要逐一保留上述反射类, 漏配置会导致运行时崩溃, 风险大于减小体积的收益。
// 故本方案不实施 ProGuard, 体积优化依赖 jlink 精简 JRE (方案 A) + 7z 压缩 (方案 B)。

// ============================================================
// Windows 便携版 zip 打包 task
// ============================================================
// 背景: jpackage 默认产物是 MSI/Exe 安装包 (需安装), 无法满足"拷贝即用"便携场景。
// 本 task 在 createDistributable (jpackage app-image) 后, 把 legado.exe + runtime/ + app/ + data/
// + portable.txt 打成 zip, 用户解压即用; 便携模式由运行时检测 portable.txt 标记启用
// (DesktopAppPaths), 不依赖编译期 -Plegado.installType (CI 与 MSI 共享同一 app image)。

// data/ 目录占位 (空目录无法直接打 zip, 用 README 占位)
val portableDataPlaceholderDir = file("build/generated/portable-data-placeholder/")
val generatePortableDataPlaceholder by tasks.registering {
    outputs.dir(portableDataPlaceholderDir)
    doLast {
        portableDataPlaceholderDir.mkdirs()
        file("${portableDataPlaceholderDir.path}/README.txt").writeText(
            "便携版数据目录\n应用运行时数据 (数据库/书源/缓存) 存放于此\n"
        )
    }
}

// 便携标记文件: 运行时 DesktopAppPaths 检测到程序目录存在 portable.txt 即启用便携模式
// (数据存 exe 同级 data/); MSI/DEB/DMG 安装版无此文件, 走系统数据目录
val portableMarkerDir = file("build/generated/portable-marker/")
val generatePortableMarker by tasks.registering {
    outputs.dir(portableMarkerDir)
    doLast {
        portableMarkerDir.mkdirs()
        file("${portableMarkerDir.path}/portable.txt").writeText(
            "便携版标记文件: 应用检测到本文件后数据存放于同目录 data/ 下; 删除本文件则改用系统数据目录\n"
        )
    }
}

val packagePortableZip by tasks.registering(Zip::class) {
    description = "Windows 便携版 zip 打包: jpackage app image + data/ 占位 → zip"
    group = "compose desktop distribution"

    // 仅 Windows 平台执行 (便携版特化)
    onlyIf { OperatingSystem.current().isWindows }

    // 依赖 jpackage app image 生成 task (createDistributable 是 packageMsi/Exe 的上游, 产物含 legado.exe + runtime/ + app/)
    dependsOn("createDistributable", generatePortableDataPlaceholder, generatePortableMarker)

    // app image 输出路径 (compose desktop createDistributable 产物, 路径: build/compose/binaries/main/app/{packageName}/)
    val appImageDir = file("build/compose/binaries/main/app/legado")

    // 路径不存在时给清晰错误 (避免 Zip task 静默跳过)
    doFirst {
        if (!appImageDir.exists()) {
            throw GradleException(
                "App image directory not found: $appImageDir\n" +
                    "Ensure createDistributable task ran successfully on Windows."
            )
        }
    }

    // 排除 app image 内运行时数据, 由 placeholder task 注入干净 data/
    from(appImageDir) {
        into("legado-portable-windows-x64")
        exclude("data/**")
    }
    from(portableDataPlaceholderDir) { into("legado-portable-windows-x64/data") }
    // 便携标记落 zip 根 (exe 同级), 运行时据此启用便携模式 (无需编译期 -Plegado.installType)
    from(portableMarkerDir) { into("legado-portable-windows-x64") }

    // 从 nativeDistributions 读 packageVersion (CI 用 sed 注入实际版本号), 拼到 zip 文件名
    val version = compose.desktop.application.nativeDistributions.packageVersion ?: "1.0.0"
    archiveFileName.set("legado-portable-windows-x64-${version}.zip")
    // 独立输出目录, 避免与 jpackage 产物 (build/compose/binaries/) 及 Gradle 默认 distributions 目录混淆
    destinationDirectory.set(layout.buildDirectory.dir("outputs/portable"))
}
