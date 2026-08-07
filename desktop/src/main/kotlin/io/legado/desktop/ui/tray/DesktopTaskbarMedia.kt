package io.legado.desktop.ui.tray

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.app.constant.Status
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.desktop.ui.tray.DesktopTaskbarMedia.attach
import java.awt.Color
import java.awt.Component
import java.awt.RenderingHints
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows 任务栏媒体集成 (对照原版 Android 媒体通知 / MediaButtonReceiver 的行为与文案):
 *
 * # 缩略图工具栏按钮 (ITaskbarList3::ThumbBarAddButtons)
 * 鼠标悬停任务栏图标时, Windows 显示窗口缩略图 + 缩略图下方一排按钮 (即"带控件的音乐播放小卡片")。
 * 按钮集对照原版通知 action (与托盘菜单 DesktopMediaTray.addAudioItems/addReadAloudItems 同源):
 * 上一章 / 播放暂停(暂停⇄继续) / 下一章 / 停止。
 * - tooltip 文案: 不可用原版通知按钮的无障碍 label (pref_media_button_per_next 系列 =
 *   "媒体按钮•上一首|下一首", 是设置项标题), ThumbBar tooltip 直接可见会显示成乱码;
 *   统一用简短章节 label (previous_chapter / next_chapter, 同原版朗读通知)。
 * - 图标: 自绘白色 glyph → ImageList (comctl32) → ThumbBarSetImageList (vtable 槽 17),
 *   THUMBBUTTON.dwMask 含 THB_BITMAP + iBitmap 指向列表索引 (toggle 按播放/暂停切图标)。
 *   此前从未提供图标源, Windows 渲染成无图标的纯文字按钮。
 *
 * # 任务栏进度条 (SetProgressValue / SetProgressState)
 * 播放中显示进度 (对照原版通知进度条); 暂停 TBPF_PAUSED; 停止/空闲清除。
 *
 * # 全局媒体键 (RegisterHotKey VK_MEDIA_*)
 * 对照原版 MediaButtonReceiver.handleIntent 的分发链:
 * - prev/next/stop 键: 音频优先, 否则朗读 (prevParagraph/nextParagraph)
 * - 播放/暂停键: 朗读在跑 → 成对切换 (ReadAloud + AudioPlay 一起暂停/恢复, 原版 readAloud() 链);
 *   否则音频在跑 → 切音频
 *
 * # 实现要点
 * - 独立守护线程 + 隐藏消息窗口 (仿 WebView2Loop): 收 WM_HOTKEY / WM_TASKBARCREATED
 *   (explorer 重启后重挂按钮, 重放上次状态)
 * - 缩略图按钮点击是 WM_COMMAND 发给主窗口: 用 WH_GETMESSAGE 钩子拦截 (AWT 窗口过程不动,
 *   其余消息原样走 AWT, 避免子类化 AWT 窗口的风险; 钩子 hMod=null 只钩当前进程线程,
 *   AWT EDT 正在本进程)
 * - ITaskbarList3 无现成 JNA 绑定, 按 vtable 序号手写 COM 调用 (同 WindowsFileDialogs.vtbl)
 * - THUMBBUTTON 结构用 Memory 手动按 x64 布局写 (WCHAR szTip[260] 需 UTF-16LE, 不依赖
 *   JNA 编码注解)
 *
 * 非 Windows 平台 install 直接跳过, 所有入口幂等。
 */
internal object DesktopTaskbarMedia {

    // ==================== 常量 (Win32) ====================

    private const val CLSID_TASKBAR_LIST = "56FDF344-FD6D-11d0-958A-006097C9A090"
    private const val IID_TASKBAR_LIST3 = "ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf"
    private const val CLSCTX_INPROC_SERVER = 1

    // ITaskbarList3 vtable 槽位 (0-2 IUnknown, 3 HrInit, 4-7 ITaskbarList, 8 ITaskbarList2)
    private const val SLOT_HRINIT = 3
    private const val SLOT_SET_PROGRESS_VALUE = 9
    private const val SLOT_SET_PROGRESS_STATE = 10
    private const val SLOT_THUMB_BAR_ADD_BUTTONS = 15
    private const val SLOT_THUMB_BAR_UPDATE_BUTTONS = 16
    private const val SLOT_THUMB_BAR_SET_IMAGE_LIST = 17

