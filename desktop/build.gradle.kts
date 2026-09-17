import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("legado.jvm.application")
    // shared 模块 @Serializable 类 (Book/BookSource 等) 在桌面端展示需要序列化插件
    alias(libs.plugins.kotlin.serialization)
    // Compose Multiplatform 桌面端 (plan 附录J: desktop/jvm 走 CMP 桌面官方 JVM)
    id("legado.compose")
}

// QuickJS native 目录架构契约（生产者/desktop/headless/runtime 必须一致）：
// amd64|x86_64|x64 -> x86_64；arm64|aarch64 -> aarch64；其余仅做路径安全化。
fun normalizeJvmNativeArch(rawArch: String): String = when (val arch = rawArch.lowercase()) {
    "amd64", "x86_64", "x64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> arch.replace(Regex("[^a-z0-9_.-]"), "_")
}

// ProGuard 瘦身已改用 Compose Desktop 官方集成 (见 compose.desktop.application.buildTypes.
// release.proguard {}): 官方 release buildType 自动创建 proguardReleaseJars 并接线到
// packageRelease*/createReleaseDistributable, joinOutputJars=false 逐 jar 输出规避 service 合并坑。
// 规则文件 desktop/proguard-rules.pro 经 configurationFiles 引用 (含 shared/quickjs consumer-rules)。
// 注: 官方默认 ProGuard 7.7.0 不在本地缓存, DSL 已显式 version=7.9.1 对齐可用缓存。
// 历史: 旧自研 JavaExec proguardDesktop 任务 (单 outjar 合并全部依赖) 已删除 —— 实测 ProGuard
// 对多 jar 同名 META-INF/services 只保留第一个 jar 的内容 (2026-08-18 最小实验证实 MainDispatcherFactory
// 被覆盖为 TestMainDispatcherFactory), 官方逐 jar 输出无此问题。

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
        kotlin.srcDir("build/generated/mediaRuntime/kotlin")
        // 直接挂载 app 端 drawable-nodpi 为桌面资源目录 (闪屏书本图标等),
        // 与 app 端共用同一份图片文件, 避免复制相同资源
        resources.srcDir("../app/src/main/res/drawable-nodpi")
    }
}

tasks.named("compileKotlin").configure { dependsOn(generateInstallType) }

// ============================================================
// 媒体播放组件 (mpv/FFmpeg native) 按需下载参数生成
// ============================================================
// 背景: 这套 native 实测 jar 21.0MiB (37 个 dll, 解压 52.7MiB), 是安装包最大单项。用户裁决不随包
// 发布, 改成首次播视频/本地音频时从镜像下载 (见 desktop/media/DesktopMediaRuntime)。
// 版本单一来源 = libs.versions.toml 的 mediamp; 各平台工件的 SHA-1 按版本预置在此 —— 升 mediamp
// 版本必须同时补新版本的五个校验值, 否则配置期直接报错 (比运行期下载失败早发现)。
private val mediaRuntimeSha1ByRelease: Map<String, Map<String, String>> = mapOf(
    // 值来源: Maven Central 上各平台 <artifact>-<version>.jar.sha1 (2026-09-15 实测与阿里镜像一致)
    "0.3.0" to mapOf(
        "windows-x64" to "77ba37f9ef80537dafbcdf3009802e839464943c",
        "windows-arm64" to "684ebeac4a8a85d97e965ae7928e84f2198e0726",
        "linux-x64" to "8e221ff2bb7b58957dbfd3b257e705539f078612",
        "macos-x64" to "78ce63a55e433a1dc041d56d72f90829270c7380",
        "macos-arm64" to "7ceb34bd7269baaf64dc1f304a9ef1a4ee5add2b",
    ),
)

val mediaRuntimeVersion = libs.versions.mediamp.get()
val mediaRuntimeSha1 = mediaRuntimeSha1ByRelease[mediaRuntimeVersion]
    ?: error(
        "libs.versions.toml mediamp=$mediaRuntimeVersion 在 desktop/build.gradle.kts 的 " +
            "mediaRuntimeSha1ByRelease 里没有校验值: 补上该版本五个平台的 jar SHA-1 再打包 " +
            "(来源: https://repo1.maven.org/maven2/org/openani/mediamp/<artifact>/<version>/...jar.sha1)"
    )

val mediaRuntimeDir = file("build/generated/mediaRuntime/kotlin/io/legado/desktop/media/")
val generateMediaRuntimeConfig by tasks.registering {
    outputs.dir(mediaRuntimeDir)
    doLast {
        mediaRuntimeDir.mkdirs()
        val entries = mediaRuntimeSha1.entries.sortedBy { it.key }
            .joinToString(",\n        ") { "\"${it.key}\" to \"${it.value}\"" }
        file("${mediaRuntimeDir.path}/MediaRuntimeConfig.kt").writeText(
            """package io.legado.desktop.media

/**
 * 媒体播放组件按需下载参数 (由 :desktop:generateMediaRuntimeConfig 生成, 勿手改)。
 *
 * version 来自 libs.versions.toml `mediamp`; sha1ByPlatform 来自 Maven Central 官方 .sha1。
 */
object MediaRuntimeConfig {
    const val VERSION: String = "$mediaRuntimeVersion"

    val sha1ByPlatform: Map<String, String> = mapOf(
        $entries
    )
}
""".trimIndent() + "\n"
        )
    }
}

tasks.named("compileKotlin").configure { dependsOn(generateMediaRuntimeConfig) }

// 媒体运行时 (mpv/ffmpeg native jar) 专用 configuration: **不**从 runtimeClasspath 继承,
// 故 jpackage 产物与便携包里都不会出现它; 仅手动挂到 :desktop:run 的 classpath 上供开发期用。
val mediaRuntimeOnly: Configuration by configurations.creating

dependencies {
    // 引入 shared 模块 jvm target (传递 commonMain + jvmMain 全部 API)
    implementation(project(":shared"))
    // 无 UI 核心 (从本模块机械抽取, 见 desktop-core/build.gradle.kts 头注释):
    // Main.kt 的阶段1/阶段3 provider 注册核心子集与运行时环境初始化改调 DesktopCore
    implementation(project(":desktop-core"))
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
    // mediamp 0.3.0 的 POM 硬声明 material-icons-extended(-desktop) 传递依赖 (库自带的播放 UI 图标,
    // 本项目播放界面自绘, 不用它的图标; 实测 mediamp-api/mpv 各 jar 字节码零引用 icons 类, 排除安全)。
    // 该依赖 37MB 且数千图标类, 必须排除, 否则直接进 jpackage 产物 (2026-08-18 实测 shrunk jar 里
    // usage.txt 有 11398 条 material.icons 删除记录即其证据)。
    implementation(libs.mediamp.mpv) {
        exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended")
        exclude(
            group = "org.jetbrains.compose.material",
            module = "material-icons-extended-desktop"
        )
    }
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
    // 媒体运行时 (单工件实测 21.0MiB, 37 个 native) **不再进发布 classpath**: 用户裁决改首次播
    // 视频/本地音频时按需下载 (见 desktop/media/DesktopMediaRuntime)。它同时服务视频与音频
    // (同一套 mpv natives), 所以文案与门禁都是"媒体播放组件"而不是"视频组件"。
    // 开发期 :desktop:run 仍挂上它 (mediaRuntimeOnly + 下方 run 任务 classpath), 本地跑代码不必每次先下一遍。
    mediaRuntimeOnly(mpvRuntime)
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
            ohosVersion(if (isHarmonyMode) "composeMultiplatform-ohos" else "cmp")
        }"
    )
}

