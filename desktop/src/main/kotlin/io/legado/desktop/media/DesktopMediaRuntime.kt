package io.legado.desktop.media

import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.file.desktopAppCacheDir
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.openani.mediamp.mpv.MpvMediampPlayer
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 桌面端媒体播放组件 (mpv + FFmpeg native) 的按需安装。
 *
 * 背景: 这套 native 以 `mediamp-mpv-runtime-<os>-<arch>` 单工件形式存在, 实测 jar 21.0MB
 * (内含 37 个 dll/so/dylib, 解压后 52.7MB), 是安装包里最大的一块。用户裁决 (2026-09-15):
 * 不再随包发布, 改成用户第一次播视频/音频时下载。它**同时**服务视频页与桌面音频播放
 * (`DesktopAudioPlayer.ensureEngine` 建的是同一个 MediampPlayer), 所以门禁与文案都是
 * "媒体播放组件", 不是"视频组件"。
 *
 * 加载契约 (直读 mediamp 0.3.0 sources 确认, 不是推测):
 * - `MpvMediampPlayer.prepareLibraries(path, extractRuntimeLibrary = false)` 必须在第一次
 *   创建播放器之前调用; 它内部 `validate = true`, 只要 `path` 下存在 wrapper 文件就直接
 *   `System.load(绝对路径)` + `SetDllDirectoryW(path)`, **不读 classpath 清单**;
 * - wrapper 文件名由 `nativeLibraryFileName("mediampv")` 决定: win `mediampv.dll`,
 *   mac `libmediampv.dylib`, 其余 `libmediampv.so`;
 * - 依赖 dll 靠同目录搜索解析, 所以 37 个文件必须平铺在同一目录、文件名逐字节不变;
 * - `NativeRuntimeLoader` 内部按 libraryName 记住已加载目录, **一个进程只允许配置一次目录**,
 *   故安装目录必须跨启动稳定 (不能拿临时目录当安装目录)。
 *
 * 下载源: 只用阿里云 central 镜像 (用户裁决, 不做多级回退)。注意它是**懒缓存**镜像 ——
 * 新版本首次被访问时可能 404, 再请求才回源命中, 所以这里对 404/IO 失败做有限次退避重试。
 */
object DesktopMediaRuntime {

    private const val MIRROR_BASE = "https://maven.aliyun.com/repository/central"

    /** 版本与各平台 SHA-1 由 :desktop:generateMediaRuntimeConfig 从 libs.versions.toml 生成 */
    private val version: String get() = MediaRuntimeConfig.VERSION

    private val expectedSha1: String? get() = MediaRuntimeConfig.sha1ByPlatform[platformSuffix]

    /** 懒缓存镜像冷启动首刷 404 的吸收: 同一 URL 最多 3 次, 间隔 1s/2s */
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    enum class Phase {
        /** 组件缺失, 等用户确认是否下载 */
        NeedConfirm,

        /** 下载中 */
        Downloading,

        /** 下载/校验/加载失败, 可重试 */
        Failed,
        ;
    }

    data class UiState(
        val phase: Phase? = null,

        /** 0..100; 总数未知时为 -1 (UI 走不确定态) */
        val percent: Int = -1,

        /** 已下载字节 / 总字节 */
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,

        /** 失败原因 (仅 [Phase.Failed] 有值) */
        val message: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())

    /** 弹框宿主订阅: phase 非 null = 需要显示弹框 */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 本进程是否已把运行时目录配置给 mediamp (一个进程只允许配一次) */
    @Volatile
    private var prepared = false

    @Volatile
    private var installing = false