    // THUMBBUTTON dwMask / dwFlags
    private const val THB_BITMAP = 0x1
    private const val THB_TOOLTIP = 0x4
    private const val THB_FLAGS = 0x8
    private const val THBF_DISABLED = 0x1
    private const val THBF_HIDDEN = 0x8

    // ImageList (comctl32) 标志与尺寸
    private const val ILC_COLOR32 = 0x20
    private const val ICON_SIZE = 32

    // 缩略图按钮图标索引 (ImageList 添加顺序)
    private const val ICON_PREV = 0
    private const val ICON_PLAY = 1
    private const val ICON_PAUSE = 2
    private const val ICON_NEXT = 3
    private const val ICON_STOP = 4

    // SetProgressState flags
    private const val TBPF_NOPROGRESS = 0x0
    private const val TBPF_NORMAL = 0x2
    private const val TBPF_PAUSED = 0x8

    // 缩略图按钮 id (点击时 WM_COMMAND 低 16 位)
    private const val BTN_PREV = 1
    private const val BTN_TOGGLE = 2
    private const val BTN_NEXT = 3
    private const val BTN_STOP = 4
    private const val BTN_COUNT = 4

    // 全局媒体键 (虚拟键值)
    private const val VK_MEDIA_NEXT_TRACK = 0xB0
    private const val VK_MEDIA_PREV_TRACK = 0xB1
    private const val VK_MEDIA_STOP = 0xB2
    private const val VK_MEDIA_PLAY_PAUSE = 0xB3

    private const val WM_TASKBARCREATED_MSG = "TaskbarCreated"
    private const val WINDOW_CLASS = "LegadoTaskbarMedia"
    private const val WH_GETMESSAGE = 3
    private const val WM_COMMAND = 0x0111
    private const val WM_HOTKEY = 0x0312
    private const val WM_QUIT = 0x0012

    // ==================== 状态 ====================

    /** 主窗口 HWND (由 [attach] 注入; 窗口重建时更新)。 */
    @Volatile
    private var mainHwnd: WinDef.HWND? = null

    /** 按钮已挂载标志 (首次 Add, 之后 Update)。 */
    @Volatile
    private var buttonsAdded = false

    /** 最近一次状态 (TaskbarCreated 后重放)。 */
    @Volatile
    private var lastAudioStatus: Int = Status.STOP

    @Volatile
    private var lastAloudState: ReadAloudState? = null

    @Volatile
    private var lastProgressMs: Int = 0

    @Volatile
    private var lastDurationMs: Int = 0

    /** 缩略图按钮图标列表 (comctl32 ImageList, 懒创建; 卸载时销毁)。 */
    @Volatile
    private var imageList: Pointer? = null

