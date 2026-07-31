package io.legado.desktop.help.webview.win

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import io.legado.app.constant.AppLog
import java.io.File

/**
 * WebView2 Evergreen Runtime 探测与加载。
 *
 * # 为什么不发 WebView2Loader.dll
 * 常规做法是随包分发 Microsoft 的 `WebView2Loader.dll` 再调它的
 * `CreateCoreWebView2EnvironmentWithOptions`。但 runtime 自带的
 * `EBWebView\<arch>\EmbeddedBrowserWebView.dll` 直接导出
 * `CreateWebViewEnvironmentWithOptionsInternal`, 按注册表定位后 LoadLibrary 即可,
 * 于是**一个 native 文件都不用随包发**, 发行包体积零增量 (上游 webview 库的
 * `WEBVIEW_MSWEBVIEW2_BUILTIN_IMPL` 走的也是这条路)。
 *
 * 缺失 runtime 时 [detect] 返回 null, 由上层回退下一级引擎; [BOOTSTRAPPER_URL]
 * 供 UI 引导用户装 Evergreen Bootstrapper (模式参考 MpvDownloader 的引导安装)。
 */
internal object WebView2Runtime {

    /** Edge WebView2 Runtime 稳定通道的 EdgeUpdate ClientState GUID。 */
    private const val STABLE_CHANNEL_GUID = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"

    private const val CLIENT_STATE_KEY =
        "SOFTWARE\\Microsoft\\EdgeUpdate\\ClientState\\$STABLE_CHANNEL_GUID"

    /**
     * 需要的最低 WebView2 API 版本 (取 runtime 版本号第三段, 如 150.0.**4078**.105)。
     * 与上游 webview 库同门槛: 低于此版本的 runtime 缺少本实现用到的接口。
     */
    private const val MIN_API_VERSION = 1150

    /** Evergreen Bootstrapper 官方下载入口 (约 2MB, 联网拉取完整 runtime)。 */
    const val BOOTSTRAPPER_URL = "https://go.microsoft.com/fwlink/p/?LinkId=2124703"

    /** runtime 客户端 DLL 中的环境创建导出 (非公开 API, 但即为 loader dll 内部所调)。 */
    private const val CREATE_ENVIRONMENT_FN = "CreateWebViewEnvironmentWithOptionsInternal"

    /** `CreateWebViewEnvironmentWithOptionsInternal` 第二参: 0 = 已安装的 runtime。 */
    const val RUNTIME_TYPE_INSTALLED = 0

    /** 探测结果: [version] 供日志, [createEnvironment] 直接可调。 */
    class Runtime(val version: String, val createEnvironment: Function)

    @Volatile
    private var cached: Runtime? = null

    @Volatile
    private var probed = false

    /** 探测并加载 runtime; 未安装/版本过低/加载失败一律返回 null (不抛)。 */
    @Synchronized
    fun detect(forceRefresh: Boolean = false): Runtime? {
        if (probed && !forceRefresh) return cached
        cached = runCatching { doDetect() }.onFailure {
            AppLog.put("WebView2 runtime 探测失败", it)
        }.getOrNull()
        probed = true
        return cached
    }

    /** 仅探测是否已安装 (不加载 DLL), 供设置页/引导 UI 用。 */
    fun installedVersion(): String? = if (!Platform.isWindows()) null else readClientState()?.second

    private fun doDetect(): Runtime? {
        if (!Platform.isWindows()) return null
        val (installDir, version) = readClientState() ?: return null
        if (parseApiVersion(version) < MIN_API_VERSION) {
            AppLog.put("WebView2 runtime 版本过低 ($version), 需要 API >= $MIN_API_VERSION")
            return null
        }
        val dll = clientDll(installDir)
        if (!dll.isFile) {
            AppLog.put("WebView2 runtime 注册表存在但客户端 DLL 缺失: ${dll.absolutePath}")
            return null
        }
        val function = NativeLibrary.getInstance(dll.absolutePath)
            .getFunction(CREATE_ENVIRONMENT_FN, Function.ALT_CONVENTION)
        return Runtime(version, function)
    }

    /**
     * 读 EdgeUpdate ClientState: 返回 (安装目录, 版本号)。
     *
     * EdgeUpdate 写的是 32 位视图, 64 位 JVM 必须带 KEY_WOW64_32KEY 才读得到;
     * 机器级 (HKLM) 优先, 找不到再看用户级 (HKCU) 安装。
     */
    private fun readClientState(): Pair<String, String>? {
        val roots = listOf(WinReg.HKEY_LOCAL_MACHINE, WinReg.HKEY_CURRENT_USER)
        for (root in roots) {
            val dir = registryString(root, "EBWebView") ?: continue
            if (dir.isBlank()) continue
            // pv 缺失时退回目录末段 (安装目录名即版本号)
            val version = registryString(root, "pv")?.takeIf { it.isNotBlank() }
                ?: File(dir).name
            return dir to version
        }
        return null
    }

    private fun registryString(root: WinReg.HKEY, name: String): String? = runCatching {
        Advapi32Util.registryGetStringValue(root, CLIENT_STATE_KEY, name, WinNT.KEY_WOW64_32KEY)
    }.getOrNull()

    /** `<安装目录>\EBWebView\<arch>\EmbeddedBrowserWebView.dll` */
    private fun clientDll(installDir: String): File {
        val arch = when {
            Platform.isARM() -> "arm64"
            Platform.is64Bit() -> "x64"
            else -> "x86"
        }
        return File(File(File(installDir, "EBWebView"), arch), "EmbeddedBrowserWebView.dll")
    }

    /** 取版本号第三段作为 API 版本 (150.0.4078.105 -> 4078); 解析失败按 0 处理。 */
    private fun parseApiVersion(version: String): Int =
        version.split('.').getOrNull(2)?.toIntOrNull() ?: 0
}
