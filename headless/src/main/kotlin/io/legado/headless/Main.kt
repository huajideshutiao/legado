package io.legado.headless

import io.legado.app.api.controller.ImageControllerProviders
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabaseProviders
import io.legado.app.help.coroutine.registerJvmDebugState
import io.legado.app.help.i18n.registerAppStringProvider
import io.legado.app.help.image.ImageOps
import io.legado.app.help.image.ImageRef
import io.legado.app.help.ui.OpenUrlProvider
import io.legado.app.help.ui.OpenUrlProviders
import io.legado.app.model.script.JsBindingInjector
import io.legado.app.utils.browseUrl
import io.legado.app.web.WebServerManager
import io.legado.desktop.DesktopCore
import io.legado.desktop.help.DesktopCrashHandler
import io.legado.desktop.model.fileBook.registerDesktopFileBookAccessor
import io.legado.desktop.model.webBook.DesktopImageControllerProvider
import io.legado.desktop.restartMainClass
import io.legado.desktop.startupArgs
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val TAG = "legado-headless"

/**
 * 桌面端无头模式入口 (headless): 无窗口/无 AV/无 UI 初始化的后台进程。
 *
 * 启动即初始化与桌面便携版等价的数据/配置环境 (legado.portable.root → 运行目录旁 data/,
 * 数据库/书源/配置落位与便携版一致), 然后打开 Web 服务端口 ([WebServerManager]) 常驻,
 * 供 Web 端阅读/管理 (与桌面端"我的"页 Web 服务开关同一链路)。
 *
 * 跑法: .\gradlew :headless:run (开发期, 数据落 headless/data/) 或
 * .\gradlew :headless:headlessDist (分发包 bin/lib, 资源与 native 内置 jar)。
 *
 * # 已知取舍 (本期)
 * - shared 的 jvm 变体与 Compose UI 源集绑死 (jvmMain 编译引用 compose 传递依赖), 本期
 *   headless 运行时仍携带 compose 相依 jar —— 全程不调用任何 UI 代码, UI 类不会被加载,
 *   仅磁盘/内存冗余; 后续可选裁剪 (拆 shared jvm 源集)。
 * - 单实例守卫 (SingleInstanceGuard) 因 java.awt/javax.swing import (bindWindow 窗口前置)
 *   留在 :desktop, 本期 headless 不做单实例互斥 —— 同时启动多个实例会争抢同一 SQLite 库,
 *   部署时需自行保证单进程 (systemd/任务计划等幂等拉起方式)。
 * - UI 绑定 provider 因重依赖留在 :desktop, headless 缺失能力 (对照桌面阶段1+阶段3):
 *   内嵌浏览器 (BackstageWebView/书源验证码 UI, 书源 create 由调用方 runCatching 回退 HTTP)、
 *   压缩包内选书导入与解压 (DesktopArchiveCodec → junrar/commons-compress; txt/epub/cbz(zip)
 *   本地书导入经注入化 [registerDesktopFileBookAccessor](null, null) 可用, rar/7z/pdf 显式报错)、
 *   漫画位图处理 (skia DesktopBitmapProvider)、Web 封面/插图字节流可用但无超宽缩图
 *   ([DesktopImageControllerProvider] 恒等缩放策略)、音频播放/SMTC
 *   (mediamp)、系统 TTS 引擎 (JNA SAPI; HttpTTS 朗读不受影响)、打开链接确认框
 *   ([HeadlessOpenUrlProvider] 改为直开系统浏览器)、JS 图片 API ([HeadlessNoopImageOps]
 *   显式报错, JS eval 本体可用)。
 */