    private val commandExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legado-taskbar-cmd").apply { isDaemon = true }
    }

    // ==================== 生命周期 ====================

    /** 启动消息线程 + 注册全局媒体键 + 装 WH_GETMESSAGE 钩子 (幂等; 非 Windows 跳过)。 */
    @Synchronized
    fun install() {
        if (pumpWindow != null) return
        if (!Platform.isWindows()) return
        val ready = CountDownLatch(1)
        val thread = Thread({ runLoop(ready) }, "legado-taskbar-media")
        thread.isDaemon = true
        thread.start()
        if (!ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            AppLog.put("任务栏媒体消息泵启动超时, 任务栏集成不可用")
        }
    }

    /**
     * 主窗口可用时注入 (Main.kt 在 Window 组装后调用), 挂缩略图按钮 + DWM 卡片。
     * 窗口重建 (如全屏切换) 时重新挂。
     */
    fun attach(window: Window) {
        if (!Platform.isWindows()) return
        val hwnd = hwndOf(window) ?: return
        if (hwnd.pointer == mainHwnd?.pointer) return
        mainHwnd = hwnd
        buttonsAdded = false
        DesktopTaskbarDwm.attach(window)
        refreshFromLastState()
    }

    fun uninstall() {
        mainHwnd = null
        buttonsAdded = false
        DesktopTaskbarDwm.uninstall()
        // 释放 COM 实例 (IUnknown::Release, vtable slot 2)
        cachedTaskbarList?.let { runCatching { vtbl(it, 2) } }
        cachedTaskbarList = null
        // 销毁按钮图标列表
        imageList?.let { runCatching { ComCtl32.INSTANCE.ImageList_Destroy(it) } }
        imageList = null
        val pump = pumpWindow
        pumpWindow = null
        if (pump != null) {
            User32.INSTANCE.PostMessage(pump, WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        }
    }

    // ==================== 状态刷新 ====================

    /**
     * 状态变化时刷新任务栏 (按钮集/禁用态/tooltip + 进度条)。
     * 由 DesktopMediaTray 在状态事件时调用, 参数与托盘菜单同源 (对照原版通知刷新时机)。
     */
    fun update(
        audioStatus: Int,
        aloud: ReadAloudTrayBinding?,
        progressMs: Int,
        durationMs: Int,
    ) {
        lastAudioStatus = audioStatus
        lastAloudState = aloud?.controller?.state?.value
        lastProgressMs = progressMs
        lastDurationMs = durationMs
        // DWM 悬停卡片 (封面/歌名/章节/进度, 活跃时启用 iconic)
        DesktopTaskbarDwm.update(audioStatus, aloud, progressMs, durationMs)
        refreshFromLastState()
    }

    private fun refreshFromLastState() {
        val hwnd = mainHwnd ?: return
        val audioStatus = lastAudioStatus
        val audioActive = audioStatus != Status.STOP
        val aloudState = lastAloudState
        val aloudActive =
            aloudState == ReadAloudState.PLAYING || aloudState == ReadAloudState.PAUSED
        val paused = when {
            audioActive -> audioStatus == Status.PAUSE
            aloudActive -> aloudState == ReadAloudState.PAUSED
            else -> false
        }
        // 进度条: 对照原版通知进度 (暂停 TBPF_PAUSED, 无任务清除)
        when {
            audioActive -> setProgress(
                if (paused) TBPF_PAUSED else TBPF_NORMAL,
                lastProgressMs.toLong(),
                lastDurationMs.coerceAtLeast(1).toLong(),
            )

            aloudActive -> setProgress(if (paused) TBPF_PAUSED else TBPF_NORMAL, 0L, 1L)

            else -> setProgress(TBPF_NOPROGRESS, 0L, 0L)
        }
        // 按钮集: 音频/朗读统一用简短章节 label (ThumbBar tooltip 直接可见,
        // 不能用原版通知的无障碍 label —— pref_media_button_per_next 系列是设置项标题,
        // 如“媒体按钮•上一首|下一首”, 直接显示即乱码)
        val buttons = if (audioActive || aloudActive) {
            buildButtons(
                prevTip = str("previous_chapter", "上一章"),
                toggleTip = if (paused) str("resume", "继续") else str("pause", "暂停"),
                toggleIcon = if (paused) ICON_PLAY else ICON_PAUSE,
                nextTip = str("next_chapter", "下一章"),
                stopTip = str("stop", "停止"),
            )
        } else {
            null
        }
        if (buttons != null) {
            if (buttonsAdded) updateButtons(hwnd, buttons) else {
                addButtons(hwnd, buttons)
                buttonsAdded = true
            }
        } else {
            // 无后台任务: 隐藏按钮 (进度清除已做)
            if (buttonsAdded) updateButtons(hwnd, hiddenButtons())
            buttonsAdded = false
        }
    }

    private fun buildButtons(
        prevTip: String,
        toggleTip: String,
        toggleIcon: Int,
        nextTip: String,
        stopTip: String,
    ): Memory {
        val mem = Memory(BUTTON_STRIDE * BTN_COUNT)
        writeButton(mem, 0, BTN_PREV, prevTip, 0, ICON_PREV)
        writeButton(mem, 1, BTN_TOGGLE, toggleTip, 0, toggleIcon)
        writeButton(mem, 2, BTN_NEXT, nextTip, 0, ICON_NEXT)
        writeButton(mem, 3, BTN_STOP, stopTip, 0, ICON_STOP)
        return mem
    }

    private fun hiddenButtons(): Memory {
        val mem = Memory(BUTTON_STRIDE * BTN_COUNT)
        for (i in 0 until BTN_COUNT) {
            writeButton(mem, i, i + 1, "", THBF_HIDDEN, ICON_PREV)
        }
        return mem
    }

    /**
     * THUMBBUTTON x64 布局 (commctrl.h): dwMask(4) iId(4) iBitmap(4) [对齐填充 12..16]
     * hIcon(8) szTip[260](520, 24..544) dwFlags(4, 544..548) → 结构 552 字节。
     * szTip 为 WCHAR, 需 UTF-16LE (Memory.setChar 即按 UTF-16LE 写)。
     * dwMask 含 THB_BITMAP: iBitmap 指向 ThumbBarSetImageList 设置列表中的索引。
     * 2026-08 修正: 此前按 32 位布局写 (hIcon=4 → szTip@16/dwFlags@536/stride 544),
     * x64 下 dwFlags 落在 szTip 内、按钮 2+ 整体错位 (缩略图按钮行为异常)。
     */
    private fun writeButton(
        mem: Memory,
        slot: Int,
        id: Int,
        tip: String,
        flags: Int,
        iconIndex: Int
    ) {
        val base = slot * BUTTON_STRIDE
        mem.setInt(base + 0, THB_BITMAP or THB_TOOLTIP or THB_FLAGS) // dwMask
        mem.setInt(base + 4, id)
        mem.setInt(base + 8, iconIndex) // iBitmap
        mem.setLong(base + 16, 0) // hIcon (x64 指针, 12..16 为对齐填充)
        val tipBase = base + 24
        tip.take(259).forEachIndexed { i, c -> mem.setChar(tipBase + i * 2L, c) }
        mem.setInt(base + 544, flags) // dwFlags
    }

    // ==================== 按钮点击 / 媒体键 (动作对照原版) ====================

    /** 缩略图按钮点击 (WM_COMMAND): 对照原版通知 action 的行为。 */
    private fun onThumbButton(id: Int) {
        val audioActive = AudioPlayShared.status != Status.STOP
        val aloud = DesktopMediaTray.readAloud
        val aloudActive = aloud?.controller?.state?.value?.let {
            it == ReadAloudState.PLAYING || it == ReadAloudState.PAUSED
        } ?: false
        runCommand {
            when (id) {
                BTN_PREV -> if (audioActive) AudioPlayShared.prev()
                else if (aloudActive) aloud!!.controller.prevParagraph()

                BTN_TOGGLE -> if (audioActive) {
                    // 对照原版通知 action: PLAY→pause / PAUSE→resume / 其他→loadOrUpPlayUrl
                    when (AudioPlayShared.status) {
                        Status.PLAY -> AudioPlayShared.pause()
                        Status.PAUSE -> AudioPlayShared.resume()
                        else -> AudioPlayShared.loadOrUpPlayUrl()
                    }
                } else if (aloudActive) {
                    val c = aloud!!.controller
                    if (c.state.value == ReadAloudState.PAUSED) c.resume() else c.pause()
                }

                BTN_NEXT -> if (audioActive) AudioPlayShared.next()
                else if (aloudActive) aloud!!.controller.nextParagraph()

                BTN_STOP -> if (audioActive) AudioPlayShared.stop()
                else if (aloudActive) aloud!!.controller.stop()
            }
        }
    }

    /**
     * 全局媒体键 (WM_HOTKEY): 对照原版 MediaButtonReceiver.handleIntent 的分发链。
     * 优先级: prev/next/stop 音频优先; 播放/暂停键朗读优先且与音频成对切换 (原版 readAloud() 链)。
     */
    private fun onMediaKey(vk: Int) {
        val audioActive = AudioPlayShared.status != Status.STOP
        val aloud = DesktopMediaTray.readAloud
        val aloudActive = aloud?.controller?.state?.value?.let {
            it == ReadAloudState.PLAYING || it == ReadAloudState.PAUSED
        } ?: false
        runCommand {
            when (vk) {
                VK_MEDIA_PLAY_PAUSE -> {
                    // 原版: 朗读在跑 → 成对切换 (ReadAloud.pause + AudioPlay.pause / 两者 resume);
                    // 否则音频在跑 → 切音频; 再否则不处理 (原版还走前台 Activity/兜底朗读, 桌面端无)
                    if (aloudActive) {
                        val c = aloud!!.controller
                        if (c.state.value == ReadAloudState.PAUSED) {
                            c.resume()
                            if (audioActive) AudioPlayShared.resume()
                        } else {
                            c.pause()
                            if (audioActive) AudioPlayShared.pause()
                        }
                    } else if (audioActive) {
                        when (AudioPlayShared.status) {
                            Status.PLAY -> AudioPlayShared.pause()
                            Status.PAUSE -> AudioPlayShared.resume()
                            else -> AudioPlayShared.loadOrUpPlayUrl()
                        }
                    }
                }

                VK_MEDIA_NEXT_TRACK -> if (audioActive) AudioPlayShared.next()
                else if (aloudActive) aloud!!.controller.nextParagraph()

                VK_MEDIA_PREV_TRACK -> if (audioActive) AudioPlayShared.prev()
                else if (aloudActive) aloud!!.controller.prevParagraph()

                VK_MEDIA_STOP -> if (audioActive) AudioPlayShared.stop()
                else if (aloudActive) aloud!!.controller.stop()
            }
        }
    }

    /** 命令切出消息线程执行 (播放命令内部会落库/起协程, 不该压在窗口过程/消息循环上)。 */
    private fun runCommand(action: () -> Unit) {
        commandExecutor.execute {
            runCatching { action() }.onFailure { AppLog.put("任务栏命令执行失败", it) }
        }
    }

    // ==================== 消息线程 (隐藏窗口 + RegisterHotKey + WH_GETMESSAGE 钩子) ====================

    @Volatile
    private var pumpWindow: WinDef.HWND? = null

    @Volatile
    private var taskbarCreatedMsg: Int? = null

    private val mediaKeysRegistered = AtomicBoolean(false)

    /** WH_GETMESSAGE 钩子回调 (运行在本消息线程; JNA 回调须强引用)。
     * 注意: WinUser.HOOKPROC 是空接口, JNA 反射取实现类唯一公共方法, 不能写 override。 */
    private val getMsgProc: WinUser.HOOKPROC =
        object : WinUser.HOOKPROC, com.sun.jna.CallbackProxy {
            override fun callback(args: Array<Any?>): Any? {
                val nCode = args.getOrNull(0) as? Int ?: 0
                val wParam = args.getOrNull(1) as? WinDef.WPARAM ?: WinDef.WPARAM(0)
                val lParam = args.getOrNull(2) as? WinDef.LPARAM ?: WinDef.LPARAM(0)
                try {
                    if (nCode >= 0 && wParam.toLong() != 0L) {
                        // lParam 指向 MSG 结构 (hwnd, message, wParam, lParam, ...)
                        val msg = Pointer(lParam.toLong())
                        if (msg.getInt(Native.POINTER_SIZE * 1L) == WM_COMMAND) {
                            val main = mainHwnd
                            if (main != null && msg.getPointer(0) == main.pointer) {
                                val id = msg.getInt(Native.POINTER_SIZE * 2L) and 0xFFFF
                                if (id in BTN_PREV..BTN_STOP) onThumbButton(id)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    AppLog.put("任务栏消息钩子异常", e)
                }
                return User32.INSTANCE.CallNextHookEx(null, nCode, wParam, lParam)
            }

            override fun getParameterTypes(): Array<Class<*>> =
                arrayOf(
                    Int::class.javaPrimitiveType!!,
                    WinDef.WPARAM::class.java,
                    WinDef.LPARAM::class.java
                )

            override fun getReturnType(): Class<*> = WinDef.LRESULT::class.java
        }

    private val pumpProc = object : WinUser.WindowProc {
        override fun callback(
            hwnd: WinDef.HWND,
            msg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinDef.LRESULT {
            try {
                when {
                    msg == WM_HOTKEY -> {
                        onMediaKey(wParam.toLong().toInt())
                        return WinDef.LRESULT(0)
                    }

                    taskbarCreatedMsg != null && msg == taskbarCreatedMsg -> {
                        refreshFromLastState()
                        return WinDef.LRESULT(0)
                    }
                }
            } catch (e: Throwable) {
                AppLog.put("任务栏媒体消息处理异常 (msg=$msg)", e)
            }
            // 其余消息走 DefWindowProc (同 WebView2Loop.windowProc)。此前一律返回 0,
            // WM_NCCREATE 返回 FALSE 会让 CreateWindowEx 中止并返回 NULL (GetLastError 常为 0,
            // 即此前 "CreateWindowEx 失败 (err=0)" 的根因), 隐藏窗口根本建不出来。
            return User32.INSTANCE.DefWindowProc(hwnd, msg, wParam, lParam)
        }
    }

    private fun runLoop(ready: CountDownLatch) {
        val created = runCatching {
            registerWindowClass()
            val hwnd = User32.INSTANCE.CreateWindowEx(
                0,
                WINDOW_CLASS,
                "legado-taskbar-media",
                WinUser.WS_POPUP,
                -32000, -32000, 1, 1,
                null, null,
                Kernel32.INSTANCE.GetModuleHandle(null),
                null,
            ) ?: error("CreateWindowEx 失败 (err=${Native.getLastError()})")
            pumpWindow = hwnd
            // explorer 重启后任务栏按钮丢失, 监听 TaskbarCreated 重挂
            taskbarCreatedMsg = User32.INSTANCE.RegisterWindowMessage(WM_TASKBARCREATED_MSG)
            registerMediaKeys(hwnd)
            installGetMessageHook()
        }.onFailure {
            AppLog.put("任务栏媒体消息泵启动失败", it)
        }.isSuccess
        ready.countDown()
        if (!created) return
        val msg = WinUser.MSG()
        while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            User32.INSTANCE.TranslateMessage(msg)
            User32.INSTANCE.DispatchMessage(msg)
        }
        // 线程退出: 摘钩子 (防止 AWT 线程消息继续触发已停止的泵)
        runCatching {
            User32.INSTANCE.UnhookWindowsHookEx(hookHandle)
        }.onFailure { AppLog.put("任务栏消息钩子摘除失败", it) }
        hookHandle = null
    }

    private fun registerWindowClass() {
        val wndClass = WinUser.WNDCLASSEX()
        wndClass.cbSize = wndClass.size()
        wndClass.lpszClassName = WINDOW_CLASS
        wndClass.lpfnWndProc = pumpProc
        wndClass.hInstance = Kernel32.INSTANCE.GetModuleHandle(null)
        val atom = User32.INSTANCE.RegisterClassEx(wndClass)
        // 重复注册 (同进程二次 install) 返回 0 + ERROR_CLASS_ALREADY_EXISTS, 无害;
        // 其他失败会让 CreateWindowEx 找不到类 (同样表现为 err=0), 必须留痕便于诊断
        // ATOM 是 IntegerType 子类, 不能与 Int 直接比较; 用等值构造比较 (equals 按 value 判等, 等价 intValue() == 0)
        if (atom == WinDef.ATOM(0) && Native.getLastError() != WinError.ERROR_CLASS_ALREADY_EXISTS) {
            AppLog.put("注册任务栏媒体窗口类失败 (err=${Native.getLastError()})")
        }
    }

    /** 注册全局媒体键 (对照原版 MediaButtonReceiver 的媒体按钮接管)。 */
    private fun registerMediaKeys(hwnd: WinDef.HWND) {
        if (!mediaKeysRegistered.compareAndSet(false, true)) return
        val keys = mapOf(
            VK_MEDIA_PLAY_PAUSE to 1,
            VK_MEDIA_NEXT_TRACK to 2,
            VK_MEDIA_PREV_TRACK to 3,
            VK_MEDIA_STOP to 4,
        )
        keys.forEach { (vk, id) ->
            runCatching { User32.INSTANCE.RegisterHotKey(hwnd, id, 0, vk) }
                .onFailure { AppLog.put("注册全局媒体键失败 vk=$vk", it) }
        }
    }

    @Volatile
    private var hookHandle: WinUser.HHOOK? = null

    /**
     * WH_GETMESSAGE 钩子: 拦截发给主窗口的 WM_COMMAND (缩略图按钮点击)。
     * hMod=null + dwThreadId=0 → 钩住当前进程所有线程 (AWT EDT 在进程内), 回调在本线程执行。
     */
    private fun installGetMessageHook() {
        runCatching {
            val h = User32.INSTANCE.SetWindowsHookEx(
                WH_GETMESSAGE,
                getMsgProc,
                null,
                0,
            ) ?: error("SetWindowsHookEx 失败 (err=${Native.getLastError()})")
            hookHandle = h
        }.onFailure {
            AppLog.put("安装任务栏消息钩子失败", it)
        }
    }

    // ==================== ITaskbarList3 (vtable 手写调用, 同 WindowsFileDialogs.vtbl) ====================

    /** 进程级缓存的 ITaskbarList3 实例 (CoCreateInstance 开销大, 状态刷新频繁)。 */
    @Volatile
    private var cachedTaskbarList: Pointer? = null

    private fun taskbarList(): Pointer? {
        cachedTaskbarList?.let { return it }
        synchronized(this) {
            cachedTaskbarList?.let { return it }
            val p = PointerByReference()
            val hr = runCatching {
                Ole32.INSTANCE.CoCreateInstance(
                    Guid.GUID(CLSID_TASKBAR_LIST),
                    Pointer.NULL,
                    CLSCTX_INPROC_SERVER,
                    Guid.GUID(IID_TASKBAR_LIST3),
                    p,
                )
            }.getOrDefault(-1)
            if (hr.toInt() != 0 || p.value == null) return null
            val punk = p.value
            // HrInit (slot 3): 必须在任何其他调用前
            vtbl(punk, SLOT_HRINIT)
            cachedTaskbarList = punk
            return punk
        }
    }

    /** 按 vtable 序号调用 COM 方法 (同 WindowsFileDialogs.vtbl)。 */
    private fun vtbl(target: Pointer, index: Int, vararg args: Any?): Int {
        val vtable = target.getPointer(0)
        val method = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(method, Function.ALT_CONVENTION)
            .invokeInt(arrayOf(target, *args))
    }

    private fun addButtons(hwnd: WinDef.HWND, buttons: Memory) {
        val list = taskbarList() ?: return
        // 图标列表就绪后设置 (THB_BITMAP/iBitmap 依赖 ThumbBarSetImageList)
        getOrCreateImageList()?.let { setImageList(hwnd, it) }
        runCatching {
            vtbl(list, SLOT_THUMB_BAR_ADD_BUTTONS, hwnd, BTN_COUNT, buttons)
        }.onFailure { AppLog.put("ThumbBarAddButtons 失败", it) }
    }

    /** ThumbBarSetImageList (vtable 槽 17): 提供按钮图标列表 (无图标则按钮无图)。 */
    private fun setImageList(hwnd: WinDef.HWND, himl: Pointer) {
        val list = taskbarList() ?: return
        runCatching {
            vtbl(list, SLOT_THUMB_BAR_SET_IMAGE_LIST, hwnd, himl)
        }.onFailure { AppLog.put("ThumbBarSetImageList 失败", it) }
    }

    // ==================== 按钮图标 (自绘 glyph → ImageList) ====================

    /** 按钮 glyph 形状 (白色, 对照原版通知按钮图标语义)。 */
    private enum class ThumbGlyph { PREV, PLAY, PAUSE, NEXT, STOP }

    /** 懒创建按钮图标列表: 32bpp ImageList, 顺序 [PREV, PLAY, PAUSE, NEXT, STOP]。 */
    private fun getOrCreateImageList(): Pointer? {
        imageList?.let { return it }
        synchronized(this) {
            imageList?.let { return it }
            val himl = ComCtl32.INSTANCE.ImageList_Create(ICON_SIZE, ICON_SIZE, ILC_COLOR32, 5, 0)
                ?: return null
            val built = runCatching {
                val hbms = mutableListOf<WinDef.HBITMAP>()
                try {
                    for (glyph in ThumbGlyph.entries) {
                        val hbm = DesktopTaskbarDwm.toHBitmap(drawGlyph(ICON_SIZE, glyph))
                            ?: error("toHBitmap 失败")
                        hbms += hbm
                        val idx = ComCtl32.INSTANCE.ImageList_Add(himl, hbm, null)
                        if (idx < 0) error("ImageList_Add 失败 idx=$idx")
                    }
                } finally {
                    hbms.forEach { runCatching { GDI32.INSTANCE.DeleteObject(it) } }
                }
            }
            if (built.isFailure) {
                AppLog.put("任务栏按钮图标构建失败", built.exceptionOrNull())
                runCatching { ComCtl32.INSTANCE.ImageList_Destroy(himl) }
                return null
            }
            imageList = himl
            return himl
        }
    }

    /** 绘制白色 glyph (透明底, 32bpp; 仿 DesktopTaskbarDwm.drawStatusIcon 自绘惯例)。 */
    private fun drawGlyph(size: Int, glyph: ThumbGlyph): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            )
            g.color = Color.WHITE
            val s = size.toFloat()
            when (glyph) {
                ThumbGlyph.PREV -> {
                    // 左侧竖条 + 右侧左指三角 (skip previous)
                    val barW = (s * 0.14f).toInt().coerceAtLeast(2)
                    val barX = (s * 0.16f).toInt()
                    g.fillRoundRect(barX, (s * 0.25f).toInt(), barW, (s * 0.5f).toInt(), barW, barW)
                    val triX = (s * 0.42f).toInt()
                    val xs =
                        intArrayOf(triX + (s * 0.34f).toInt(), triX, triX + (s * 0.34f).toInt())
                    val ys = intArrayOf((s * 0.2f).toInt(), (s * 0.5f).toInt(), (s * 0.8f).toInt())
                    g.fillPolygon(xs, ys, 3)
                }

                ThumbGlyph.PLAY -> {
                    // 右指实心三角
                    val xs =
                        intArrayOf((s * 0.32f).toInt(), (s * 0.78f).toInt(), (s * 0.32f).toInt())
                    val ys =
                        intArrayOf((s * 0.18f).toInt(), (s * 0.5f).toInt(), (s * 0.82f).toInt())
                    g.fillPolygon(xs, ys, 3)
                }

                ThumbGlyph.PAUSE -> {
                    // 双竖条
                    val barW = (s * 0.16f).toInt().coerceAtLeast(2)
                    val gap = (s * 0.14f).toInt()
                    val x1 = (s * 0.26f).toInt()
                    val y = (s * 0.22f).toInt()
                    val h = (s * 0.56f).toInt()
                    g.fillRoundRect(x1, y, barW, h, barW, barW)
                    g.fillRoundRect(x1 + barW + gap, y, barW, h, barW, barW)
                }

                ThumbGlyph.NEXT -> {
                    // 右侧竖条 + 左方右指三角 (skip next)
                    val triX = (s * 0.26f).toInt()
                    val xs = intArrayOf(triX, triX + (s * 0.34f).toInt(), triX)
                    val ys = intArrayOf((s * 0.2f).toInt(), (s * 0.5f).toInt(), (s * 0.8f).toInt())
                    g.fillPolygon(xs, ys, 3)
                    val barW = (s * 0.14f).toInt().coerceAtLeast(2)
                    val barX = (s * 0.70f).toInt()
                    g.fillRoundRect(barX, (s * 0.25f).toInt(), barW, (s * 0.5f).toInt(), barW, barW)
                }

                ThumbGlyph.STOP -> {
                    // 实心方块
                    val x = (s * 0.28f).toInt()
                    val w = (s * 0.44f).toInt()
                    g.fillRoundRect(x, x, w, w, (s * 0.08f).toInt(), (s * 0.08f).toInt())
                }
            }
        } finally {
            g.dispose()
        }
        return img
    }

    // ==================== comctl32 (ImageList) 最小绑定 ====================

    /** ImageList 函数 (老版 comctl32 仍导出; HIMAGELIST 用 Pointer 表示)。 */
    private interface ComCtl32 : Library {
        fun ImageList_Create(cx: Int, cy: Int, flags: Int, cInitial: Int, cGrow: Int): Pointer?
        fun ImageList_Add(himl: Pointer, hbmImage: WinDef.HBITMAP, hbmMask: WinDef.HBITMAP?): Int
        fun ImageList_Destroy(himl: Pointer): Boolean

        companion object {
            val INSTANCE: ComCtl32 = Native.load("comctl32", ComCtl32::class.java)
        }
    }

    private fun updateButtons(hwnd: WinDef.HWND, buttons: Memory) {
        val list = taskbarList() ?: return
        runCatching {
            vtbl(list, SLOT_THUMB_BAR_UPDATE_BUTTONS, hwnd, BTN_COUNT, buttons)
        }.onFailure { AppLog.put("ThumbBarUpdateButtons 失败", it) }
    }

    private fun setProgress(state: Int, completed: Long, total: Long) {
        val list = taskbarList() ?: return
        val hwnd = mainHwnd ?: return
        runCatching {
            vtbl(list, SLOT_SET_PROGRESS_STATE, hwnd, state)
            if (state != TBPF_NOPROGRESS) {
                vtbl(list, SLOT_SET_PROGRESS_VALUE, hwnd, completed, total)
            }
        }.onFailure { AppLog.put("任务栏进度更新失败", it) }
    }

    // ==================== HWND 获取 ====================

    /** AWT Window → 原生 HWND (反射 peer.getHWnd, 同 mpv 桥接做法; peer 字段是 package-private)。 */
    private fun hwndOf(window: Window): WinDef.HWND? {
        return runCatching {
            val peerField = Component::class.java.getDeclaredField("peer")
            peerField.isAccessible = true
            val peer = peerField.get(window) ?: return null
            val method = peer.javaClass.methods.firstOrNull { it.name == "getHWnd" }
                ?: peer.javaClass.declaredMethods.firstOrNull { it.name == "getHWnd" }
                ?: return null
            method.isAccessible = true
            val value = method.invoke(peer) as? Long ?: return null
            if (value == 0L) null else WinDef.HWND(Pointer(value))
        }.getOrNull()
    }

    private fun str(key: String, fallback: String): String =
        jvmGetString(key).takeIf { it != key } ?: fallback

    /** THUMBBUTTON 结构 stride (仅支持 x64 = 552; writeButton 偏移与任务栏链路其余部分同为 x64 假设)。 */
    private val BUTTON_STRIDE = 552L

    private const val START_TIMEOUT_SECONDS = 10L
}