// Compose Desktop 统一配置入口 (mainClass + nativeDistributions)
// 注: 不使用 Gradle application 插件, 避免与 compose.desktop.application{} 注册的 run task 冲突

// ============================================================
// release 运行时 classpath 剔除测试库 (2026-09 实测缺陷)
// ============================================================
// 链路 (硬证据: ./gradlew :desktop:dependencyInsight --dependency junit --configuration runtimeClasspath):
//   runtimeClasspath → org.jetbrains.compose.desktop:desktop-jvm-windows-x64 → desktop-jvm
//     → org.jetbrains.compose.ui:ui-tooling-preview(-desktop)          ← “IDE 预览”用
//     → org.jetbrains.compose.ui:ui-test(-desktop)
//     → junit:4.13.2 (+ hamcrest / com.google.truth / guava / kotlinx-coroutines-test 一串传递依赖)
// 即 ui-tooling-preview 在 desktop 变体里把整套测试库挂在 runtime 方向上, 3.26.09121949 便携版
// app/ 目录实测含 junit-4.13.2、hamcrest-core、truth-1.0.1、ui-test-desktop、
// ui-test-junit4-desktop、kotlinx-coroutines-test-jvm。危害除体积与启动期类扫描外, 还有
// ServiceLoader 污染: coroutines-test 声明的 TestMainDispatcherFactory 与 coroutines-swing 的
// Swing 工厂同屏竞争 Dispatchers.Main (2026-08-18 已实测过同类覆盖导致 Dispatchers.Main 崩溃),
// 再叠加本次修的“ProGuard 删了 services 实现类但留着服务文件”, 组合起来就是随机启动故障。
// 只裁 runtimeClasspath: testRuntimeClasspath 不继承它 (两者是兄弟, 均 extendsFrom
// implementation/runtimeOnly), 所以 :desktop:test 仍能用 testImplementation 的 junit/ui-test-junit4。
// 排除根只选 ui-test*: junit/truth/guava 全部从它往下传, 扒掉根即整串消失。
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-desktop")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4-desktop")
}

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

    // CDS 前置条件: jlink 必须保留 bin/java.exe。
    // 插件默认 --strip-native-commands=true, 产物 runtime/bin 下根本没有 java.exe
    // (3.26.09121949 便携版实测: runtime/bin 只剩 *.dll, runtime/lib/server 为空),
    // 于是旧版 dumpCdsArchive 的 `if (!jreJava.exists()) warn + return` 静默跳过,
    // -Xshare:auto 全程空转 —— 注释里宣称的“启动期类加载时间降 20~40%”从未落地。
    // 代价: 产物 runtime 多 3 个 launcher —— 实测 java.exe 50296 + javaw.exe 50296 +
    // keytool.exe 24696 = 125,288B (0.12MB, 不是此前注释写的"量级 MB", 那数字夸大约 10 倍)。
    // 刻意不用上方 compressionLevel 的 runCatching 静默降级: 拿不到这个属性 = CDS 又会静默失效,
    // 必须当场构建失败。
    run {
        val getter = javaClass.methods.firstOrNull { it.name.startsWith("getStripNativeCommands") }
            ?: error(
                "AbstractJLinkTask.stripNativeCommands 不存在 (插件版本差异): CDS 归档将无法生成, " +
                    "请同步更新本配置, 不得静默跳过"
            )
        @Suppress("UNCHECKED_CAST") // 同 compressionLevel: 运行期擦除后 cast 为 Property<Any>
        val stripProp = getter.invoke(this) as Property<Any>
        stripProp.set(false)
        logger.lifecycle(
            "[legado-desktop] jlink --strip-native-commands=false 已启用 (为 CDS 归档保留 bin/java)"
        )
    }
}

// KP6: 把 legado_quickjs native 库纳入 jpackage 产物 (便携版 / MSI 安装版)
// 背景: jpackage 默认只把 classpath jar 打进 app 目录, 不会纳入 -Djava.library.path 指向的
// 外部 native 库; 打包后 System.load 找不到 legado_quickjs.dll, JS 引擎初始化失败。
// 方案: 用 appResourcesRootDir 声明资源根目录, copyQuickjsNativeToResources task 把
// modules/quickjs 构建产物 (legado_quickjs.dll/.so/.dylib) 复制进去, jpackage 会把该目录
// 内容复制到 app/{packageName}/ 下; Main.kt 通过 compose.application.resources.dir
// 系统属性定位并设置 legado.quickjs.lib, quickjs 模块 Platform.kt 属性1逻辑 System.load 加载。
val quickjsPlatformId = buildString {
    val osName = when {
        OperatingSystem.current().isWindows -> "windows"
        OperatingSystem.current().isMacOsX -> "macos"
        OperatingSystem.current().isLinux -> "linux"
        else -> System.getProperty("os.name").lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
    }
    val arch = normalizeJvmNativeArch(System.getProperty("os.arch"))
    append(osName).append('-').append(arch)
}
val quickjsNativeDir =
    file("${rootProject.projectDir}/modules/quickjs/build/libs/jvm/native/$quickjsPlatformId")
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

/**
 * native C 桥的公用 cmake 构建 (smtc / wndchrome 共用)。
 * 工具链: 有 nmake 走 MSVC 默认生成器, 否则退 MinGW Makefiles;
 * 全程失败只 warn 不 fail —— native 缺失只影响对应功能, 不该阻断 Kotlin 编译。
 */
