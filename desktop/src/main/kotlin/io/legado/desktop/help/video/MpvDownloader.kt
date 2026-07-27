package io.legado.desktop.help.video

import io.legado.app.constant.AppLog
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.http.OkHttpClientProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * mpv 便携版应用内下载 (仅 Windows)。
 *
 * 未检测到 mpv 时, [io.legado.desktop.ui.book.video.MpvInstallGuide] 的"自动下载"按钮走这里:
 * GitHub Release 查最新构建 → 下载 7z → 解压 → 找 mpv.exe → 写入
 * [MpvDetector.PREF_KEY_MPV_PATH] → `MpvDetector.detect(forceRefresh = true)` 生效。
 *
 * # 为什么只做 Windows
 * Linux/macOS 包管理器一行命令即可 (apt/brew), 且发行版众多难以给出通用二进制;
 * 占位文案已有引导, 本类 [isSupported] 在非 Windows 直接返回 false, UI 不显示下载按钮。
 *
 * # 下载源与许可
 * 源: [zhongfly/mpv-winbuild](https://github.com/zhongfly/mpv-winbuild) (shinchiro
 * mpv-winbuild-cmake 的自动构建下游, 每小时跟进 mpv 上游, 保留最近 30 天构建)。
 * 取 `mpv-x86_64-*.7z` (非 v3: v3 需 AVX2, 老 CPU 会崩; 非 dev: dev 是 libmpv 开发包;
 * 非 debug: 体积翻倍)。这些是 **GPLv2+** 构建 (Releases 里带 lgpl 标记的只有 ffmpeg 与
 * mpv-dev/libmpv, 没有 LGPL 的 mpv.exe 播放器包)。legado 本身 GPL-3.0, 与 GPLv2+ 兼容,
 * 且 mpv 是运行时下载的独立进程 (非链接/非随包分发), 不构成衍生作品, 无许可阻塞。
 *
 * # 7z 解压为什么不用 Java 库
 * mpv 的 7z 用 BCJ2 编码 (x86 可执行文件多流预处理器), commons-compress 直到最新版
 * 仍抛 `Multi input/output stream coders are not yet supported` (已实测 1.21 与 1.28)。
 * 故改用 Windows 自带 `%SystemRoot%\System32\tar.exe` (Win10 1803+ 内置 bsdtar,
 * 链接 libarchive+liblzma, 原生支持 7z; 实测可解开本源产物且 mpv.exe 正常运行)。
 * 缺失 tar.exe 的老系统由 [extract] 报错, UI 回退手动安装引导。
 */
object MpvDownloader {

    /** GitHub Release API: 最新构建 (zhongfly 每次构建都发 release, latest 即最新) */
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/zhongfly/mpv-winbuild/releases/latest"

    private const val ACCEPT_HEADER = "application/vnd.github+json"

    /** 下载源主页 (失败时引导用户手动下载) */
    const val RELEASE_PAGE_URL = "https://github.com/zhongfly/mpv-winbuild/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    /** 仅 Windows 支持应用内下载 (见类注释) */
    val isSupported: Boolean get() = MpvDetector.isWindows

    /** 安装根目录: `{filesDir}/mpv` (与 DB/配置同级, 便携版随目录走) */
    private val installDir: File get() = File(AppFilesDirs.get().filesDir, "mpv")

    /**
     * 下载并安装 mpv 便携版; 返回 mpv.exe 完整路径, 失败抛异常 (由调用方转 UI 错误文案)。
     *
     * 全程在 [Dispatchers.IO] 执行; 每个阶段检查协程取消 (用户中途离开 Screen 即中止),
     * 失败/取消都清理半成品目录, 不留下坏的安装。
     *
     * @param onProgress 进度回调 (0f..1f; -1f 表示总长未知的不确定进度), 在 IO 线程调用
     */
    suspend fun downloadAndInstall(onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        require(isSupported) { "应用内下载 mpv 仅支持 Windows" }
        val dir = installDir
        // 重装前清空旧目录 (可能是上次失败的半成品或版本过旧)
        dir.deleteRecursively()
        dir.mkdirs()
        val archive = File(dir, "mpv-download.7z")
        try {
            ensureActive()
            val (assetUrl, assetName) = fetchLatestAssetUrl()
            AppLog.put("mpv 便携版下载开始: $assetName")
            ensureActive()
            download(assetUrl, archive, onProgress)
            ensureActive()
            // 解压阶段总长未知 (bsdtar 无进度输出), 回落不确定进度
            onProgress(-1f)
            extract(archive, dir)
            val exe = findMpvExe(dir)
                ?: throw IOException("解压完成但未找到 mpv.exe (下载源目录结构可能已变更)")
            // 落存路径供 MpvDetector 优先级最高的"设置项自定义路径"命中
            PreferenceProviders.get().putString(MpvDetector.PREF_KEY_MPV_PATH, exe.absolutePath)
            AppLog.put("mpv 便携版安装完成: ${exe.absolutePath}")
            exe.absolutePath
        } catch (e: Throwable) {
            // 失败/取消: 清理半成品目录, 避免残留几十 MB 垃圾 + 下次探测到坏文件
            runCatching { dir.deleteRecursively() }
            throw e
        } finally {
            runCatching { archive.delete() }
        }
    }

