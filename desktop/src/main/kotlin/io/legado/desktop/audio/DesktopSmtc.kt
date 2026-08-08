package io.legado.desktop.audio

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import io.legado.app.constant.AppLog
import io.legado.app.constant.Status
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.desktop.audio.DesktopSmtc.init
import io.legado.desktop.ui.tray.DesktopMediaTray
import io.legado.desktop.ui.tray.DesktopTaskbarMedia
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.Volatile

/**
 * Windows SMTC (SystemMediaTransportControls) 集成 —— native 桥版本。
 *
 * 实现在 desktop/src/main/cpp/smtc/smtc_bridge.c (MinGW 纯 C):
 * 官方 Win32 手动路径 ISystemMediaTransportControlsInterop::GetForWindow (mpv 范式),
 * 专用 STA 工作线程持有全部 WinRT 对象并跑消息泵, timeline 推送节流 ~5s。
 *
 * 会话必须绑主窗口 HWND: 绑到无任务栏按钮的隐藏窗口时, 悬停任务栏会让消费方
 * (Taskbar.View.dll) 抛 stowed exception 拖崩 explorer (A/B 实证)。
 *
 * 本文件只保留: 状态映射 (SmtcState → C 参数) + JNA Library 接口 + 回调命令分发。
 * 线程模型: 单线程 executor (COM 线程亲和), 回调经 C 侧函数指针回到 JVM 后投递 executor。
 */
internal object DesktopSmtc {

    /** SMTC 总开关。 */
    private const val ENABLE_SMTC = true

    /** JNA Callback: C 桥回传的播放控制命令 (cmd 用系统 Button 枚举直传, seek 用 LG_CMD_SEEK)。 */
    private const val LG_CMD_SEEK = 5
    private const val BUTTON_PLAY = 0
    private const val BUTTON_PAUSE = 1
    private const val BUTTON_STOP = 2
    private const val BUTTON_NEXT = 6
    private const val BUTTON_PREVIOUS = 7

    /** C 桥导出函数 (JNA Library 接口, 严格类型化; 见 smtc_bridge.c)。 */
    private interface LegadoSmtc : Library {
        /** 初始化; 返回 0 = 成功, 非 0 = 失败 (失败后调用方熔断, 不重试)。
         *  hwnd = 主窗口句柄, 会话归属于它; 传 null 则 C 侧自建兜底隐形窗口。 */
        fun lgsmtc_init(hwnd: Pointer?, cb: CmdCallback): Int

        /** 推送状态/元数据/进度 (timeline 节流在 C 侧)。 */
        fun lgsmtc_update(
            title: WString,
            artist: WString,
            album: WString,
            cover: WString,
            playing: Int,
            paused: Int,
            prevNext: Int,
            posMs: Long,
            durMs: Long,
            rate: Double,
        )

        /** 摘除事件 + 释放全部 COM 引用 + 销毁 dummy 窗口。 */
        fun lgsmtc_release()

        /** 诊断: 最近一次失败的真实 HRESULT (如 GetForWindow 的 -5 分支)。 */
        fun lgsmtc_last_hr(): Int

        /** 播放控制命令回调 (C 桥在系统线程触发, 本接口负责投递回 executor)。 */
        interface CmdCallback : Callback {
            fun onCommand(cmd: Int, arg: Long)
        }
    }

    // ==================== 状态 (仅 executor 线程访问) ====================

    @Volatile
    private var initialized = false

    @Volatile
    private var initFailed = false

    private var bridge: LegadoSmtc? = null

    /** 单线程 executor: 所有 C 桥调用固定在此线程 (COM 线程亲和, 与旧实现一致)。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-smtc").apply { isDaemon = true }
    }

    /** C 回调对象强引用 (JNA Callback 被 GC 即失效)。 */
    private val cmdCallback = object : LegadoSmtc.CmdCallback {
        override fun onCommand(cmd: Int, arg: Long) {
            // C 桥在系统线程触发: 只读参数后投递回 executor 执行命令 (对照旧实现)
            executor.execute {
                runCatching { dispatchButton(cmd, arg) }.onFailure {
                    AppLog.put("SMTC 按钮命令执行失败", it)
                }
            }
        }
    }

