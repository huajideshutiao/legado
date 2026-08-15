import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.time.LocalDate
import java.util.Properties

plugins {
    id("legado.jvm.application")
    // shared 模块 @Serializable 类 (Book/BookSource 等) 在桌面端展示需要序列化插件
    alias(libs.plugins.kotlin.serialization)
    // Compose Multiplatform 桌面端 (plan 附录J: desktop/jvm 走 CMP 桌面官方 JVM)
    id("legado.compose")
}

// ProGuard 瘦身 (可选, 默认开启): 离线/沙箱构建用 `-PdisableDesktopProguard=true` 关闭
// (规则文件 desktop/proguard-rules.pro 始终保留, 任务与依赖只在开启时声明)。
// 实现不引 Gradle 插件: 插件 marker 在部分镜像缺失且 Kotlin DSL 脚本插件对
// buildscript 类路径支持不佳, 改用 JavaExec 直跑 proguard.ProGuard 主类,
// 依赖经项目仓库 (central/aliyun) 解析, 见下方 proguardDesktop 任务。
if (providers.gradleProperty("disableDesktopProguard").orNull != "true") {
    // ============================================================
    // ProGuard 瘦身 (方案 C 落地: 危险区全 keep, 只裁死代码, 不混淆)
    // ============================================================
    // 规则见 proguard-rules.pro (移植 app 端 R8 配置): 反射/序列化/Room/JNI/
    // JS 桥/分派表全部 keep, -dontobfuscate (书源按类名反射加载), 只移除死代码。
    // 产物: build/libs/legado-desktop-shrunk.jar + build/proguard/{seeds,usage,mapping}.txt。
    // 独立任务, 暂不接线到 jpackage —— 先跑一次看 usage.txt 报表与体积, 确认无风险再接。
    val proguardConfig by configurations.creating
    dependencies {
        // exclude org.jetbrains:annotations: 纯编译期注解 (CLASS retention), 运行时用不到,
        // 也避免离线环境缺该传递产物导致任务无法执行
        add("proguardConfig", "com.guardsquare:proguard-gradle:7.9.1") {
            exclude(group = "org.jetbrains", module = "annotations")
        }
    }
    tasks.register<JavaExec>("proguardDesktop") {
        group = "build"
        description = "ProGuard 死代码裁剪: 桌面端 jar + 全部依赖 → 单 jar (危险区 keep)"
        dependsOn(tasks.jar)
        classpath = proguardConfig
        mainClass.set("proguard.ProGuard")

        // ProGuard 的 printseeds/usage/mapping 不会自建目录, 先建好避免 Unexpected error
        doFirst {
            layout.buildDirectory.get().file("proguard").asFile.mkdirs()
            layout.buildDirectory.get().file("libs").asFile.mkdirs()
        }

        val runtimeClasspath = project.configurations.getByName("runtimeClasspath")
        // bcprov 必须排除在 injars 外, 保持独立签名 jar 分发:
        // 1) 它带 Oracle JCE Code Signing CA 签名, 合并进 fat jar 后 ProGuard 重写类导致
        //    摘要失效, 类加载抛 SecurityException: Invalid signature file digest;
        // 2) 更关键: JCE provider 认证要求 provider 类来自该签名 jar 的 code source,
        //    fat jar 内无法通过认证 → Cipher.getInstance("AES/CBC/PKCS7Padding") 会失败。
        //    打包时 shrunk jar 与 bcprov.jar 并列进 classpath 即可。
        val bcprovJar = runtimeClasspath.files.firstOrNull { it.name.startsWith("bcprov-jdk18on") }
        val injarJars = runtimeClasspath.filter { it.name != bcprovJar?.name }

        args("-include", "proguard-rules.pro")
        // 输入: 桌面端 jar + 全部运行时依赖 (除 bcprov) (ProGuard classpath 支持 OS 路径分隔符)
        args("-injars", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
        args("-injars", injarJars.joinToString(File.pathSeparator) { it.absolutePath })
        args("-outjars", layout.buildDirectory.get().file("libs/legado-desktop-shrunk.jar").asFile.absolutePath)
        // JDK 运行时作为 library jars (ProGuard 7.2+ 支持 jmod; 桌面端跑在 Java 21 工具链上)
        File(desktopJavaHome.get(), "jmods").listFiles()
            ?.sortedBy { it.name }
            ?.forEach { args("-libraryjars", it.absolutePath) }
        // bcprov 也作为 library jar (代码引用它做 JCE 注册, 但不并入产物)
        if (bcprovJar != null) {
            args("-libraryjars", bcprovJar.absolutePath)
        }
        // 调参报表: seeds=keep 命中清单, usage=删除清单, mapping=映射
        args("-printseeds", layout.buildDirectory.get().file("proguard/seeds.txt").asFile.absolutePath)
        args("-printusage", layout.buildDirectory.get().file("proguard/usage.txt").asFile.absolutePath)
        args("-printmapping", layout.buildDirectory.get().file("proguard/mapping.txt").asFile.absolutePath)
    }
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
    if (isHarmonyMode) ohosVersion("composeMultiplatform-ohos") else ohosVersion("cmp")

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("org.jetbrains.compose") &&
            requested.version == activeComposeVersion
        ) {
            // CPF 基线 (如 1.9.2-0.5.0-25) 的 Desktop JVM 平台制品 = 主版本 (1.9.2)
            // 仅鸿蒙模式真正生效 (CPF 版本带后缀 → 重写为主版本); 非鸿蒙模式下
            // activeComposeVersion = 主版本 (如 1.11.1), useVersion 是同版本 no-op。
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
    // 引入 shared 模块 jvm target (传递 commonMain + jvmMain 全部 API)
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
    // shared/jvmMain 已 api(project(':modules:quickjs')), 桌面端通过 shared 传递依赖可见;
    // 显式 implementation 确保 :desktop:run 之前 :modules:quickjs:jvmJar (含 buildJvmNativeLib) 被触发
    implementation(project(":modules:quickjs"))
    // Coil3 图片栈 (封面/ReviewListScreen 直接用 rememberAsyncImagePainter/ImageRequest):
    // shared 对 coil3 是 implementation 不外泄, desktop 显式声明; coil-compose 传递 api 出
    // coil(SingletonImageLoader)/coil-core(ImageRequest/DiskCache)/coil-compose-core(painter)
    implementation(libs.coil3.compose)
    // 桌面端音频播放: open-ani/mediamp (mediamp-mpv 后端, 与视频同引擎, mpv=FFmpeg 全格式)。
    // 引擎实例在 DesktopAudioPlayer 惰性创建 (ServiceLoader 解析 mediamp-mpv);
    // mpv runtime 由下方 mediamp-mpv-runtime 提供 (与视频端共用同一套解包加载)。
    // 桌面端视频播放: open-ani/mediamp (mediamp-mpv 后端)。替代自研 libmpv 直通渲染 +
    // mpv.exe 外部进程方案 (已删, 见 git 历史)。
    // - 渲染: libmpv render API → 独立 producer GL/D3D11 上下文 → 共享纹理环 → Skia 零拷贝
    //   (Windows: D3D11→Skia D3D12 共享; Linux: GLX share group; macOS: Metal), 视频区是普通
    //   Compose 层, 控制层/弹层自由叠加, 无 airspace 问题, 也不再有 Skia GL 状态缓存污染
    // - mpv runtime: mediamp-mpv 的 POM 只把 runtime 工件列在 dependencyManagement (版本锁
    //   定), 并不传递引入, 必须显式 runtimeOnly 声明; 下方按构建平台固定单工件,
    //   loader 运行时按当前 OS/arch 解包加载, 无需用户安装 mpv
    // - 防盗链: UriMediaData(uri, headers) 原生透传 User-Agent/Referer/http-header-fields
    implementation(libs.mediamp.mpv)
    // mpv runtime 按构建平台固定: win/linux 恒 x64 (x86 系), mac 恒 arm64 (M 芯片),
    // 避免聚合工件把全部平台 natives 塞进每个安装包; 未知平台兜底聚合工件。
    // 注意: macos-latest 若未来换 x64 runner, 需同步改回 macos-x64
    val mpvRuntime = when {
        OperatingSystem.current().isWindows -> libs.mediamp.mpv.runtime.windows.x64
        OperatingSystem.current().isLinux -> libs.mediamp.mpv.runtime.linux.x64
        OperatingSystem.current().isMacOsX -> libs.mediamp.mpv.runtime.macos.arm64
        // runtime 别名同时是组前缀 (runtime-windows-x64 等), 生成物是 accessor 组对象;
        // asProvider() 取回聚合库 Provider, 与上面各分支同为 Provider<MinimalExternalModuleDependency>,
        // 消除 "implicitly cast to Any" 警告 (旧写法直接把组对象当依赖传, 兜底分支实际是坏的)。
        else -> libs.mediamp.mpv.runtime.asProvider()
    }
    runtimeOnly(mpvRuntime)
    // jna 保留: WindowsFileDialogs (jna-platform) / DesktopAppConfigAccessor / DesktopBattery /
    // DesktopWebViewEngines 直调 Win32; 视频侧 JNA 绑定已随自研渲染器删除。
    implementation(libs.jna)
    // jna-platform 提供 Win32 COM 基础设施 (Ole32/Guid/HRESULT), 供 WindowsFileDialogs 直调
    // IFileDialog 取现代文件对话框 (AWT FileDialog 在 Windows 上是 comdlg32 旧版样式)。
    implementation(libs.jna.platform)
    // 2026-08-15 教训: 不要重新启用 bcprov! 一旦 BC 类进入运行时 classpath, hutool
    // SecureUtil.createCipher 会经 GlobalBouncyCastleProvider 用 BC 的 RSA Cipher:
    // BC 的 RSA getBlockSize()=127 (SunJCE=0), 触发 AsymmetricCrypto.encrypt 的分段加密
    // (128 字节输入被切成 127+1 两段分别 RSA 再拼接), 产出错误的 encSecKey →
    // 网易 weapi 全部 200 空体, 网易云发现/目录/歌单无法加载 (07c2a5e5 引入, 根因排查见
    // shared AsymmetricCryptoAndroid.initCipher 注释)。如需 PKCS7Padding, 请恢复
    // SymmetricCryptoAndroid 的 normalizePkcs7Padding (PKCS7→PKCS5 字节级等价, 无需 BC),
    // 不要再加回 bcprov。
    // implementation(libs.bcprov)
    // WebP: 解码走 TwelveMonkeys imageio-webp (纯 Java, 活跃维护, 已替代归档的 gotson/webp-imageio);
    // 编码走 Skiko (Compose Desktop 自带) 的 EncodedImageFormat.WEBP, 见 DesktopImageOps.encodeWebpSkia
    implementation(libs.imageio.webp)
    // 本地书格式: PDF 渲染 (对照 app 端 PdfRenderer 语义)
    implementation(libs.pdfbox)
    // 压缩包: 7z/tar/gz/bz2/xz (xz 库是 7z LZMA2 默认压缩方法的必需依赖, 非只为 .xz)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    // rar4/rar5 (junrar 8.x 已支持 RAR5); slf4j-nop 消 junrar 传递依赖的无绑定警告
    implementation(libs.junrar)
    implementation(libs.slf4j.nop)
    // 内嵌浏览器引擎全部直调系统引擎 (Windows WebView2 / Linux webkit2gtk / macOS WKWebView),
    // 零随包 native。历史上 JavaFX WebView (OpenJFX 21 内嵌 2018 年 WebKit 606.1) 曾作为
    // 跨平台兜底, 因内核过老 (ES2017+ 缺失/无资源拦截/cookie 反射 hack) 达不到书源网页需求,
    // 已移除 —— 引擎不可用时直接回退系统浏览器 (见 help/webview/DesktopWebViewEngines)。
    // KP2-D: 桌面端 Room 事务支持
    // room-ktx 2.8.4 不发布 jvm 变体 (Android 专属), 桌面端改用 room-runtime 的 useWriterTransaction
    // shared.commonMain 已 api(libs.room.runtime), 桌面端通过传递依赖可见, 无需显式声明

    // 测试: WebView2 消息泵/环境/窗口创建闭环验证 (修复"startBrowser 首次调用打不开")
    testImplementation(libs.junit)
    // Compose UI 测试 (compose.desktop.uiTestJUnit4 已弃用转 error, 直接声明同版本坐标;
    // 版本跟 composeMultiplatform 走, 与插件展开值一致)
    testImplementation(
        "org.jetbrains.compose.ui:ui-test-junit4:${
            ohosVersion(if (isHarmonyMode) "composeMultiplatform" else "cmp")
        }"
    )
    // JBR 客户端 API (WindowDecorations/CustomTitleBar, Windows 原生标题栏自定义):
    // 官方制品 org.jetbrains.runtime:jbr-api (Apache 2.0, github.com/JetBrains/JetBrainsRuntimeApi),
    // 与 ab-download-manager/jewel 同款; JBR 侧实现在 JBR 21 运行时内置, 非 JBR 时 getWindowDecorations() 返回 null
    implementation(libs.jbr.api)
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

// ============================================================
// jlink 运行时精简补充
// ============================================================// Compose 插件 AbstractJLinkTask 已默认开启 --strip-debug / --no-header-files /
// --no-man-pages / --strip-native-commands (internal 属性默认 true), 无需重复设置。
// 唯一还能压的是 --compress=2 (zip): 插件 1.10.x 里 compressionLevel 是 internal 且
// DSL 未公开 (源码标注 "todo: public DSL"), 用反射设置; 插件升级字段变动时静默降级。
// 注: 必须用 tasks.matching (惰性) —— compose 的 createRuntimeImage 在
// compose.desktop.application{} 求值后才注册, tasks.named() 在此处会抛 Task not found。
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    runCatching {
        val taskClass = javaClass
        // Kotlin internal 属性 getter 带模块名后缀 ($compose), 用前缀匹配兼容
        val getter = taskClass.methods.firstOrNull { it.name.startsWith("getCompressionLevel") }
            ?: error("getCompressionLevel not found")

        @Suppress("UNCHECKED_CAST") // 反射取 internal 属性, 类型擦除后只能非受检转换
        val prop = getter.invoke(this) as Property<Any>
        val zip = Class.forName(
            "org.jetbrains.compose.desktop.application.internal.RuntimeCompressionLevel",
            true,
            taskClass.classLoader,
        ).enumConstants?.firstOrNull { it.toString() == "ZIP" }
            ?: error("RuntimeCompressionLevel.ZIP not found")
        // Property<*> 的 set 签名是 set(Nothing?) 无法传值, 按运行期擦除 cast 为 Property<Any>
        prop.set(zip)
        logger.lifecycle("[legado-desktop] jlink --compress=2 (zip) 已启用")
    }.onFailure {
        logger.warn("[legado-desktop] jlink --compress 设置失败(插件版本差异), 跳过: ${it.message}")
    }
}

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