    /**
     * 查 latest release, 选出 mpv 播放器包资产, 返回 (下载直链, 资产名)。
     *
     * 资产名模式: `mpv-x86_64-<日期>-git-<hash>.7z` / aarch64 同构。
     * 排除 `mpv-dev-*` (libmpv 开发包) / `mpv-debug-*` (调试符号, 体积翻倍) /
     * `-v3` (要求 AVX2 指令集, 老 CPU 直接崩) / `ffmpeg-*`。
     */
    private fun fetchLatestAssetUrl(): Pair<String, String> {
        val client = OkHttpClientProviders.get().okHttpClient
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", ACCEPT_HEADER)
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub API 请求失败 (HTTP ${response.code})")
            }
            response.body.string()
        }
        val assets = json.parseToJsonElement(body).jsonObject["assets"]?.jsonArray
            ?: throw IOException("GitHub API 响应无 assets 字段")
        // ARM64 Windows 取 aarch64 包, 其余一律 x86_64 (x86_64 包在 ARM 上靠模拟层能跑但性能差)
        val arch = System.getProperty("os.arch", "").lowercase()
        val wantArch = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
        for (asset in assets) {
            val obj = asset.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!name.startsWith("mpv-", ignoreCase = true)) continue
            if (!name.endsWith(".7z", ignoreCase = true)) continue
            if (name.startsWith("mpv-dev-", true) || name.startsWith("mpv-debug-", true)) continue
            if (!name.contains("-$wantArch-", ignoreCase = true)) continue
            if (name.contains("-v3-", ignoreCase = true)) continue
            val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: continue
            return url to name
        }
        throw IOException("最新构建中未找到 mpv-$wantArch 资产")
    }

    /**
     * 流式下载到 [dest], 按 Content-Length 回调进度 (无长度头时回调 -1f 不确定进度)。
     *
     * 下载超时单独放宽 (共享 client 的读超时按接口请求配置, 几十 MB 的包会超时);
     * 每块写入后检查协程取消, 用户中途返回可立即中止。
     */
    private suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val client = OkHttpClientProviders.get().okHttpClient
            .newBuilder()
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(30, TimeUnit.MINUTES)
            .build()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败 (HTTP ${response.code})")
            }
            val body = response.body
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            // 按整百分点节流回调, 避免每 64KB 触发一次 Compose 重组
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct / 100f)
                            }
                        } else {
                            onProgress(-1f)
                        }
                    }
                }
            }
        }
    }

    /**
     * 解压 7z 到 [dir], 走 Windows 自带 bsdtar (见类注释: Java 侧无可用 7z BCJ2 解码器)。
     *
     * 用 `%SystemRoot%\System32\tar.exe` 绝对路径而非 PATH 上的 `tar`: 装了 Git/MSYS 的机器
     * PATH 里往往是 GNU tar, 不支持 7z。
     */
    private fun extract(archive: File, dir: File) {
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val tar = File(systemRoot, "System32\\tar.exe")
        if (!tar.isFile) {
            throw IOException("系统缺少 tar.exe (需 Windows 10 1803 及以上), 请手动下载 mpv")
        }
        val process = ProcessBuilder(tar.absolutePath, "-xf", archive.absolutePath, "-C", dir.absolutePath)
            .redirectErrorStream(true)
            .start()
        // drain 输出防管道缓冲塞满导致子进程阻塞
        val output = StringBuilder()
        val drain = Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            }
        }, "mpv-extract-drain").apply { isDaemon = true; start() }
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IOException("解压超时")
        }
        drain.join(2000)
        if (process.exitValue() != 0) {
            throw IOException("解压失败 (exit=${process.exitValue()})\n${output.toString().take(500)}")
        }
    }

    /** 解压产物里找 mpv.exe (当前源放在根目录, 递归兜底防目录结构变更) */
    private fun findMpvExe(dir: File): File? {
        File(dir, "mpv.exe").takeIf { it.isFile }?.let { return it }
        return dir.walkTopDown().maxDepth(3)
            .firstOrNull { it.isFile && it.name.equals("mpv.exe", ignoreCase = true) }
    }
}
