package io.legado.desktop.ui.tray

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.app.constant.Status
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.desktop.ui.tray.DesktopTaskbarMedia.attach
import java.awt.Component
import java.awt.Window
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
 * - 音频活跃: 上一章 / 播放暂停(暂停⇄继续) / 下一章 / 停止
 *   (tooltip 文案对照原版通知按钮 label: 音频用 pref_media_button_per_next 系列)
 * - 朗读活跃: 上一章 / 播放暂停(暂停⇄继续) / 下一章 / 停止
 *   (tooltip 文案对照原版朗读通知: previous_chapter / next_chapter)
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

    // THUMBBUTTON dwMask / dwFlags
    private const val THB_TOOLTIP = 0x4
    private const val THB_FLAGS = 0x8
    private const val THBF_DISABLED = 0x1
    private const val THBF_HIDDEN = 0x8

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
        // 按钮集: 音频链优先 (与托盘菜单一致); 文案对照原版通知按钮 label ——
        // 音频 prev/next 用 pref_media_button_per_next 系列, 朗读才用 previous/next_chapter
        val buttons = if (audioActive) {
            buildButtons(
                prevTip = str("pref_media_button_per_next", "上一章"),
                toggleTip = if (paused) str("resume", "继续") else str("pause", "暂停"),
                nextTip = str("pref_media_button_per_next_summary", "下一章"),
                stopTip = str("stop", "停止"),
            )
        } else if (aloudActive) {
            buildButtons(
                prevTip = str("previous_chapter", "上一章"),
                toggleTip = if (paused) str("resume", "继续") else str("pause", "暂停"),
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
        nextTip: String,
        stopTip: String,
    ): Memory {
        val mem = Memory(BUTTON_STRIDE * BTN_COUNT)
        writeButton(mem, 0, BTN_PREV, prevTip, 0)
        writeButton(mem, 1, BTN_TOGGLE, toggleTip, 0)
        writeButton(mem, 2, BTN_NEXT, nextTip, 0)
        writeButton(mem, 3, BTN_STOP, stopTip, 0)
        return mem
    }

    private fun hiddenButtons(): Memory {
        val mem = Memory(BUTTON_STRIDE * BTN_COUNT)
        for (i in 0 until BTN_COUNT) {
            writeButton(mem, i, i + 1, "", THBF_HIDDEN)
        }
        return mem
    }

    /**
     * THUMBBUTTON x64 布局: dwMask(4) iId(4) iBitmap(4) iIcon(4) szTip[260](520) dwFlags(4),
     * 共 540 → 8 字节对齐 544。szTip 为 WCHAR, 需 UTF-16LE (Memory.setChar 即按 UTF-16LE 写)。
     */
    private fun writeButton(mem: Memory, slot: Int, id: Int, tip: String, flags: Int) {
        val base = slot * BUTTON_STRIDE
        mem.setInt(base + 0, THB_TOOLTIP or THB_FLAGS) // dwMask
        mem.setInt(base + 4, id)
        mem.setInt(base + 8, 0) // iBitmap
        mem.setInt(base + 12, 0) // iIcon
        val tipBase = base + 16
        tip.take(259).forEachIndexed { i, c -> mem.setChar(tipBase + i * 2L, c) }
        mem.setInt(base + 536, flags) // dwFlags
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
                    msg == WM_HOTKEY -> onMediaKey(wParam.toLong().toInt())

                    else -> taskbarCreatedMsg?.let {
                        if (msg == it) refreshFromLastState()
                    }
                }
            } catch (e: Throwable) {
                AppLog.put("任务栏媒体消息处理异常 (msg=$msg)", e)
            }
            return WinDef.LRESULT(0)
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
        User32.INSTANCE.RegisterClassEx(wndClass)
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
        runCatching {
            vtbl(list, SLOT_THUMB_BAR_ADD_BUTTONS, hwnd, BTN_COUNT, buttons)
        }.onFailure { AppLog.put("ThumbBarAddButtons 失败", it) }
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

    /** THUMBBUTTON 结构 stride (x64: 540 → 8 字节对齐 544)。 */
    private val BUTTON_STRIDE = if (Native.POINTER_SIZE == 8) 544L else 540L

    private const val START_TIMEOUT_SECONDS = 10L
}