fun runCmakeNativeBuild(
    tag: String,
    cppDir: File,
    outDir: File,
    buildDir: File,
    logger: org.gradle.api.logging.Logger,
) {
    val cmakeCmd = findCmakeExecutable()
    if (cmakeCmd == null) {
        logger.warn("[$tag] cmake not found, skipping native build.")
        return
    }
    // CMake 缓存绑定源码目录, 项目迁移后残留的旧缓存会让 configure 直接报错退出;
    // 与 modules/quickjs buildJvmNativeLib 同款检测, 源目录变了就清缓存重建
    val cmakeCache = File(buildDir, "CMakeCache.txt")
    if (cmakeCache.exists()) {
        val homePrefix = "CMAKE_HOME_DIRECTORY:INTERNAL="
        val cachedHome = cmakeCache.readLines()
            .find { it.startsWith(homePrefix) }
            ?.substring(homePrefix.length)
        if (cachedHome != null) {
            val cachedPath = File(cachedHome).canonicalPath
            val currentPath = cppDir.canonicalPath
            val sameSource = if (OperatingSystem.current().isWindows) {
                cachedPath.equals(currentPath, ignoreCase = true)
            } else {
                cachedPath == currentPath
            }
            if (!sameSource) {
                logger.lifecycle("[$tag] CMake source changed; resetting stale cache.")
                buildDir.deleteRecursively()
            }
        }
    }
    outDir.mkdirs()
    buildDir.mkdirs()

    var useMinGW = false
    var mingwBinDir: String? = null
    if (OperatingSystem.current().isWindows) {
        val hasNmake = runCatching {
            val p = ProcessBuilder("nmake", "/?").start()
            p.waitFor()
            p.exitValue() == 0
        }.getOrDefault(false)
        if (!hasNmake) {
            mingwBinDir = findMingwBinDir()
            if (mingwBinDir != null) {
                useMinGW = true
                logger.lifecycle("[$tag] Using MinGW Makefiles: $mingwBinDir")
            } else {
                logger.warn("[$tag] No nmake/MSVC or MinGW found; cmake may fail.")
            }
        }
    }

    // MinGW 时把工具链目录前置到 PATH (cmake 需要在 PATH 上找到 gcc/make)
    fun ProcessBuilder.withToolchainPath(): ProcessBuilder = apply {
        if (useMinGW && mingwBinDir != null) {
            environment()["PATH"] =
                mingwBinDir + File.pathSeparator + (environment()["PATH"] ?: "")
        }
        redirectErrorStream(true)
    }

    val configureCmd = mutableListOf(cmakeCmd)
    if (useMinGW) {
        configureCmd += listOf("-G", "MinGW Makefiles")
    }
    configureCmd += listOf(
        "-S", cppDir.absolutePath,
        "-B", buildDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=" + outDir.absolutePath,
        "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=" + outDir.absolutePath,
    )
    logger.lifecycle("[$tag] cmake configure: ${configureCmd.joinToString(" ")}")
    runCatching {
        val cfg = ProcessBuilder(configureCmd).withToolchainPath().start()
        cfg.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
        cfg.waitFor()
        if (cfg.exitValue() != 0) return@runCatching
        val build = ProcessBuilder(
            listOf(cmakeCmd, "--build", buildDir.absolutePath, "--config", "Release")
        ).withToolchainPath().start()
        build.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
        build.waitFor()
        if (build.exitValue() != 0) {
            logger.warn("[$tag] cmake build failed (exit=${build.exitValue()}).")
            // 失败时清掉产物: 否则 task 仍被记为成功, 下次 UP-TO-DATE 会拿旧 dll 骗人
            // (踩过: 应用运行时 dll 被锁 → 链接失败 → 下次构建跳过 → 用的还是旧库)
            outDir.listFiles()?.forEach { it.delete() }
        }
    }.onFailure {
        logger.warn("[$tag] native build failed: ${it.message}")
    }
}