fun main(args: Array<String>) {
    // 打栈开关: 对齐 Android BuildConfig.DEBUG 语义, 仅 debug 打栈
    // (:headless:run 注入 -Dlegado.debug=true, 打包产物不注入 = 静默)
    registerJvmDebugState(System.getProperty("legado.debug")?.toBoolean() == true)
    // 1. 数据环境初始化:
    //    - 命令行参数 --data-dir=... 或系统属性 legado.data.dir / legado.portable.root 优先
    //    - 打包分发包目录下若存在 portable.txt, 走便携模式 (程序同级 data/)
    //    - 默认传 null: DesktopCore 自动对齐系统标准数据目录 (Win=%APPDATA%\legado),
    //      与桌面端共享数据, 且绝不污染源码树
    val dataDir = resolveDataDir(args)
    // 2. quickjs native: 优先从 classpath 资源提取 (打包自包含); 开发期无资源 → 传 null,
    //    quickjs Platform.kt 候选3 从工作目录向上递归找 modules/quickjs 构建产物
    DesktopCore.initRuntimeEnvironment(dataDir, extractQuickjsNative())
    // 3. 全局崩溃日志 (与桌面同款): 必须紧跟 initRuntimeEnvironment —— 落盘目录依赖
    //    它设的 legado.portable.root
    DesktopCrashHandler.install()
    // 单实例守卫: 留 :desktop (见顶部取舍注释), 本期跳过
    // 重启支持: 覆写主类为 headless 入口 (默认 io.legado.desktop.MainKt 会拉起桌面 UI),
    // 并保存启动参数 (DesktopAppRestart 的 ProcessBuilder 复用)
    restartMainClass = "io.legado.headless.MainKt"
    startupArgs = args
    // 4. JS 图片 API 兜底: skia 版 DesktopImageOps 留 :desktop, headless 注册显式报错实现。
    //    必须先于 registerCoreProviders (JsBindings 构造访问 JsBindingInjector.image,
    //    未注册 checkNotNull 失败, 任何 JsEngine.eval 都跑不了; 兜底后 eval 可跑,
    //    仅图片 API 给出可诊断异常)
    JsBindingInjector.registerImageOps(HeadlessNoopImageOps)
    // 5. provider 注册: 阶段1 核心 (含 config/数据库/JS 引擎/HTTP/朗读工厂) —— 与桌面
    //    DesktopCore.registerCoreProviders 完全等价
    DesktopCore.registerCoreProviders()
    // 5.2 AppString 兜底重注册: DesktopCore 注册的 jvmGetString 走 compose 字符串资源
    //     (skiko/AWT 取系统主题), 无头 JVM 里必抛 → BackupConfigShared 等 <clinit> 调
    //     appString 的类全部 NoClassDefFoundError。重注册为键名兜底 (语义同未注册 fallback,
    //     文案损失仅影响极少数错误提示)。
    registerAppStringProvider { key, _ -> key.name }
    // 5.5 本地书导入 + Web 封面/插图: 实现均在 desktop-core, 重能力注入化。
    //     - DesktopFileBookAccessor: 不注入压缩/PDF → txt/epub/cbz(zip) 导入可用,
    //       rar/7z/pdf 显式报错 (对齐桌面 registerDesktopFileBookAccessor 时机:
    //       任何 EpubFile/FileBook 调用之前)
    //     - DesktopImageControllerProvider: 缩图策略恒等 (无 skia) → /cover /getImg
    //       返回缓存/下载原始字节, 不做超宽缩图
    registerDesktopFileBookAccessor(null, null)
    ImageControllerProviders.register(DesktopImageControllerProvider())
    // 无 UI 确认框: 书源 openUrl 直开系统浏览器 (桌面端有 DesktopDialogs 确认框,
    // 无头进程无从确认; 直开语义见 HeadlessOpenUrlProvider 注释)
    OpenUrlProviders.register(HeadlessOpenUrlProvider)
    // 6. 阶段3 核心 provider (含 Web 服务三件套注册) + 启动期异步任务
    //    (adjustSortNumber/默认数据/清理/WebDav, 与桌面尾部逐行等价)
    runBlocking {
        DesktopCore.registerSecondaryCoreProviders()
        DesktopCore.startupBackgroundTasks()
    }
    // 7. Web 服务: 与桌面"我的"页开关同一入口 (start 内部先停旧实例再起 HttpServer+WS)
    val result = WebServerManager.startWithResult()
    if (result.addresses.isEmpty()) {
        System.err.println(
            "headless 启动失败: Web 服务无法启动 " +
                (result.errorMsg ?: "(无可用网络地址)")
        )
        exitProcess(1)
    }
    // 8. 就绪通告 + 常驻: 主线程阻塞, 进程由 SIGINT/SIGTERM/kill 触发 shutdown hook 收尾
    println("headless ready: ${result.addresses.joinToString(", ")}")
    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread({
            runCatching { WebServerManager.stop() }
            // 数据库收尾: 桌面端退出路径 (compose exitApplication) 未显式关库, 这里补上
            // 稳妥关闭 (Room close; 失败仅记日志, 进程退出不受阻)
            runCatching {
                AppDatabaseProviders.get().appDb.close()
            }.onFailure { AppLog.put("headless 数据库关闭失败: ${it.message}", it) }
            println("headless stopped")
        }, "legado-headless-shutdown")
    )
    latch.await()
}

/**
 * 解析 headless 数据目录:
 * 1. 显式覆盖: 命令行 `--data-dir=...` 或系统属性 `-Dlegado.data.dir=...` / `-Dlegado.portable.root=...`
 * 2. 便携模式: 打包分发包目录下若存在 `portable.txt` 标记, 使用同级 `data/` 目录
 * 3. 默认返回 null: 交由 DesktopCore 走系统标准数据目录 (Win=%APPDATA%\legado), 与桌面端完全对齐
 */