// ===== legado_smtc native 桥 (Windows SMTC, 纯 C + MinGW) =====
// 背景: SMTC 集成从 JNA 手写 COM vtable 重构为 native C 桥 (官方 interop 路径 +
// 严格 QI 回调 + timeline 节流), 见 desktop/src/main/cpp/smtc/smtc_bridge.c。
// 构建/打包/加载链路照 quickjs buildJvmNativeLib 同模式 (cmake + MinGW 探测)。
val smtcNativeDir = layout.buildDirectory.dir("libs/smtc/native").get().asFile
val smtcNativeBuildDir = layout.buildDirectory.dir("intermediates/cmake-smtc").get().asFile
val smtcCppDir = file("src/main/cpp/smtc")

val buildSmtcNative by tasks.registering {
    group = "native"
    description = "Build legado_smtc native library (SMTC bridge) for desktop JVM"
    inputs.dir(smtcCppDir)
    outputs.file(
        File(
            smtcNativeDir,
            if (OperatingSystem.current().isWindows) "legado_smtc.dll" else "liblegado_smtc.so"
        )
    )
    doFirst {
        val cmakeCmd = findCmakeExecutable()
        if (cmakeCmd == null) {
            logger.warn("[legado-smtc] cmake not found, skipping native build. SMTC unavailable.")
            return@doFirst
        }
        smtcNativeDir.mkdirs()
        smtcNativeBuildDir.mkdirs()

        val isWindows = OperatingSystem.current().isWindows
        var useMinGW = false
        var mingwBinDir: String? = null
        if (isWindows) {
            val hasNmake = runCatching {
                val p = ProcessBuilder("nmake", "/?").start()
                p.waitFor()
                p.exitValue() == 0
            }.getOrDefault(false)
            if (!hasNmake) {
                mingwBinDir = findMingwBinDir()
                if (mingwBinDir != null) {
                    useMinGW = true
                    logger.lifecycle("[legado-smtc] Using MinGW Makefiles: $mingwBinDir")
                } else {
                    logger.warn("[legado-smtc] No nmake/MSVC or MinGW found; cmake may fail.")
                }
            }
        }

        val configureCmd = mutableListOf(cmakeCmd)
        if (useMinGW) {
            configureCmd += listOf("-G", "MinGW Makefiles")
        }
        configureCmd += listOf(
            "-S", smtcCppDir.absolutePath,
            "-B", smtcNativeBuildDir.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=" + smtcNativeDir.absolutePath,
            "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=" + smtcNativeDir.absolutePath,
        )
        logger.lifecycle("[legado-smtc] cmake configure: ${configureCmd.joinToString(" ")}")
        runCatching {
            val cfg = ProcessBuilder(configureCmd)
            if (useMinGW && mingwBinDir != null) {
                cfg.environment()["PATH"] =
                    mingwBinDir + File.pathSeparator + (cfg.environment()["PATH"] ?: "")
            }
            cfg.redirectErrorStream(true)
            val p = cfg.start()
            p.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
            p.waitFor()
            if (p.exitValue() != 0) return@runCatching
            val build = ProcessBuilder(
                listOf(
                    cmakeCmd,
                    "--build",
                    smtcNativeBuildDir.absolutePath,
                    "--config",
                    "Release"
                )
            )
            if (useMinGW && mingwBinDir != null) {
                build.environment()["PATH"] =
                    mingwBinDir + File.pathSeparator + (build.environment()["PATH"] ?: "")
            }
            build.redirectErrorStream(true)
            val b = build.start()
            b.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
            b.waitFor()
            if (b.exitValue() != 0) {
                logger.warn("[legado-smtc] cmake build failed (exit=${b.exitValue()}).")
            }
        }.onFailure {
            logger.warn("[legado-smtc] native build failed: ${it.message}")
        }
    }
}