    /** 平台后缀, 与 mediamp `platformSuffixFor` 同规则 (工件名/清单名都用它) */
    private val platformSuffix: String by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
        val osPart = when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            else -> "linux"
        }
        val archPart = if (arch.contains("aarch64") || arch.contains("arm64")) "arm64" else "x64"
        "$osPart-$archPart"
    }

    /** 开发期 classpath 上仍有 runtime 工件 (:desktop:run 显式挂 mediaRuntimeOnly) 时为 true */
    private val manifestOnClasspath: String? by lazy {
        val name = "mpv-natives-$platformSuffix.txt"
        val loader = MpvMediampPlayer::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
        if (loader.getResource(name) != null) name else null
    }

    private fun installDir(): File =
        File(File(desktopAppCacheDir(), "media-runtime"), "$platformSuffix-$version")

    private fun wrapperFileName(): String = when {
        platformSuffix.startsWith("windows") -> "mediampv.dll"
        platformSuffix.startsWith("macos") -> "libmediampv.dylib"
        else -> "libmediampv.so"
    }

    /** 安装完成标记 (内容 = 校验通过的 jar 的 SHA-1); 用它区分"装好了"与"下到一半被杀" */
    private fun markerFile(dir: File): File = File(dir, ".sha1")

    /**
     * 不弹框的就绪查询: 给音频侧“装完了就清掉临时错误”用。
     *
     * 与 [ensureReady] 的区别是它**不推 UI 状态** —— 一个查询不应产生"弹框"副作用。
     */
    fun isReady(): Boolean = prepared ||
        manifestOnClasspath != null ||
        isInstalled(installDir())

    /**
     * 播放器创建点调用: 媒体运行时可用返回 true。
     *
     * 返回 false 时已把 [uiState] 推到"需要下载"态, 由宿主弹框呈现进度与重试 —— 调用方只需
     * 按现有失败路径降级 (视频返回空控制器并报错, 音频置 engineError), 不要自己再弹东西。
     */
    fun ensureReady(): Boolean {
        if (prepared) return true
        // 开发期: jar 还在 classpath, 走 mediamp 自带的 classpath 解包, 与按需下载互不干扰
        manifestOnClasspath?.let {
            return prepare { MpvMediampPlayer.prepareLibraries() }
        }
        val dir = installDir()
        if (isInstalled(dir)) {
            return prepare { MpvMediampPlayer.prepareLibraries(dir.absolutePath, false) }
        }
        // 未安装: 推到确认态。已在下载中则保持下载态 (用户取消后不会自动重来)
        if (!installing && _uiState.value.phase != Phase.Downloading) {
            _uiState.value = UiState(phase = Phase.NeedConfirm)
        }
        return false
    }

    private fun isInstalled(dir: File): Boolean {
        val expected = expectedSha1 ?: return false
        if (!File(dir, wrapperFileName()).isFile) return false
        val marker = markerFile(dir)
        if (!marker.isFile) return false
        // .sha1 完成标记读取失败 (被占用/权限/刚被删的竞争窗口) 不能当成"已装好", 也不能
        // 把 IOException 丢给调用方 —— isReady() 会被视频页构造器直接调, 异常会变成"进页即崩"。
        // 记日志后按未安装处理, 走正常确认/下载流程。
        val actual = runCatching { marker.readText() }.onFailure {
            AppLog.put("媒体组件完成标记读取失败: ${marker.absolutePath}", it)
        }.getOrNull() ?: return false
        return actual.trim().equals(expected, ignoreCase = true)
    }

    /** prepareLibraries 会把 native 永久装进本进程, 失败必须留日志且不许静默当作成功 */
    private inline fun prepare(block: () -> Unit): Boolean = try {
        block()
        prepared = true
        _uiState.value = UiState()
        true
    } catch (e: Throwable) {
        AppLog.put("媒体播放组件加载失败: ${e.message}", e)
        _uiState.value = UiState(phase = Phase.Failed, message = e.message ?: e.javaClass.simpleName)
        false
    }

    /** 用户在弹框上点「下载」 */
    fun startInstall() {
        if (prepared || installing) return
        val expected = expectedSha1
        if (expected == null) {
            _uiState.value = UiState(
                phase = Phase.Failed,
                message = "平台 $platformSuffix 无预置校验值 (mediamp $version)",
            )
            return
        }
        installing = true
        _uiState.value = UiState(phase = Phase.Downloading)
        Coroutine.async {
            try {
                install(expected)
            } catch (e: Throwable) {
                AppLog.put("媒体播放组件安装失败: ${e.message}", e)
                _uiState.value = UiState(
                    phase = Phase.Failed,
                    message = e.message ?: e.javaClass.simpleName,
                )
            } finally {
                installing = false
            }
        }
    }

    /** 用户取消 (下载中取消 = 关掉弹框, 下次进播放页会重新问) */
    fun dismiss() {
        if (!installing) _uiState.value = UiState()
    }

    /** 手动递归删: 不用 stdlib 的 File.deleteDir() (本工程 kotlin-stdlib 配置下未解析到该扩展) */
    private fun deleteTree(target: File) {
        if (target.isDirectory) {
            target.listFiles()?.forEach(::deleteTree)
        }
        if (!target.delete()) {
            AppLog.put("旧版本媒体组件残留未删净: ${target.absolutePath}", tag = "媒体组件")
        }
    }

    private suspend fun install(expectedSha1: String) {
        val dir = installDir()
        dir.parentFile?.mkdirs()
        // 版本目录同级只剩当前版本: 升级后旧版本那套 37 个文件不该继续占磁盘
        dir.parentFile?.listFiles()?.filter { it.isDirectory && it != dir }?.forEach { deleteTree(it) }

        val client = OkHttpClient()
        val fileName = "mediamp-mpv-runtime-$platformSuffix-$version.jar"
        val url = "$MIRROR_BASE/org/openani/mediamp/mediamp-mpv-runtime-$platformSuffix/" +
            "$version/$fileName"
        val jar = downloadWithRetry(client, url, File(dir.parentFile, "$fileName.part"), expectedSha1)

        val extracted = try {
            extractNatives(jar, dir)
        } finally {
            jar.delete()
        }
        if (!File(dir, wrapperFileName()).isFile) {
            throw IllegalStateException("解包后缺少 ${wrapperFileName()} (清单条目 $extracted 个)")
        }
        markerFile(dir).writeText(expectedSha1)
        AppLog.put("媒体播放组件已安装: ${dir.absolutePath} ($extracted 个文件)", tag = "媒体组件")
        prepare { MpvMediampPlayer.prepareLibraries(dir.absolutePath, false) }
    }

    /**
     * 下载并校验 SHA-1。
     *
     * 重试只针对"阿里懒缓存首刷 404 / 连接失败"这类可自愈错误; 校验值不符是硬错误, 直接抛,
     * 不重试 —— 换源重下也不会变成正确内容。
     */
    private suspend fun downloadWithRetry(
        client: OkHttpClient,
        url: String,
        dest: File,
        expectedSha1: String,
    ): File {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            try {
                return downloadOnce(client, url, dest, expectedSha1)
            } catch (e: Throwable) {
                lastError = e
                dest.delete()
                if (e is Sha1MismatchException) throw e
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    AppLog.put(
                        "媒体组件下载第 $attempt 次失败, 重试: ${e.message}",
                        tag = "媒体组件",
                    )
                    delay(attempt * 1000L)
                }
            }
        }
        throw lastError ?: IllegalStateException("媒体播放组件下载失败")
    }

    private class Sha1MismatchException(actual: String, expected: String) :
        IllegalStateException("下载内容校验失败 (sha1 $actual ≠ $expected)")

    private suspend fun downloadOnce(
        client: OkHttpClient,
        url: String,
        dest: File,
        expectedSha1: String,
    ): File {
        _uiState.value = UiState(phase = Phase.Downloading, percent = 0, downloadedBytes = 0, totalBytes = 0)
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} $url")
            }
            val body = resp.body
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-1")
            dest.outputStream().buffered().use { fileOut ->
                body.byteStream().use { input ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        fileOut.write(buf, 0, n)
                        done += n
                        publishProgress(done, total)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha1, ignoreCase = true)) {
                throw Sha1MismatchException(actual, expectedSha1)
            }
        }
        return dest
    }

    /** 进度按整百分比推进再刷 UI, 避免 21MB 下载把弹框按帧重组成性能问题 */
    private var lastPublishedPercent = -1

    private fun publishProgress(done: Long, total: Long) {
        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
        if (percent == lastPublishedPercent) return
        lastPublishedPercent = percent
        _uiState.value = UiState(
            phase = Phase.Downloading,
            percent = percent,
            downloadedBytes = done,
            totalBytes = total,
        )
    }

    /**
     * 按 jar 内自带清单 `mpv-natives-<平台>.txt` 平铺解包。
     *
     * 清单是 mediamp 自己维护的权威文件名单, 不在我们这边复制一份, 避免升级时名单漂移;
     * 条目名带 `-` 版本号 (如 avcodec-62.dll), 必须逐字节保持原名, 否则 wrapper 的依赖找不到。
     *
     * # 条目名先过"纯文件名"硬约束再落盘
     * 清单与 dll 同源于下载来的工件, 拼出 `../x.dll` 就会把文件写到安装目录之外 —— 而本目录会被
     * `prepareLibraries` 交 `SetDllDirectoryW` 进 dll 搜索路径, 写到外面的东西副作用不可控。
     * 因此带路径分隔符/`..`/绝对路径的条目名直接抛, 不静默跳过 (跳过 = 默认收下坏清单)。
     */
    private fun extractNatives(jar: File, dir: File): Int {
        ZipFile(jar).use { zf ->
            val manifestEntry = zf.getEntry("mpv-natives-$platformSuffix.txt")
                ?: throw IllegalStateException("工件内缺少 mpv-natives-$platformSuffix.txt")
            val names = zf.getInputStream(manifestEntry).bufferedReader().use { reader ->
                reader.readLines().map(String::trim).filter(String::isNotEmpty)
            }
            names.forEach { name ->
                val asFile = File(name)
                if (name == "." || name == ".." || name.contains('/') || name.contains('\\') ||
                    asFile.isAbsolute || asFile.parent != null || name != asFile.name
                ) {
                    throw IllegalStateException("清单条目名不是纯文件名, 拒绝解包: $name")
                }
            }
            dir.mkdirs()
            var count = 0
            for (name in names) {
                val entry = zf.getEntry(name)
                    ?: throw IllegalStateException("清单条目 $name 不在工件内")
                val target = File(dir, name)
                FileOutputStream(target).use { out ->
                    zf.getInputStream(entry).use { it.copyTo(out) }
                }
                count++
            }
            return count
        }
    }
}