val buildSmtcNative by tasks.registering {
    group = "native"
    description = "Build legado_smtc native library (SMTC bridge) for desktop JVM"
    // SMTC 桥是纯 Win32 代码 (smtc_bridge.c 直引 windows.h), 非 Windows 平台无法编译,
    // onlyIf 跳过避免 macOS/Linux 打包时白跑一次必失败的构建
    onlyIf { OperatingSystem.current().isWindows }
    inputs.dir(smtcCppDir)
    outputs.file(File(smtcNativeDir, "legado_smtc.dll"))
    doFirst {
        runCmakeNativeBuild("legado-smtc", smtcCppDir, smtcNativeDir, smtcNativeBuildDir, logger)
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
    onlyIf { OperatingSystem.current().isWindows }
    from(smtcNativeDir)
    into(file("${composeResourcesDir.path}/windows"))
    include("*.dll")
}

// ===== legado_wndchrome native 桥 (Windows 窗口控制条, 纯 C) =====
// 去 JBR CustomTitleBar 依赖: 双层 WndProc 子类化 (JFrame + skiko Canvas) + WM_NCCALCSIZE 把客户区
// 顶到窗口顶端 + 一个鼠标穿透的 layered 子窗口画整条控制条 (含自绘三键)。
// 契约见 src/main/cpp/wndchrome/wndchrome.h, 调研见 build/research/win32-titlebar/SYNTHESIS.md。
// Windows 专属 (纯 Win32 API), 其他平台整条 task 跳过。
val wndchromeNativeDir = layout.buildDirectory.dir("libs/wndchrome/native").get().asFile
val wndchromeNativeBuildDir =
    layout.buildDirectory.dir("intermediates/cmake-wndchrome").get().asFile
val wndchromeCppDir = file("src/main/cpp/wndchrome")

val buildWndChromeNative by tasks.registering {
    group = "native"
    description = "Build legado_wndchrome native library (window chrome bridge) for Windows"
    onlyIf { OperatingSystem.current().isWindows }
    inputs.dir(wndchromeCppDir)
    outputs.file(File(wndchromeNativeDir, "legado_wndchrome.dll"))
    doFirst {
        runCmakeNativeBuild(
            "legado-wndchrome",
            wndchromeCppDir,
            wndchromeNativeDir,
            wndchromeNativeBuildDir,
            logger,
        )
    }
}

val copyWndChromeNativeToResources by tasks.registering(Copy::class) {
    dependsOn(buildWndChromeNative)
    onlyIf { OperatingSystem.current().isWindows }
    from(wndchromeNativeDir)
    into(file("${composeResourcesDir.path}/windows"))
    include("*.dll")
}

// CI 传 -PappVersion 注入 packageVersion, 格式随工作流不同: test.yml 是 "3.YY.MMDDHHMM",
// release.yml 是 "3.YY.MMDDHH"。MSI 只接受三段 MAJOR.MINOR.BUILD 且 BUILD ≤ 65535,
// 两者的 BUILD 段都超限; 该校验在配置期跑且遍历全部 targetFormats, 非法值会让整仓库任何
// gradle 命令连带 deb/rpm/dmg 一起挂掉。故给 MSI 映射为 "3.YY.<年内第几小时>"
// (3.26.5651 = 26 年第 236 天 11 时): 上限 8783, 跨小时递增, 跨年归零靠 YY 进位
// 保住 MSI 升级要求的单调递增。
private fun msiSafeVersion(pkgVer: String): String {
    // 第三段 MMDD[HH[MM]]: 4/6/8 位都收, 缺小时按 0 点算
    val m = Regex("""^(\d+)\.(\d{2})\.(\d{2})(\d{2})(\d{2})?(?:\d{2})?$""").find(pkgVer) ?: return pkgVer
    val (major, yy, mm, dd, hh) = m.destructured
    val dayOfYear = LocalDate.of(2000 + yy.toInt(), mm.toInt(), dd.toInt()).dayOfYear
    return "$major.$yy.${(dayOfYear - 1) * 24 + (hh.toIntOrNull() ?: 0)}"
}

// ============================================================
// 依赖 jar consumer 规则合并
// ============================================================
// 背景 (读 compose-gradle-plugin-1.11.1-sources 的 AbstractProguardTask.execute 实证):
// 插件生成的 root-config.pro 只 -include jars-config.pro +
// default-compose-desktop-rules.pro + DSL 的 configurationFiles, 全程不读依赖 jar 里的
// META-INF/proguard/*.pro (consumer rules 是 AGP/R8 机制, 独立 ProGuard 无此功能;
// 插件源码里自己还留着 "todo: also consider pulling coroutines rules from coroutines artifact"),
// 也不会为 META-INF/services 声明的实现类生成 keep。
// 3.26.09121949 正式版产物实测出三处硬损伤 (均已用 unzip/javap + 探针在产物上复现):
// 1) androidx sqlite-bundled 自带的
//    -keepclasseswithmembers class androidx.sqlite.driver.bundled.** { native <methods>; }
//    未生效 → nativeThreadSafeMode 等 5 个 native 方法被删 → jar 内 sqliteJni.dll 的
//    JNI_OnLoad RegisterNatives 抛 NoSuchMethodError → 数据库整体不可用
//    (同 proguard-rules.pro 的 JNI 小节, 两处一并修);
// 2) coil-network-okhttp 的 OkHttpNetworkFetcherServiceLoaderTarget 被删而 jar 内
//    META-INF/services/coil3.util.FetcherServiceLoaderTarget 保留 → Coil 每次取图抛
//    ServiceConfigurationError 被 EngineInterceptor 吞成 ErrorResult → 封面全空,
//    且异常不入 lazy 缓存 → 每张未命中缓存的图都要重扫一遍 classpath → 启动卡顿;
// 3) kxml2 的 org.kxml2.io.KXmlParser / KXmlSerializer 被删 → XmlPullParserFactory 回落
//    默认实现 → EPUB 导出链路 NPE。
// 做法: 打包期遍历 :desktop 的 runtimeClasspath, 提取上述两类元数据合成一份 .pro,
// 经 configurationFiles.from(task) 追加给 ProGuard
// (ConfigurableFileCollection 接受 TaskProvider 时自带隐式任务依赖, 另加下方显式兜底接线)。
val dependencyConsumerRulesFile =
    layout.buildDirectory.file("generated/desktop-proguard/dependency-consumer-rules.pro")

val mergeDependencyProguardRules by tasks.registering {
    group = "compose desktop distribution"
    description =
        "提取依赖 jar 自带 consumer 规则与 META-INF/services 实现类, 合成 ProGuard 规则文件"
    val runtimeClasspath = configurations.named("runtimeClasspath")
    inputs.files(runtimeClasspath)
    outputs.file(dependencyConsumerRulesFile)
    doLast {
        // 只取 ProGuard 通用约定路径 META-INF/proguard/*.pro: 各库另有的
        // META-INF/com.android.tools/{proguard,r8}/*.pro 是给 AGP/R8 消费的, 可能含 R8 专属语法,
        // 而实际内容不超出 META-INF/proguard/ 版本 (sqlite/room/coroutines/serialization 均三处同存)
        val consumerRuleDir = "META-INF/proguard/"
        val servicesDir = "META-INF/services/"
        val ruleBlocks = StringBuilder()
        val serviceClasses = sortedSetOf<String>()
        val serviceOrigin = mutableMapOf<String, String>()
        // 排序保证输出确定性 (否则 jar 遍历顺序变化会频繁弄脏下游 ProGuard 任务缓存)
        for (jar in runtimeClasspath.get().files.sortedBy { it.name }) {
            if (!jar.isFile || !jar.name.endsWith(".jar", ignoreCase = true)) continue
            ZipFile(jar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    when {
                        entry.isDirectory -> Unit
                        entry.name.startsWith(consumerRuleDir) && entry.name.endsWith(".pro") -> {
                            ruleBlocks.appendLine("# ---- ${jar.name} : ${entry.name} ----")
                            zip.getInputStream(entry).reader().use { ruleBlocks.append(it.readText()) }
                            ruleBlocks.appendLine()
                        }
                        entry.name.startsWith(servicesDir) &&
                            entry.name.length > servicesDir.length -> {
                            val text = zip.getInputStream(entry).bufferedReader().readText()
                            for (rawLine in text.lines()) {
                                // services 文件写法两种都存在: 一行一类名 (空白分隔) 与一行多类名
                                // 逗号分隔 —— kxml2 的
                                // META-INF/services/org.xmlpull.v1.XmlPullParserFactory 实为
                                // "org.kxml2.io.KXmlParser,org.kxml2.io.KXmlSerializer" 单行逗号分隔。
                                // 只按空白拆会把整行当成一个非法类名跳过 → kxml2 照样被删 (JAXP
                                // XmlPullParserFactory 自己就是按逗号解析的), 故必须一并拆逗号。
                                for (impl in rawLine.substringBefore('#').trim().split(Regex("[\\s,]+"))) {
                                    if (impl.isEmpty()) continue
                                    val looksLikeClassName = impl.contains('.') &&
                                        impl.all { c ->
                                            c.isLetterOrDigit() || c == '.' || c == '_' || c == '$'
                                        }
                                    if (!looksLikeClassName) continue
                                    if (serviceClasses.add(impl)) {
                                        serviceOrigin[impl] = "${jar.name} : ${entry.name}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val out = dependencyConsumerRulesFile.get().asFile
        out.parentFile.mkdirs()
        out.bufferedWriter().use { w ->
            w.appendLine("# 自动生成, 勿手改: 由 :desktop:mergeDependencyProguardRules 生成")
            w.appendLine("# 来源: :desktop runtimeClasspath 上各依赖 jar 的 META-INF 元数据")
            w.appendLine()
            w.appendLine(
                "# ===== A. 依赖 jar 自带 consumer 规则 (官方 ProGuard 集成不会自动读取) ====="
            )
            w.append(ruleBlocks)
            w.appendLine()
            w.appendLine(
                "# ===== B. META-INF/services 声明的实现类 (运行期按类名字符串反射实例化) ====="
            )
            for (impl in serviceClasses) {
                w.appendLine("# ${serviceOrigin[impl]}")
                w.appendLine("-keep class $impl { *; }")
            }
        }
        logger.lifecycle(
            "[legado-desktop] 依赖 consumer 规则已合成: 服务实现类 ${serviceClasses.size} 个 → $out"
        )
    }
}

// 显式兜底接线: ProGuard 任务由 compose 插件在 compose.desktop.application{} 求值后才注册,
// 用惰性 matching 补上依赖 (configurationFiles.from(task) 已携隐式依赖, 此处双保险)。
tasks.matching { it.name.startsWith("proguard") && it.name.endsWith("Jars") }.configureEach {
    dependsOn(mergeDependencyProguardRules)
}

// ============================================================
// 打包期剔除 jar 内非构建平台的 native
// ============================================================
// 根因 (实测于 3.26.09150109 镜像): 三方 jar 把全平台 native 装同一个 artifact —
//  - sqlite-bundled-jvm 2.7.0: natives/{windows_x64,linux_x64,linux_arm64,osx_arm64} 四份
//    sqliteJni, 解压 7.17MiB, 非本平台三份在 jar 内仍占 2.66MiB;
//  - jna 5.19.1: com/sun/jna/ 下 27 份 jnidispatch (aix/sunos/freebsd/loongarch64/s390x…),
//    解压 5.04MiB, 本机只认 win32-x86-64 一份。
// 这些条目在 jar 内已 deflate, jpackage 外层再压不动 (实测整树 gzip-6 与 MSI 比值接近),
// 所以每出一个平台的包就把其它平台的字节照抄一遍 —— Windows 安装包里约 4MiB 是死字节。
// 做法: 在官方 ProGuard 输出目录上就地重写 jar, 只留构建平台自己的 native 目录。ProGuard
// 已在该目录上产出、下游 createReleaseDistributable / packageRelease* 全部从该目录取件, 所以
// 一个钩子同时覆盖 MSI / deb / rpm / dmg / 便携 zip 五条链; CI 矩阵下构建平台即目标平台
// (windows/ubuntu/macos runner), 无需按 targetFormat 分支。
// 判定口径: 只有 token 命中下方已知平台目录名单、且不是本平台的那份才删; 名单外的目录名
// (如 com/sun/jna/platform/ 这种普通包) 一律保留, 避免误删 Java 类。
private val foreignNativeRoots = listOf("natives/", "com/sun/jna/")

private val knownNativeTokens = setOf(
    // sqlite-bundled: natives/<token>/
    "windows_x64", "windows_arm64", "linux_x64", "linux_arm64", "osx_x64", "osx_arm64",
    // jna: com/sun/jna/<token>/
    "win32-x86", "win32-x86-64", "win32-aarch64", "win32-amd64",
    "linux-x86", "linux-x86-64", "linux-arm", "linux-armel", "linux-aarch64",
    "linux-ppc", "linux-ppc64", "linux-ppc64le", "linux-mips64el", "linux-loongarch64",
    "linux-riscv64", "linux-s390x", "linux-x86_64",
    "darwin-x86", "darwin-x86-64", "darwin-aarch64", "darwin-universal",
    "sunos-x86", "sunos-x86-64", "sunos-sparc", "sunos-sparcv9",
    "freebsd-x86", "freebsd-x86-64", "freebsd-arm", "freebsd-ia64",
    "openbsd-x86", "openbsd-x86-64", "netbsd-x86", "netbsd-x86-64",
    "dragonflybsd-x86-64", "kfreebsd-i386", "kfreebsd-x86-64", "aix-ppc", "aix-ppc64",
)

private fun nativeTokensToKeep(os: OperatingSystem, arch: String): Set<String> {
    val arm = arch.contains("aarch64") || arch.contains("arm64")
    return when {
        os.isWindows -> if (arm) setOf("win32-aarch64", "windows_arm64")
        else setOf("win32-x86-64", "win32-amd64", "windows_x64")
        os.isMacOsX -> if (arm) setOf("darwin-aarch64", "osx_arm64")
        else setOf("darwin-x86-64", "darwin-x86", "osx_x64")
        else -> if (arm) setOf("linux-aarch64", "linux_arm64")
        else setOf("linux-x86-64", "linux-x86_64", "linux_x64")
    }
}

/** 未知目录名一律视为非平台目录 (不删); 已知平台 token 且不属于本平台 → 删。 */
private fun isForeignNativeEntry(
    name: String,
    keep: Set<String>,
): Boolean {
    val root = foreignNativeRoots.firstOrNull { name.startsWith(it) } ?: return false
    val token = name.substring(root.length).substringBefore('/')
    return token in knownNativeTokens && token !in keep
}

private fun stripForeignNativeEntries(
    jarDir: File,
    keep: Set<String>,
    report: (String) -> Unit,
) {
    if (!jarDir.isDirectory) {
        throw GradleException(
            "剔除跨平台 native 失败: ProGuard 输出目录不存在 $jarDir —— " +
                "插件输出路径已变, 不得静默跳过 (否则白背体积又回来)"
        )
    }
    var totalSaved = 0L
    var rewritten = 0
    val jars = jarDir.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") }
        ?.sortedBy { it.name } ?: emptyList()
    for (jar in jars) {
        val dropNames = HashSet<String>()
        ZipFile(jar).use { zf ->
            zf.entries().asSequence().forEach { e ->
                if (!e.isDirectory && isForeignNativeEntry(e.name, keep)) dropNames += e.name
            }
        }
        if (dropNames.isEmpty()) continue
        val tmp = File(jar.parentFile, jar.name + ".stripping")
        var after = 0L
        ZipFile(jar).use { zf ->
            ZipOutputStream(
                BufferedOutputStream(FileOutputStream(tmp))
            ).use { out ->
                out.setLevel(Deflater.BEST_COMPRESSION)
                zf.entries().asSequence().forEach { e ->
                    if (e.name in dropNames) return@forEach
                    // 原为 STORED 的条目保持 STORED, 不改变 jar 内存储形态以免影响加载路径
                    val ne = ZipEntry(e.name)
                    if (e.method == ZipEntry.STORED) ne.method = ZipEntry.STORED
                    out.putNextEntry(ne)
                    zf.getInputStream(e).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        after = tmp.length()
        val original = jar.length()
        if (after >= original) {
            // 重写后反而变大 (理论上只会变小: 删的都是已压缩条目) → 保留原件, 不静默接受负收益
            tmp.delete()
            report("[native-strip] ${jar.name} 重写后未变小, 保留原件")
            continue
        }
        if (!jar.delete() || !tmp.renameTo(jar)) {
            throw GradleException("[native-strip] 替换 jar 失败: ${jar.absolutePath}")
        }
        totalSaved += original - after
        rewritten++
        report(
            "[native-strip] ${jar.name}: $original B → $after B (剔 ${dropNames.size} 个非本平台 native 条目)"
        )
    }
    report(
        "[legado-desktop] 跨平台 native 剔除完成: 保留目录 $keep, 重写 $rewritten 个 jar, 省 ${totalSaved / 1024} KB"
    )
}

// ProGuard 输出就地在末尾 doLast 处理: Gradle 在全部 action (含 doLast) 跑完后才取输出指纹,
// 因此不会把 proguardReleaseJars 变成永久 dirty。
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    val proguardOutDir = layout.buildDirectory.dir("compose/tmp/main-release/proguard")
    val keepTokens = nativeTokensToKeep(
        OperatingSystem.current(),
        System.getProperty("os.arch").orEmpty(),
    )
    doLast {
        stripForeignNativeEntries(proguardOutDir.get().asFile, keepTokens) { logger.lifecycle(it) }
    }
}

compose.desktop {
    application {
        mainClass = "io.legado.desktop.MainKt"
        // 官方 ProGuard 集成 (Compose Desktop 1.10+ 自带, 替代下方自研 proguardDesktop 任务):
        // - release buildType 默认启用 (default 保持不启用, 开发期 :desktop:run 不受影响)
        // - joinOutputJars 默认 false: 每个输入 jar 单独 shrunk 输出, 各 jar 的 META-INF/services
        //   各自保留 —— 规避 ProGuard 单 outjar 合并时同名 service 文件只留第一个 jar 内容的坑
        //   (2026-08-18 最小实验证实: MainDispatcherFactory 被覆盖成 TestMainDispatcherFactory,
        //   导致 SwingDispatcherFactory 丢失 → Dispatchers.Main 崩溃)。
        // - obfuscate 默认 false: 书源按类名反射加载, 不混淆 (与自研规则 -dontobfuscate 一致)
        // - 规则文件: 官方 default-compose-desktop-rules.pro 自动附带 + 下方显式追加项目规则
        //   (含 shared/quickjs consumer-rules, 与自研 proguard-rules.pro 同源)
        // - version 显式 7.9.1: 官方默认 7.7.0 不在本地缓存, 离线构建无法解析 (2026-08-18 实测)
        buildTypes {
            release {
                proguard {
                    version.set("7.9.1")
                    // 优化必须关闭: 2026-08-18 实测 optimize=true 复现 ProGuard 优化器崩溃
                    // (Stack.generalize: Stacks have different current sizes [0] and [1],
                    // 即旧 error[1011] StackGeneralizationException) —— Kotlin/Compose 高阶函数/
                    // 协程字节码触发 PartialEvaluator bug, ProGuard 7.9.1 未修复。
                    // 安卓端能用 R8 -optimizationpasses 5 是因为 R8 是 Google 重写的优化器,
                    // 对 Kotlin 字节码安全; ProGuard 无解, 只能 -dontoptimize 保稳。
                    // 体积差距来自工具链优化能力差异 + class vs dex 格式差异, 非规则差异。
                    optimize.set(false)
                    configurationFiles.from(file("proguard-rules.pro"))
                    // 依赖 jar 自带 consumer 规则 + META-INF/services 实现类 keep
                    // (官方集成不读这两类元数据, 缺口与实测损伤见 mergeDependencyProguardRules 注释)
                    configurationFiles.from(mergeDependencyProguardRules)
                }
            }
        }
        // 修复: 默认随 Gradle daemon JVM 走 (可能 17), 显式用 Java 21 工具链, 见上方 desktopJavaHome
        javaHome = desktopJavaHome.get()
        // KP6 修复: 删除 -Djava.library.path jvmArg。
        // 根因: Windows 绝对路径 C:\...\jvm\native 中的 \n 被 jpackage cfg 文件解析器
        // 当成换行符, 把 "native" 拆成下一行 "ative" 当主类名, 启动报
        // ClassNotFoundException: ative。且该路径是开发机绝对路径, 打包后在用户机器
        // 上根本不存在, 无意义。
        // native 库加载改走: appResourcesRootDir 纳入产物 + Main.kt 设
        // legado.quickjs.lib 系统属性 + Platform.kt 候选1 System.load 加载。
        // 开发期 :desktop:run 通过 legado.quickjs.lib 指向按 os-arch 分层的 native 输出。
        jvmArgs += listOf(
            "-Xmx768m",                          // 提升堆上限避免大书架 OOM (原 512m 偏小)
            "-Xms384m",                          // 启动期堆底提到 384m: 实测 -Xms128m 下启动头 1.1s 内跳 4 次
                                                // young GC 且堆反复顶在 128M 上限扩张 (G1 扩容+迁移是白送成本)
            "-XX:+UseG1GC",                      // JDK 17 默认即 G1, 显式声明更稳定
            "-XX:MaxGCPauseMillis=200",          // G1 目标停顿
            // -XX:TieredStopAtLevel=1 / -XX:CompileThreshold 已删除 (2026-09 复盘):
            // TieredStopAtLevel=1 = 只用 C1 编译器, 确实省启动期 JIT 时间, 但代价是后续全部
            // 代码停在低优化级别 (无 C2 内联/逃逸消除)。Compose Desktop 是常驻交互型进程:
            // 组合/布局/绘制热点长期跑在 C1 上, 点击后那一帧的重组合+渲染比默认分层编译慢几倍,
            // 用户感知就是“点了没反应 / 操作卡顿”。启动优化应走 CDS (见 dumpCdsArchive),
            // 而不是牺牲整个生命周期的吞吐。
            "-Xshare:auto",                      // 启用 CDS: 正式版由 dumpCdsArchive 预生成归档, 生成失败会让构建直接失败
            // -XX:+UseStringDeduplication 已删除 (2026-09 启动实测): 字符串去重靠并发标记周期顺带做,
            // 官方适用场景是堆充裕的大服务; 本包 -Xmx768m 且启动期大量临时字符串 (Compose 资源/文案),
            // 为了省几十 MB 常驻去重的 CPU/标记开销反而拖慢启动。
            "-Dfile.encoding=UTF-8",             // Windows 默认 GBK, 显式声明 UTF-8 避免资源乱码
            // 反射访问 AWT 原生句柄 (任务栏按钮/DWM 卡片等经 WComponentPeer.getHWnd 拿 HWND):
            // Component.peer 字段在 java.awt (需 opens), getHWnd 在 sun.awt.windows (需 opens),
            // 缺任一都会 InaccessibleObjectException 被吞 → HWND 静默拿不到
            "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED",
        )

        // Compose Desktop 原生分发配置 (msi/deb/rpm) — 配合 .github/workflows 多端编译
        // CI 产物路径: desktop/build/compose/binaries/{msi,deb,rpm}/<package>-<version>.<ext>
        // 注意: packageVersion 必须 x.y.z[.w] 格式; CI 通过 -PappVersion 注入实际版本号 (统一与 Android 版本一致)
        nativeDistributions {
            // 声明目标格式, 由 CI 在对应 runner 上分别打包 (Windows→msi, Linux→deb/rpm, macOS→dmg)
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
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
            // CI 传 -PappVersion 注入实际版本 (与 Android 同源); 本地构建回落 1.0.0
            packageVersion = providers.gradleProperty("appVersion").orNull ?: "1.0.0"
            // 应用元数据 (从 shared 模块继承项目名, 这里给桌面端独立 packageName)
            packageName = "legado"
            description = "Legado desktop reader (Compose Multiplatform)"
            vendor = "gedoor"
            // 文件关联 (jpackage --file-associations): 双击书籍文件用 legado 打开, 参数经 argv
            // (macOS 经 Apple Event OpenFilesHandler) 送到 Main.kt 的 pendingAssociationFiles。
            // 扩展名只取 AppPattern.bookFileRegex 的四种正文格式 —— 关联在 Windows 上是抢默认
            // 打开方式, .json/.zip 不抢 (仍可从"打开方式"手动选, 分发链照样处理)
            //
            // 【硬约束: 第三个参数 (描述) 必须 ASCII-only】它会被写进安装包的 Windows 注册表
            // 字符串 (light.exe 链进 MSI 字符串表)。jpackage 不传 culture, 由构建机 locale 现场
            // 决定: GitHub windows runner 是 en-us → 数据库代码页 1252, 任何非 ASCII 字符 (中文
            // 在内) 都会让 light.exe 报 LGHT0311、退出码 311, MSI 直接打不出来; 本地中文 Windows
            // (936) 反而能过, 所以这类问题只在 CI 暴露 (2026-09-11 CI run 34599585893 实测,
            // 探针复现见 failures 记录)。三端统一用英文描述, 顺带避开"英文系统装出乱码"。
            fileAssociation("text/plain", "txt", "TXT Book")
            fileAssociation("application/epub+zip", "epub", "EPUB Book")
            fileAssociation("application/pdf", "pdf", "PDF Document")
            fileAssociation("application/vnd.comicbook+zip", "cbz", "CBZ Comic")
            // 视频文件关联 (让"打开方式"能把视频直接交给 legado 开播): 扩展名与 shared
            // AppPattern.videoFileRegex 逐一对应, 少一个就会出现"系统交给我们、自己不认"。
            // 交给 Main.kt 的文件关联链 → shared FileAssociationDispatch 在 JSON/书籍正则之前
            // 先判视频 (VideoDirect) → 直接 push 播放页直投态, 不建书不查书源。
            // MIME 用各扩展名的规范值: .ts 是 MPEG-TS 传输流 (video/mp2t),
            // .m3u8 是 HLS 清单 (application/vnd.apple.mpegurl) —— 不是猜的字符串,
            // Linux 的 .desktop MimeType 与 macOS UTI 换算都按它匹配。
            // 已知代价: .ts 同时是 TypeScript 源码扩展名, 装上后 Windows 会把它抢给 legado;
            // 与 videoFileRegex 保持一致优先于避开歧义 (要改就得两边同时改)。
            // 描述一律 ASCII-only, 同上方 MSI 代码页 1252 硬约束。
            fileAssociation("video/mp4", "mp4", "MP4 Video")
            fileAssociation("video/x-matroska", "mkv", "Matroska Video")
            fileAssociation("video/quicktime", "mov", "QuickTime Video")
            fileAssociation("video/webm", "webm", "WebM Video")
            fileAssociation("video/x-flv", "flv", "Flash Video")
            fileAssociation("video/x-msvideo", "avi", "AVI Video")
            fileAssociation("video/mp2t", "ts", "MPEG TS Video")
            fileAssociation("application/vnd.apple.mpegurl", "m3u8", "HLS Playlist")
            // 应用图标 (从 Android ic_launcher 高清图转换生成): Windows ICO, Linux PNG
            // Windows MSI 专属配置
            windows {
                iconFile = file("src/main/resources/icons/legado.ico")
                menu = true
                dirChooser = true
                // 创建桌面快捷方式 + 开始菜单分组 (Legado)
                shortcut = true
                menuGroup = "Legado"
                // perMachine 默认 false → 安装到 %LOCALAPPDATA%, 不需要管理员权限
                // (当前 Compose 版本无显式 perMachine setter, 默认行为即为 per-user)
                // 升级时由 MSI 自身 UUID 识别, packageVersion 必须递增
                upgradeUuid = "7F5C4E2A-3B6D-4F8A-9C1E-1A2B3C4D5E6F"
                // CI 注入的 packageVersion 带时间戳, BUILD 段对 MSI 非法, 经 msiSafeVersion
                // 映射为 "3.YY.<年内第几小时>" (deb/rpm/dmg 仍用原值)
                msiPackageVersion = msiSafeVersion(
                    compose.desktop.application.nativeDistributions.packageVersion ?: "1.0.0"
                )
            }
            // Linux deb/rpm 专属配置
            linux {
                iconFile = file("src/main/resources/icons/legado.png")
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

// About 页与检查更新读 jar manifest 的 Implementation-Version (DesktopAppInfo.versionName),
// 不写则恒回落 "1.0.0"; desktop.jar 在 jpackage 产物 classpath 首位, 取到的就是这份。
tasks.jar {
    manifest.attributes["Implementation-Version"] =
        compose.desktop.application.nativeDistributions.packageVersion ?: "1.0.0"
}

// KP1.1: :desktop:run 之前确保 :modules:quickjs 的 native 库已构建 (.dll/.so/.dylib)
// buildJvmNativeLib 探测系统 CMake + 编译器；缺失或失败会直接终止 run/打包，避免带病产物。
// 注: compose.desktop.application{} 创建的 run task 需在 afterEvaluate 中追加依赖
afterEvaluate {
    tasks.named("run").configure {
        dependsOn(project(":modules:quickjs").tasks.named("buildJvmNativeLib"))
        dependsOn(buildSmtcNative)
        dependsOn(buildWndChromeNative)
        // 开发期把媒体运行时挂回 classpath: 它已不在 runtimeClasspath (不进包), 挂上后
        // DesktopMediaRuntime 走"清单在 classpath → 本地解包"分支, 与改动前行为一致。
        if (this is JavaExec) {
            classpath += mediaRuntimeOnly
        }
        // 开发期 run 注入 debug 标志: 让 shared printStackTraceOnDebug 对齐 Android 的
        // BuildConfig.DEBUG 语义 (仅开发打栈); 打包产物不带该属性 = 静默
        if (this is JavaExec) {
            val quickjsLibraryName = when {
                OperatingSystem.current().isWindows -> "legado_quickjs.dll"
                OperatingSystem.current().isMacOsX -> "liblegado_quickjs.dylib"
                else -> "liblegado_quickjs.so"
            }
            systemProperty(
                "legado.quickjs.lib",
                quickjsNativeDir.resolve(quickjsLibraryName).absolutePath
            )
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
            "packageMsi", "packageExe", "packageDeb", "packageRpm", "packageDmg",
            // release buildType 变体 (官方 ProGuard 集成启用后的打包任务名, 与 default 同名后缀 Release)
            "createReleaseDistributable", "packageReleaseMsi", "packageReleaseExe",
            "packageReleaseDeb", "packageReleaseRpm", "packageReleaseDmg",
        )
    }.configureEach {
        dependsOn(copyQuickjsNativeToResources)
        dependsOn(copySmtcNativeToResources)
        dependsOn(copyWndChromeNativeToResources)
    }
}

// ============================================================
// ProGuard/R8 优化说明 (官方集成已落地, 见 buildTypes.release.proguard {})
// ============================================================
// R8 是 Android 专属, 桌面端无 Android 编译插件; 桌面端瘦身走官方 ProGuard 集成
// (proguardReleaseJars 任务, 接线到 createReleaseDistributable/packageRelease*)。
// Compose Multiplatform 大量依赖反射:
// - @Composable 函数通过 Compose 编译器插件生成 synthetic 方法, ProGuard 难以正确保留
// - kotlinx.serialization 用反射实例化数据类 (Book/BookSource 等)
// - Room KMP 生成的 DAO 实现类通过反射访问
// - quickjs JNI 桥通过反射查找 native 方法
// 因此规则文件 (proguard-rules.pro) 对这些危险区全部 keep + 关闭混淆 (-dontobfuscate,
// 书源按类名反射加载), 只做死代码删除。体积优化组合: ProGuard 裁死代码 (官方集成) +
// jlink 精简 JRE (strip-debug/compress 等, 见 createRuntimeImage 配置) + 7z 压缩打包。
// 产物报表在官方 proguard 任务输出目录 (build/compose/tmp/main/proguard/ 附近)。

// ============================================================
// CDS (Class Data Sharing) 归档生成
// ============================================================
// 背景: jlink 精简的 JRE 不含默认 CDS 归档 (classes.jsa),
// -Xshare:auto 静默回退到非 CDS 模式 → 每次启动从零加载/解析全部类元数据,
// 是正式版 (jlink JRE) 比 debug 版 (完整 JDK 自带 CDS) 启动慢的主因之一。
// 本 task 在 jlink 输出目录上跑该 JRE 自己的 java -Xshare:dump 生成默认 CDS 归档。
// 实测: CDS 归档可减少启动期类加载时间 20~40% (JDK 21 默认归档含 ~15k 核心类)。
//
// 【为何写在 jlink 输出目录而不是 app image 里】(2026-09 修正): 插件的 createRuntimeImage
// 不带 buildType 后缀 (所有 buildType 共用 tmp/main/runtime), createReleaseDistributable 和
// packageReleaseMsi 的 --runtime-image 全部指向它 (见两份 .args.txt 实测) —— 旧实现把归档
// 写进已生成的 app/legado/runtime, 只对便携 zip 有效, MSI 那条链永远拿不到。现写回 jlink 输出,
// 两条链共享。
//
// 【为何失败必须响】: 旧实现只 logger.warn 不 fail, 而 jlink 默认 --strip-native-commands=true
// 把 bin/java.exe 整个剔掉 (3.26.09121949 便携版实测: runtime/bin 下无任何 *.exe),
// 于是本任务每次都走 `!exists() → 跳过` 分支 —— 产物里一个 .jsa 都没有, -Xshare:auto 静默空转,
// 注释里宣称的 20~40% 启动提速从未落地。静默失效就是缺陷被长期掩埋的根因, 故现在:
// 启动器缺失 / 退出码非 0 / 归档未落地, 均当场抛 GradleException。
// 【归档目录跟平台走, 不是写死的 lib/server】(2026-09-14 实测纠正):
// Windows 上 HotSpot 的默认归档落在 runtime/bin/server/ (与 jvm.dll 同级),
// Linux/macOS 才是 runtime/lib/server/。用错路径会让本任务在 Windows 上
// 误判“dump 成功但归档不存在”而抛异常, 反而把构建卡住。
// 本机实测: java -Xshare:dump 产出 runtime/bin/server/{classes.jsa, classes_nocoops.jsa},
// 且 -Xlog:cds 确认“Opened archive ...”; java -version 中位耗时 218ms → 193ms (-Xshare:auto)。
val cdsRuntimeImageDir = file("build/compose/tmp/main/runtime")

val dumpCdsArchive by tasks.registering {
    group = "compose desktop distribution"
    description = "为 jlink JRE 生成默认 CDS 归档 (启动期类加载加速, 便携版与 MSI 共享)"
    // 依赖 jlink 输出 (插件的公共 task, 不带 buildType 后缀)
    dependsOn("createRuntimeImage")

    val serverDir = File(
        cdsRuntimeImageDir,
        if (OperatingSystem.current().isWindows) "bin/server" else "lib/server",
    )
    val baseArchive = File(serverDir, "classes.jsa")
    val nocoopsArchive = File(serverDir, "classes_nocoops.jsa")
    // 刻意不把归档声明成本 task 的 outputs: 它们落在 createRuntimeImage 的 @OutputDirectory
    // (jlink 输出目录) 里面, 两个 task 输出重叠会被 Gradle 判为互相弄脏, 结果是每次构建都
    // 重跑一遗 jlink。改为 upToDateWhen(false) 让 dump 每次都跑 (单次 ~1s, 比丢归档便宜)。
    outputs.upToDateWhen { false }

    doLast {
        val javaExe = if (OperatingSystem.current().isWindows) {
            File(cdsRuntimeImageDir, "bin/java.exe")
        } else {
            File(cdsRuntimeImageDir, "bin/java")
        }
        if (!javaExe.isFile) {
            throw GradleException(
                "CDS dump 失败: jlink 输出 $cdsRuntimeImageDir 里没有启动器 $javaExe —— "
                        + "需在 createRuntimeImage 里保持 --strip-native-commands=false (见该 task 注释)"
            )
        }
        // 只 dump 压缩指针那一份归档: jpackage cfg 里 -Xmx768m 恒走压缩指针, JVM 只映射
        // classes.jsa, classes_nocoops.jsa 运行时永不加载 —— 旧实现跑两遍 dump, 每次白往包里
        // 塞 12,386,304B (3.26.09150109 便携包实测含该文件)。下方 nocoops 的清理分支负责把
        // 旧构建留在复用 jlink 输出目录里的归档删掉, 否则只停 dump 不减体积。
        val pb = ProcessBuilder(listOf(javaExe.absolutePath, "-Xshare:dump"))
        pb.directory(cdsRuntimeImageDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val exit = proc.waitFor()
        output.lines().filter { it.isNotBlank() }
            .forEach { logger.lifecycle("[cds-dump] $it") }
        if (exit != 0) {
            throw GradleException("CDS dump 失败 (exit=$exit):\n$output")
        }
        // 冗余归档必须真删: createRuntimeImage 的产物目录被 Gradle 判定 up-to-date 时不会清空,
        // 只停掉第二遍 dump 会让上一次构建留下的 classes_nocoops.jsa 继续进包。
        if (nocoopsArchive.isFile && !nocoopsArchive.delete()) {
            throw GradleException("冗余 CDS 归档删除失败: $nocoopsArchive")
        }
        if (!baseArchive.isFile) {
            throw GradleException(
                "CDS dump 退出码为 0 但未产出 $baseArchive —— -Xshare:auto 会继续空转, 拒绝静默放行"
            )
        }
        logger.lifecycle(
            "[legado-desktop] CDS 归档已生成: ${baseArchive.name} (${baseArchive.length() / 1024} KB)"
        )
    }
}

// 确保 “复制 jlink 输出”的下游 task (app image 与各类安装包) 都在 CDS 归档生成之后才跑,
// 否则 jpackage 会先把没有 .jsa 的 runtime 复制走。
tasks.matching {
    it.name in listOf(
        "createDistributable", "createReleaseDistributable",
    ) || it.name in listOf(
        "packageReleaseMsi", "packageReleaseExe",
        "packageReleaseDeb", "packageReleaseRpm", "packageReleaseDmg",
    )
}.configureEach {
    dependsOn(dumpCdsArchive)
}

// Compose 的 deb/rpm 打包 task 不依赖 app image (jpackage 能自 --input 直接构建), 而
// QuickJS native 经 appResourcesRootDir 只落进 app/legado/, CI 校验步骤也是查这棵树。
// Windows 有 packagePortableZip 的 dependsOn 顺带拉起 app image, macOS 的 dmg 由 jpackage
// 自身要求 app image, 只剩 Linux 两端没人拉 → 按各自 buildType 显式补上。
tasks.matching { it.name in listOf("packageDeb", "packageRpm") }.configureEach {
    dependsOn("createDistributable")
}
tasks.matching { it.name in listOf("packageReleaseDeb", "packageReleaseRpm") }.configureEach {
    dependsOn("createReleaseDistributable")
}

// ============================================================
// Windows 便携版 zip 打包 task
// ============================================================
// 背景: jpackage 默认产物是 MSI/Exe 安装包 (需安装), 无法满足"拷贝即用"便携场景。
// 本 task 在 createReleaseDistributable (jpackage app-image, release 链含 ProGuard 瘦身) 后,
// 把 legado.exe + runtime/ + app/ + data/
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

    // 依赖 jpackage app image 生成 task (createReleaseDistributable 是 packageReleaseMsi/Exe 的上游,
    // 产物含 legado.exe + runtime/ + app/; release 链含官方 ProGuard 瘦身, 便携版与 MSI 同源同瘦身)
    dependsOn(
        "createReleaseDistributable",
        dumpCdsArchive,
        generatePortableDataPlaceholder,
        generatePortableMarker
    )

    // app image 输出路径 (compose desktop createReleaseDistributable 产物, 路径:
    // build/compose/binaries/{buildType}/app/{packageName}/; 官方 DSL 启用 release buildType 后
    // 目录名带 classifier 后缀 main-release, 2026-08-18 实测)
    val appImageDir = file("build/compose/binaries/main-release/app/legado")

    // 路径不存在时给清晰错误 (避免 Zip task 静默跳过)
    doFirst {
        if (!appImageDir.exists()) {
            throw GradleException(
                "App image directory not found: $appImageDir\n" +
                    "Ensure createReleaseDistributable task ran successfully on Windows."
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

    // 从 nativeDistributions 读 packageVersion (CI 传 -PappVersion 注入), 拼到 zip 文件名
    val version = compose.desktop.application.nativeDistributions.packageVersion ?: "1.0.0"
    archiveFileName.set("legado-portable-windows-x64-${version}.zip")
    // 独立输出目录, 避免与 jpackage 产物 (build/compose/binaries/) 及 Gradle 默认 distributions 目录混淆
    destinationDirectory.set(layout.buildDirectory.dir("outputs/portable"))
}