    // ==================== 公开 API (幂等, 全 runCatching) ====================

    /** 加载 native 桥并初始化 (幂等; 失败熔断不再重试)。 */
    fun init() {
        if (!Platform.isWindows()) return
        if (!ENABLE_SMTC) return
        executor.execute {
            if (initialized || initFailed) return@execute
            // 会话必须绑主窗口: 绑无任务栏按钮的隐藏窗口会让悬停任务栏拖崩 explorer
            // (A/B 实证)。主窗口未就绪时不初始化, 下次 update 再试。
            val hwnd = DesktopTaskbarMedia.mainWindowHandle() ?: return@execute
            runCatching {
                val lib = bridge ?: loadBridge().also { bridge = it }
                val rc = lib.lgsmtc_init(hwnd, cmdCallback)
                if (rc != 0) {
                    val hr = runCatching { lib.lgsmtc_last_hr() }.getOrDefault(0)
                    throw IllegalStateException(
                        "lgsmtc_init 失败 rc=$rc" +
                            if (hr != 0) " hr=0x${Integer.toHexString(hr)}" else ""
                    )
                }
                initialized = true
            }.onFailure {
                initFailed = true
                AppLog.put("SMTC 初始化失败", it)
            }
        }
    }

    /** 推送播放状态/元数据/进度/封面到系统媒体卡 (自动补一次 [init]; 失败熔断)。 */
    fun update(state: SmtcState) {
        if (!Platform.isWindows()) return
        if (!ENABLE_SMTC) return
        executor.execute {
            if (initFailed) return@execute
            val lib = bridge ?: loadBridge().also { bridge = it }
            if (!initialized) {
                // 同 init(): 主窗口未就绪就不初始化 (不置 initFailed, 留待下次推送重试)
                val hwnd = DesktopTaskbarMedia.mainWindowHandle() ?: return@execute
                val rc = runCatching {
                    lib.lgsmtc_init(hwnd, cmdCallback)
                }.getOrDefault(-1)
                if (rc != 0) {
                    initFailed = true
                    val hr = runCatching { lib.lgsmtc_last_hr() }.getOrDefault(0)
                    AppLog.put(
                        "SMTC 初始化失败 rc=$rc" +
                            if (hr != 0) " hr=0x${Integer.toHexString(hr)}" else ""
                    )
                    return@execute
                }
                initialized = true
            }
            runCatching {
                lib.lgsmtc_update(
                    WString(state.title),
                    WString(state.artist),
                    WString(state.albumArtist),
                    WString(state.coverUrl ?: ""),
                    if (state.isPlaying) 1 else 0,
                    if (state.isPaused) 1 else 0,
                    if (state.prevNextEnabled) 1 else 0,
                    state.positionMs,
                    state.durationMs,
                    state.playbackRate.toDouble(),
                )
            }.onFailure {
                AppLog.put("SMTC 状态更新失败", it)
            }
        }
    }

    /** 摘除事件回调 + 释放 (幂等; 下次 init/update 会重新初始化)。 */
    fun release() {
        if (!Platform.isWindows()) return
        if (!ENABLE_SMTC) return
        executor.execute {
            runCatching {
                bridge?.lgsmtc_release()
                initialized = false
            }.onFailure {
                AppLog.put("SMTC 释放失败", it)
            }
        }
    }

    // ==================== native 库加载 (照 quickjs Platform.kt 搜索链) ====================

    private fun loadBridge(): LegadoSmtc {
        val path = findNativeLibrary()
            ?: error("legado_smtc native library not found")
        return Native.load(path, LegadoSmtc::class.java)
    }