fun findCmakeExecutable(): String? {
    project.findProperty("legado.cmake.path")?.let {
        if (File(it.toString()).exists()) return it.toString()
    }
    val cmakeOk = runCatching {
        val p = ProcessBuilder("cmake", "--version").start()
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)
    if (cmakeOk) return "cmake"
    runCatching {
        val props = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val sdkDir = props.getProperty("sdk.dir") ?: return null
        val cmakeBase = File(sdkDir, "cmake")
        if (cmakeBase.exists()) {
            for (dir in cmakeBase.listFiles()!!.sortedByDescending { it.name }) {
                val exe = File(dir, "bin/cmake.exe")
                if (exe.exists()) return exe.absolutePath
            }
        }
        null
    }
    return null
}

fun findMingwBinDir(): String? {
    project.findProperty("legado.mingw.path")?.let {
        if (File(it.toString(), "gcc.exe").exists()) return it.toString()
    }
    val gccOk = runCatching {
        val p = ProcessBuilder("gcc", "--version").start()
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)
    if (gccOk) {
        runCatching {
            val p = ProcessBuilder("where", "gcc").start()
            val out =
                p.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull() ?: ""
            if (out.isNotEmpty() && File(out).exists()) return File(out).parent
        }
    }
    runCatching {
        val wingetBase =
            File(System.getProperty("user.home"), "AppData/Local/Microsoft/WinGet/Packages")
        if (wingetBase.exists()) {
            for (pkg in wingetBase.listFiles()!!
                .filter { it.name.lowercase().contains("llvm-mingw") }
                .sortedByDescending { it.name }) {
                for (sub in pkg.listFiles()!!.filter { it.isDirectory }) {
                    val bin = File(sub, "bin")
                    if (File(bin, "gcc.exe").exists()) return bin.absolutePath
                }
            }
        }
        null
    }
    return null
}