private fun resolveDataDir(args: Array<String>): File? {
    args.firstOrNull { it.startsWith("--data-dir=") }
        ?.substringAfter("--data-dir=")
        ?.takeIf { it.isNotBlank() }
        ?.let { return File(it) }

    System.getProperty("legado.data.dir")
        ?.takeIf { it.isNotBlank() }
        ?.let { return File(it) }

    System.getProperty("legado.portable.root")
        ?.takeIf { it.isNotBlank() }
        ?.let { return File(it) }

    val appRoot = locateAppRoot()
    if (appRoot != null && File(appRoot, "portable.txt").exists()) {
        return File(appRoot, "data")
    }
    return null
}

/**
 * 定位应用根目录 (打包分发包 bin 上级):
 * - 打包 (installDist/distZip): jar 位于应用根下的 lib 目录 → 取 lib 上级 (bin 启动脚本所在目录即应用根)
 * - 开发期返回 null (不作为便携根目录)
 */
private fun locateAppRoot(): File? {
    val codeSource = runCatching {
        File(object : Any() {}.javaClass.protectionDomain.codeSource.location.toURI())
    }.getOrNull()
    val jarFile = codeSource?.takeIf { it.isFile } ?: return null
    val distRoot = jarFile.parentFile?.parentFile
    if (distRoot != null && distRoot.resolve("bin").isDirectory) return distRoot
    return jarFile.parentFile ?: jarFile
}

/**
 * 从 classpath 提取 quickjs native 库到临时文件 (headless/build.gradle.kts 的
 * copyQuickjsNativeToHeadlessResources 把库复制进 jar 资源), 返回库文件;
 * 资源不存在 (开发期) 返回 null → DesktopCore 不设属性, quickjs Platform.kt
 * 候选3 (向上递归找构建产物) 兜底。
 */
private fun extractQuickjsNative(): File? {
    val osName = System.getProperty("os.name").lowercase()
    val libName = when {
        osName.contains("windows") -> "legado_quickjs.dll"
        osName.contains("mac") || osName.contains("darwin") -> "liblegado_quickjs.dylib"
        else -> "liblegado_quickjs.so"
    }
    val bytes = runCatching {
        object : Any() {}.javaClass.classLoader.getResourceAsStream(libName)?.use { it.readBytes() }
    }.getOrNull() ?: return null
    if (bytes.isEmpty()) return null
    return runCatching {
        val suffix = "." + libName.substringAfterLast('.')
        val out = File.createTempFile("legado_quickjs_", suffix)
        out.deleteOnExit()
        out.writeBytes(bytes)
        out
    }.onFailure {
        AppLog.put("headless quickjs native 提取失败: ${it.message}", it)
    }.getOrNull()
}

/**
 * headless 兜底 [OpenUrlProvider]: 书源请求跳转外部链接时直开系统默认浏览器
 * (桌面端先弹 DesktopDialogs 确认框防恶意书源, 无头进程无 UI 无从确认 —— 本期接受
 * 直开语义并记录日志; 服务化部署建议用容器网络策略限制进程可访问的外部地址)。
 */
private object HeadlessOpenUrlProvider : OpenUrlProvider {

    override fun openUrl(
        url: String,
        mimeType: String?,
        sourceKey: String?,
        sourceTag: String?,
        sourceType: Int,
    ) {
        AppLog.put("headless openUrl (无确认框直开): $url", tag = TAG)
        if (!browseUrl(url)) {
            AppLog.put("headless 打开链接失败: $url", tag = TAG)
        }
    }
}

/**
 * headless 兜底 [ImageOps]: 所有像素操作显式抛 UnsupportedOperationException。
 *
 * 注册它是为了让 JsBindingInjector.image 非空 —— JsBindings 构造时访问该 getter,
 * 未注册时任何 JsEngine.eval 都跑不了; 注册后 JS eval/书源规则正常, 仅图片类 API
 * (图片解密/裁切/拼接等) 给出可诊断异常而非启动崩溃。skia 版实现在 :desktop。
 */
private object HeadlessNoopImageOps : ImageOps {

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("headless 模式无 skia 图片栈, JS 图片 API 不可用")

    override fun decode(bytes: ByteArray): ImageRef = unsupported()
    override fun decode(base64: String): ImageRef = unsupported()
    override fun encode(img: ImageRef, format: String, quality: Int): ByteArray = unsupported()
    override fun split(img: ImageRef, rows: Int, cols: Int): List<ImageRef> = unsupported()
    override fun stitch(imgs: List<ImageRef>, direction: String): ImageRef = unsupported()
    override fun crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef = unsupported()
    override fun rotate(img: ImageRef, deg: Int): ImageRef = unsupported()
    override fun flip(img: ImageRef, direction: String): ImageRef = unsupported()
    override fun size(img: ImageRef): Map<String, Int> = unsupported()
}