    /** 搜索链: 系统属性 → 环境变量 → 构建产物 (工作目录向上递归) → 打包资源目录。 */
    private fun findNativeLibrary(): String? {
        val name = if (Platform.isWindows()) "legado_smtc.dll" else "liblegado_smtc.so"
        System.getProperty("legado.smtc.lib")?.takeIf { File(it).exists() }?.let { return it }
        System.getenv("LEGADO_SMTC_LIB")?.takeIf { File(it).exists() }?.let { return it }
        // 构建产物: 从工作目录向上递归找 build/libs/smtc/native (照 quickjs Platform.kt 候选3)
        runCatching {
            var dir = File(System.getProperty("user.dir") ?: "")
            while (dir.parentFile != null) {
                val f = File(dir, "build/libs/smtc/native/$name")
                if (f.exists()) return f.absolutePath
                dir = dir.parentFile
            }
        }
        // 打包资源目录 (jpackage app/{packageName}/ 下, Main.kt 注入的 resources.dir)
        runCatching {
            val resDir = System.getProperty("compose.application.resources.dir")
            if (!resDir.isNullOrBlank()) {
                val f = File(resDir, name)
                if (f.exists()) return f.absolutePath
            }
        }
        return null
    }

    // ==================== 命令分发 (对照托盘 DesktopTaskbarMedia 的优先级: 音频优先, 否则朗读) ====================

    private fun dispatchButton(button: Int, arg: Long) {
        val audioActive = AudioPlayShared.status != Status.STOP
        val aloud = DesktopMediaTray.readAloud
        val aloudActive = aloud?.controller?.state?.value?.let {
            it == ReadAloudState.PLAYING || it == ReadAloudState.PAUSED
        } ?: false
        when (button) {
            BUTTON_PLAY -> when {
                audioActive -> when (AudioPlayShared.status) {
                    Status.PAUSE -> AudioPlayShared.resume()
                    Status.PLAY, Status.LOADING -> Unit
                    else -> AudioPlayShared.loadOrUpPlayUrl()
                }

                aloudActive -> if (aloud.controller.state.value == ReadAloudState.PAUSED) {
                    aloud.controller.resume()
                }
            }

            BUTTON_PAUSE -> when {
                audioActive -> if (AudioPlayShared.status == Status.PLAY) AudioPlayShared.pause()
                aloudActive -> if (aloud.controller.state.value == ReadAloudState.PLAYING) {
                    aloud.controller.pause()
                }
            }

            BUTTON_NEXT -> if (audioActive) AudioPlayShared.next()
            else if (aloudActive) aloud.controller.nextParagraph()

            BUTTON_PREVIOUS -> if (audioActive) AudioPlayShared.prev()
            else if (aloudActive) aloud.controller.prevParagraph()

            BUTTON_STOP -> if (audioActive) AudioPlayShared.stop()
            else if (aloudActive) aloud.controller.stop()

            LG_CMD_SEEK -> {
                // C 桥已把 100ns 换算为 ms; 仅音频可 seek
                if (audioActive && AudioPlayShared.status != Status.STOP) {
                    AudioPlayShared.adjustProgress(arg.toInt())
                }
            }
        }
    }
}

/**
 * SMTC 媒体卡状态快照 (由音频 / 朗读调用方各自填充简单值, 与托盘同源)。
 *
 * @param title 章节名 (Title)
 * @param artist 书名 (Artist)
 * @param albumArtist 作者 (AlbumArtist, 对照原版 MediaMetadata 的 ALBUM=作者)
 * @param isPlaying 播放中 (PlaybackStatus=Playing, 启用暂停按钮)
 * @param isPaused 暂停中 (PlaybackStatus=Paused)
 * @param prevNextEnabled 是否启用上一首/下一首按钮
 * @param positionMs 当前进度 (毫秒; <0 表示不推进度, 朗读无进度概念)
 * @param durationMs 总时长 (毫秒; <=0 表示不推进度)
 * @param playbackRate 倍速 (>0 时写 ISMTC2.PlaybackRate; 0 表示不写)
 * @param coverUrl 封面 URL (仅音频; 朗读无封面)
 */
data class SmtcState(
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val prevNextEnabled: Boolean = true,
    val positionMs: Long = -1L,
    val durationMs: Long = -1L,
    val playbackRate: Float = 1f,
    val coverUrl: String? = null,
)