val copySmtcNativeToResources by tasks.registering(Copy::class) {
    dependsOn(buildSmtcNative)
    from(smtcNativeDir)
    val osName = when {
        OperatingSystem.current().isWindows -> "windows"
        OperatingSystem.current().isMacOsX -> "macos"
        else -> "linux"
    }
    into(file("${composeResourcesDir.path}/$osName"))
    include("*.dll", "*.so", "*.dylib")
}

// CI 用 sed 把 packageVersion 注入为 "3.YY.MMDDHHMM" (如 3.26.08131506)。
// Windows MSI 只接受 MAJOR.MINOR.BUILD 且 BUILD ≤ 65535, 8 位时间戳直接配置期报错;
// deb/rpm 支持 4 段版本不受影响。此处仅给 MSI 单独映射为 "3.YY.YYDDD":
// 年内随日期递增、跨年 YY 进位, 保证 MSI 升级版本号单调不减 (YY≥66 时超 65535, 届时换算法)。
private fun msiSafeVersion(pkgVer: String): String {
    val m = Regex("""^(\d+)\.(\d+)\.(\d{4})\d{4}$""").find(pkgVer) ?: return pkgVer
    val major = m.groupValues[1]
    val yy = m.groupValues[2].toInt()
    val mmdd = m.groupValues[3]
    val dayOfYear = LocalDate
        .of(2000 + yy, mmdd.substring(0, 2).toInt(), mmdd.substring(2, 4).toInt())
        .dayOfYear
    return "$major.$yy.${yy * 1000 + dayOfYear}"
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
            // - jdk.management: HotSpotDiagnosticMXBean (关于页"创建堆转储"经 MBean 名字符串
            //   查找, jdeps 静态分析看不到)
            // 如运行时报 ClassNotFoundException / NoClassDefFoundError, 在此补对应模块
            modules(
                "jdk.localedata",
                "jdk.unsupported",
                "jdk.crypto.cryptoki",
                "jdk.crypto.ec",
                "jdk.zipfs",
                "jdk.management",
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
                // MSI 版本号上限 MAJOR.MINOR.BUILD 且 BUILD ≤ 65535, 而 CI sed 注入的
                // packageVersion 是 "3.YY.MMDDHHMM" (BUILD 段 8 位非法, 配置期直接报错);
                // 这里单独映射为合法且单调递增的 "3.YY.YYDDD" (deb/rpm 不受影响)
                msiPackageVersion = msiSafeVersion(
                    compose.desktop.application.nativeDistributions.packageVersion ?: "1.0.0"
                )
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
            // macOS dmg 专属配置
            macOS {
                // legado:// / yuedu:// deep link: 把 CFBundleURLTypes 注入 .app Info.plist
                // (对照 iosApp/project.yml 的 CFBundleURLTypes 与 app 端 intent-filter)。
                // CMP DSL 无 jpackage --mac-url-scheme 等价项, 用 infoPlist.extraKeysRawXml
                // 在 </dict> 前追加原始 XML; LaunchServices 安装/启动 .app 后即把两 scheme
                // 关联到本应用。运行时回调由 Main.kt setOpenURIHandler (Apple Event) 承接,
                // 无需运行时注册 (Windows/Linux 的运行时注册见 DesktopUrlProtocol)。
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>io.legado.deeplink</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>legado</string>
                                    <string>yuedu</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
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
        dependsOn(buildSmtcNative)
        // 开发期 run 注入 debug 标志: 让 shared printStackTraceOnDebug 对齐 Android 的
        // BuildConfig.DEBUG 语义 (仅开发打栈); 打包产物不带该属性 = 静默
        if (this is JavaExec) {
            systemProperty("legado.debug", "true")
            // AppLog 的 write/debugPrint 走 desktopDebug 门控 (registerDesktopAppLogHost),
            // 开发期 run 一并打开, 否则 shared/desktop 的 AppLog.put 全部静默 (排查时误判"无异常")
            systemProperty("legado.desktop.debug", "true")
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
        dependsOn(copySmtcNativeToResources)
    }
}

// ============================================================
// ProGuard/R8 优化说明 (方案 C: 已落地, 见上方 proguardDesktop 任务)
// ============================================================
// R8 是 Android 专属, 桌面端无 Android 编译插件; 桌面端瘦身走 ProGuard。
// Compose Multiplatform 大量依赖反射:
// - @Composable 函数通过 Compose 编译器插件生成 synthetic 方法, ProGuard 难以正确保留
// - kotlinx.serialization 用反射实例化数据类 (Book/BookSource 等)
// - Room KMP 生成的 DAO 实现类通过反射访问
// - quickjs JNI 桥通过反射查找 native 方法
// 因此规则文件 (proguard-rules.pro) 对这些危险区全部 keep + 关闭混淆 (-dontobfuscate,
// 书源按类名反射加载), 只做死代码删除。体积优化组合: ProGuard 裁死代码 (本方案) +
// jlink 精简 JRE (strip-debug/compress 等, 见 createRuntimeImage 配置) + 7z 压缩打包。
// 接线到 jpackage 前先跑 `.\gradlew :desktop:proguardDesktop` 看 usage.txt 报表。

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
