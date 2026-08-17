package io.legado.desktop.ui.tray

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import io.legado.app.constant.AppLog
import io.legado.app.constant.Status
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.desktop.help.win.createHiddenMessageWindow
import io.legado.desktop.help.win.registerMessageWindowClass
import io.legado.desktop.ui.DesktopWindowChromeNative
import io.legado.desktop.ui.hwndOrNull
import io.legado.desktop.ui.tray.DesktopTaskbarMedia.attach
import io.legado.desktop.ui.tray.DesktopTaskbarMedia.comTasks
import io.legado.desktop.ui.tray.DesktopTaskbarMedia.runOnPump
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

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
 * - 图标: Segoe Fluent Icons 字形 → 32bpp 预乘 DIB + 1bpp 掩码 → ImageList (comctl32)
 *   → ThumbBarSetImageList (vtable 槽 17), THUMBBUTTON.dwMask 含 THB_BITMAP + iBitmap 为列表索引。
 *
 * # 线程模型 (关键)
 * ITaskbarList3 是 Apartment-threaded: 所有调用必须在自建的 STA 泵线程
 * (legado-taskbar-media, runLoop 里 CoInitializeEx(APARTMENTTHREADED))。
 * 放在 AWT EDT 上会拿到跨套间代理, ThumbBarSetImageList 传裸 HIMAGELIST 无法编组 → E_FAIL,
 * 而 AddButtons 只传结构体可编组 → S_OK, 表现为"按钮有、图标无"。
 * 故经 [runOnPump] 投递 (WM_LG_RUN_COM + 任务队列); DWM 卡片走 GDI/窗口过程, 留在 EDT。
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
 *   (explorer 重启广播, 只清挂载标志)
 * - 缩略图按钮点击 (WM_COMMAND) 与本窗口任务栏按钮建立 (TaskbarButtonCreated) 都是发给主窗口的
 *   消息: 主窗口子类化由 native 桥 (wndchrome) 独占, 经 [DesktopWindowChromeNative.addMessageHandler]
 *   转发上来 (S3: 三层子类化收成一层; WH_GETMESSAGE 钩子因消息泵时序不可靠已弃用)。
 *   TaskbarButtonCreated 的号是运行期分配的, 由 lgchrome_add_hook_message 追加进白名单
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
    private const val THBF_HIDDEN = 0x8

    /** ThumbBar 图标开关 (关闭则按钮只有 tooltip, 无图标)。 */
    private const val ENABLE_THUMBBAR_ICONS = true

    // ImageList (comctl32): ILC_COLOR32|ILC_MASK + 32bpp 预乘位图 + 1bpp 掩码
    private const val ILC_MASK = 0x1
    private const val ILC_COLOR32 = 0x20

    /**
     * 按钮图标尺寸。Win11 任务栏 thumbbar 按 24px 渲染 (100% DPI),
     * 给 16px 会被放大导致字形发虚 (demo 逐尺寸实测: 16 糊, 24 清晰)。
     */
    private const val ICON_SIZE = 24

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

    // 两个消息各有分工, 都要监听:
    // - "TaskbarCreated": explorer 重启广播 (HWND_BROADCAST, 所有顶层窗口都收) → 隐藏泵窗口收
    // - "TaskbarButtonCreated": 本窗口的任务栏按钮已建立 → 只发给主窗口, 经 native 桥转发上来
    private const val WM_TASKBARCREATED_MSG = "TaskbarCreated"
    private const val WM_TASKBARBUTTONCREATED_MSG = "TaskbarButtonCreated"
    private const val WINDOW_CLASS = "LegadoTaskbarMedia"
    private const val WM_COMMAND = 0x0111
    private const val WM_HOTKEY = 0x0312
    private const val WM_QUIT = 0x0012

    /** 自定义消息: 唤醒泵线程执行 [comTasks] 里排队的 COM 操作。 */
    private const val WM_LG_RUN_COM = 0x0400 + 0x51   // WM_APP+0x51


    // ==================== 状态 ====================

    /** 主窗口 HWND (由 [attach] 注入; 窗口重建时更新)。 */
    @Volatile
    private var mainHwnd: WinDef.HWND? = null

    /** 按钮已挂载标志 (首次 Add, 之后 Update)。 */
    @Volatile
    private var buttonsAdded = false

    /**
     * image list 是否已成功挂到本窗口。
     * 只有为 true 时按钮才可带 THB_BITMAP —— 否则任务栏按 iBitmap 取图取不到,
     * Taskbar.View.dll 在 await 处抛 stowed exception (0xc000027b) 拖崩 explorer。
     */
    @Volatile
    private var iconsAttached = false

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

    /** 启动消息线程 (隐藏窗口 + 全局媒体键 + TaskbarCreated) (幂等; 非 Windows 跳过)。 */
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
        val hwnd = window.hwndOrNull() ?: return
        if (hwnd.pointer == mainHwnd?.pointer) return
        mainHwnd = hwnd
        buttonsAdded = false
        iconsAttached = false   // image list 是按窗口挂的, 换窗口必须重挂
        // SMTC 会话绑的是旧 HWND, 释放后下次推送会用新窗口重新初始化
        io.legado.desktop.audio.DesktopSmtc.release()
        DesktopTaskbarDwm.attach(window)
        // WM_COMMAND (缩略图按钮点击) 由 native 桥转发; 与 DWM 卡片各收自己的消息, 无顺序依赖
        if (!hooked) hooked = DesktopWindowChromeNative.addMessageHandler(messageHandler)
        // 桥已挂上才有意义: TaskbarButtonCreated 的号是运行期分配的, 要单独加进白名单
        if (hooked) armTaskbarButtonCreated()
        runOnPump { refreshFromLastState() }
    }

    fun uninstall() {
        if (hooked) {
            DesktopWindowChromeNative.removeMessageHandler(messageHandler)
            hooked = false
        }
        mainHwnd = null
        buttonsAdded = false
        iconsAttached = false
        DesktopTaskbarDwm.uninstall()
        // 释放 COM 实例 (IUnknown::Release, vtable slot 2) —— 须在创建它的 STA 线程上
        runOnPump {
            cachedTaskbarList?.let { runCatching { vtbl(it, 2) } }
            cachedTaskbarList = null
            imageList?.let { runCatching { ComCtl32.INSTANCE.ImageList_Destroy(it) } }
            imageList = null
        }
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
     *
     * 调用方线程不定 (AUDIO_PROGRESS/AUDIO_SIZE 在协程线程发), 而 ITaskbarList3 是
     * Apartment-threaded 对象: 只有在自建的 STA 泵线程上调用才能传裸 HIMAGELIST。
     * 故状态写入后, COM 操作投递到泵线程, DWM 卡片投递到 EDT。
     */
    fun update(
        audioStatus: Int,
        aloud: ReadAloudTrayBinding?,
        progressMs: Int,
        durationMs: Int,
    ) {
        val aloudState = aloud?.controller?.state?.value
        lastAudioStatus = audioStatus
        lastAloudState = aloudState
        lastProgressMs = progressMs
        lastDurationMs = durationMs
        // DWM 悬停卡片走 AWT 窗口过程/GDI, 收口 EDT
        SwingUtilities.invokeLater {
            DesktopTaskbarDwm.update(audioStatus, aloud, progressMs, durationMs)
        }
        // ITaskbarList3 调用必须在 STA 泵线程 (见 runOnPump 注释)
        runOnPump { refreshFromLastState() }
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
        //
        val active = audioActive || aloudActive
        if (!active) {
            // 无后台任务: 按钮改隐藏态 (不复位 buttonsAdded, 否则再次活跃会重复 Add)
            if (buttonsAdded) updateButtons(hwnd, hiddenButtons())
            return
        }
        // 图标必须在 AddButtons 之前挂 (按钮带 THB_BITMAP 时任务栏按 iBitmap 取图)
        val withIcons = attachIcons(hwnd)
        val buttons = buildButtons(
            prevTip = str("previous_chapter", "上一章"),
            toggleTip = if (paused) str("resume", "继续") else str("pause", "暂停"),
            toggleIcon = if (paused) ICON_PLAY else ICON_PAUSE,
            nextTip = str("next_chapter", "下一章"),
            stopTip = str("stop", "停止"),
            withIcons = withIcons,
        )
        if (buttonsAdded) {
            updateButtons(hwnd, buttons)
        } else if (addButtons(hwnd, buttons) == 0 && withIcons) {
            // 图标已挂上且 Add 成功才 latch: 否则下次刷新重挂图标再 Add
            buttonsAdded = true
        }
    }

    /**
     * 挂 image list (必须在 AddButtons 之前)。返回 true = 图标可用, 按钮才可带 THB_BITMAP。
     *
     * 须在 STA 泵线程调用: 裸 HIMAGELIST 无法跨套间编组, 非 STA 线程上必得 E_FAIL。
     */
    private fun attachIcons(hwnd: WinDef.HWND): Boolean {
        if (iconsAttached) return true
        if (!ENABLE_THUMBBAR_ICONS) return false
        val list = taskbarList() ?: return false
        val himl = getOrCreateImageList() ?: return false
        val hr = runCatching {
            vtbl(list, SLOT_THUMB_BAR_SET_IMAGE_LIST, hwnd, himl)
        }.onFailure { AppLog.put("ThumbBarSetImageList 失败", it) }.getOrDefault(-1)
        if (hr != 0) {
            AppLog.put("ThumbBarSetImageList 失败 hr=0x${Integer.toHexString(hr)}")
            return false
        }
        iconsAttached = true
        return true
    }

    private fun buildButtons(
        prevTip: String,
        toggleTip: String,
        toggleIcon: Int,
        nextTip: String,
        stopTip: String,
        withIcons: Boolean,
    ): Memory {
        val mem = Memory(BUTTON_STRIDE * BTN_COUNT)
        // 整块清零: 未初始化内存可能导致 szTip 越界读到垃圾 (见 writeButton 终止符注释)
        mem.clear()
        writeButton(mem, 0, BTN_PREV, prevTip, 0, ICON_PREV, withIcons)
        writeButton(mem, 1, BTN_TOGGLE, toggleTip, 0, toggleIcon, withIcons)
        writeButton(mem, 2, BTN_NEXT, nextTip, 0, ICON_NEXT, withIcons)
        writeButton(mem, 3, BTN_STOP, stopTip, 0, ICON_STOP, withIcons)
        return mem
    }

    private fun hiddenButtons(): Memory {
        val mem = Memory(BUTTON_STRIDE * BTN_COUNT)
        mem.clear()
        for (i in 0 until BTN_COUNT) {
            writeButton(mem, i, i + 1, "", THBF_HIDDEN, ICON_PREV, false)
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
        iconIndex: Int,
        withIcons: Boolean,
    ) {
        val base = slot * BUTTON_STRIDE
        // THB_BITMAP 只在 image list 确实挂上后才置位: 声明了却取不到图 = explorer 崩
        val mask = if (withIcons) {
            THB_BITMAP or THB_TOOLTIP or THB_FLAGS
        } else {
            THB_TOOLTIP or THB_FLAGS
        }
        mem.setInt(base + 0, mask) // dwMask
        mem.setInt(base + 4, id)
        mem.setInt(base + 8, iconIndex) // iBitmap
        mem.setLong(base + 16, 0) // hIcon (x64 指针, 12..16 为对齐填充)
        val tipBase = base + 24
        tip.take(259).forEachIndexed { i, c -> mem.setChar(tipBase + i * 2L, c) }
        // szTip[260] WCHAR: 内容后显式补 0 终止符 —— JNA Memory 分配后不清零,
        // 不终止的话 explorer 读 tip 越界读到未初始化内存 (乱码尾巴/随机乱码)
        mem.setChar(tipBase + tip.length * 2L, '\u0000')
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
                else if (aloudActive) aloud.controller.prevParagraph()

                BTN_TOGGLE -> if (audioActive) {
                    // 对照原版通知 action: PLAY→pause / PAUSE→resume / 其他→loadOrUpPlayUrl
                    when (AudioPlayShared.status) {
                        Status.PLAY -> AudioPlayShared.pause()
                        Status.PAUSE -> AudioPlayShared.resume()
                        else -> AudioPlayShared.loadOrUpPlayUrl()
                    }
                } else if (aloudActive) {
                    val c = aloud.controller
                    if (c.state.value == ReadAloudState.PAUSED) c.resume() else c.pause()
                }

                BTN_NEXT -> if (audioActive) AudioPlayShared.next()
                else if (aloudActive) aloud.controller.nextParagraph()

                BTN_STOP -> if (audioActive) AudioPlayShared.stop()
                else if (aloudActive) aloud.controller.stop()
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
                        val c = aloud.controller
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
                else if (aloudActive) aloud.controller.nextParagraph()

                VK_MEDIA_PREV_TRACK -> if (audioActive) AudioPlayShared.prev()
                else if (aloudActive) aloud.controller.prevParagraph()

                VK_MEDIA_STOP -> if (audioActive) AudioPlayShared.stop()
                else if (aloudActive) aloud.controller.stop()
            }
        }
    }

    /** 命令切出消息线程执行 (播放命令内部会落库/起协程, 不该压在窗口过程/消息循环上)。 */
    private fun runCommand(action: () -> Unit) {
        commandExecutor.execute {
            runCatching { action() }.onFailure { AppLog.put("任务栏命令执行失败", it) }
        }
    }

    // ==================== 消息线程 (隐藏窗口 + RegisterHotKey + TaskbarCreated) ====================

    @Volatile
    private var pumpWindow: WinDef.HWND? = null

    @Volatile
    private var taskbarCreatedMsg: Int? = null

    /**
     * "TaskbarButtonCreated" 的消息号 (RegisterWindowMessage 运行期分配, ≥0xC000)。
     * 非 null 表示已加进 native 桥白名单, 主窗口收到它就会转发上来。
     */
    @Volatile
    private var taskbarButtonCreatedMsg: Int? = null

    private val mediaKeysRegistered = AtomicBoolean(false)

    /**
     * 泵线程 COM 任务队列。
     *
     * ITaskbarList3 是 Apartment-threaded: 必须在真正 STA 线程上调用。
     * AWT 的 EDT 未做 CoInitializeEx(APARTMENTTHREADED), 在其上取到的是跨套间代理,
     * ThumbBarSetImageList 传裸 HIMAGELIST 句柄无法跨套间编组 → E_FAIL (0x80004005),
     * 而 AddButtons 只传结构体可编组 → 返回 S_OK, 于是"按钮有、图标无"。
     * 故所有 ITaskbarList3 调用统一投递到本进程自建的 STA 泵线程执行。
     */
    private val comTasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /** 泵线程标识 (在其上直接执行, 避免自我投递死等)。 */
    @Volatile
    private var pumpThread: Thread? = null

    /** 把 COM 操作投递到 STA 泵线程 (已在泵线程则直接执行)。 */
    private fun runOnPump(action: () -> Unit) {
        if (Thread.currentThread() === pumpThread) {
            runCatching { action() }.onFailure { AppLog.put("任务栏 COM 操作失败", it) }
            return
        }
        val hwnd = pumpWindow ?: return
        comTasks.add(action)
        User32.INSTANCE.PostMessage(hwnd, WM_LG_RUN_COM, WinDef.WPARAM(0), WinDef.LPARAM(0))
    }

    private fun drainComTasks() {
        while (true) {
            val task = comTasks.poll() ?: return
            runCatching { task() }.onFailure { AppLog.put("任务栏 COM 操作失败", it) }
        }
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
                    msg == WM_LG_RUN_COM -> {
                        drainComTasks()
                        return WinDef.LRESULT(0)
                    }

                    msg == WM_HOTKEY -> {
                        onMediaKey(wParam.toLong().toInt())
                        return WinDef.LRESULT(0)
                    }

                    taskbarCreatedMsg != null && msg == taskbarCreatedMsg -> {
                        // explorer 重启: 任务栏按钮与 image list 全部重建, Update 对重建的按钮无效。
                        // 此刻新按钮还没建立, 等主窗口的 TaskbarButtonCreated 再重挂, 这里只清状态。
                        buttonsAdded = false
                        iconsAttached = false
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
        pumpThread = Thread.currentThread()
        // ITaskbarList3 是 Apartment-threaded: 本线程必须是真 STA, 否则 SetImageList
        // 拿到跨套间代理, 裸 HIMAGELIST 无法编组 → E_FAIL
        runCatching { Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED) }
        val created = runCatching {
            // WNDCLASSEX 注册 + 屏幕外隐藏窗口的样板统一收口在 help/win/Win32MessageWindow
            // (与 WebView2Loop 消息泵同款, 重复注册 ERROR_CLASS_ALREADY_EXISTS 无害)
            registerMessageWindowClass(
                className = WINDOW_CLASS,
                wndProc = pumpProc,
                owner = "任务栏媒体",
            )
            val hwnd = createHiddenMessageWindow(WINDOW_CLASS, "legado-taskbar-media")
            pumpWindow = hwnd
            // explorer 重启后任务栏按钮丢失, 监听 TaskbarCreated 重挂
            taskbarCreatedMsg = User32.INSTANCE.RegisterWindowMessage(WM_TASKBARCREATED_MSG)
            registerMediaKeys(hwnd)
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

    /**
     * 缩略图按钮点击 / 任务栏按钮建立: 拦发给主窗口的 WM_COMMAND 与 TaskbarButtonCreated。
     *
     * 不用 WH_GETMESSAGE 钩子 (依赖消息泵时序, AWT GetMessage 路径下回调触发不可靠,
     * 且全局钩子需 DLL 过程 → 1428; 线程级钩子又不触发)。
     * 两者都是发给窗口的消息, 由 native 桥的窗口过程拦截后转发到这里。
     */
    private val messageHandler: (Int, Long, Long) -> Boolean = ::handleWindowMessage

    /** 消息处理器已挂到 native 桥 (挂不上则 thumbbar 按钮点击收不到)。 */
    @Volatile
    private var hooked = false

    /**
     * 把 "TaskbarButtonCreated" 加进 native 桥的消息白名单 (幂等)。
     *
     * 该号由 RegisterWindowMessage 运行期分配 (≥0xC000), 不在 C 侧的编译期白名单里,
     * 必须显式追加, 否则本窗口的任务栏按钮建立事件收不到 (thumbbar 只能等下一次 update 重挂)。
     * 桥不可用时优雅退化: 只记一条日志, 其余功能照旧。
     */
    private fun armTaskbarButtonCreated() {
        if (taskbarButtonCreatedMsg != null) return
        val msg = runCatching {
            User32.INSTANCE.RegisterWindowMessage(WM_TASKBARBUTTONCREATED_MSG)
        }.getOrDefault(0)
        if (msg == 0) {
            AppLog.put("RegisterWindowMessage(TaskbarButtonCreated) 失败, thumbbar 只能延迟重挂")
            return
        }
        // 走 native 桥的绑定 (别按库名 Function.getFunction 取导出: 那依赖裸名能被
        // LoadLibrary 命中, 而桥是按绝对路径加载的, 走已加载实例更稳)
        val added = DesktopWindowChromeNative.addHookMessage(msg)
        if (!added) {
            AppLog.put("追加 TaskbarButtonCreated 白名单失败, thumbbar 只能延迟重挂")
        }
        if (added) taskbarButtonCreatedMsg = msg
    }

    /**
     * 白名单消息处理 (跑在 native 窗口线程 AWT-Windows, 非 EDT): 只解 id + 切线程执行, 不阻塞。
     * 返回 true = 已处理 (native 直接答 0 给 Windows)。
     */
    private fun handleWindowMessage(msg: Int, wparam: Long, lparam: Long): Boolean {
        // 任务栏按钮 (重)建立: 旧 image list 与按钮随之作废, 此刻才是 ThumbBarAddButtons 的
        // 正确时机 (MSDN); explorer 重启后不重挂就永远没有按钮。
        if (taskbarButtonCreatedMsg != null && msg == taskbarButtonCreatedMsg) {
            buttonsAdded = false
            iconsAttached = false
            runOnPump { refreshFromLastState() }
            return true
        }
        if (msg != WM_COMMAND) return false
        val id = (wparam and 0xFFFF).toInt()
        if (id in BTN_PREV..BTN_STOP) onThumbButton(id)
        return true
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
            // HrInit (slot 3): 必须在任何其他调用前; 失败则整个 ITaskbarList3 不可用
            if (vtbl(punk, SLOT_HRINIT) != 0) {
                AppLog.put("ITaskbarList3 HrInit 失败")
                runCatching { vtbl(punk, 2) }   // Release
                return null
            }
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

    private fun addButtons(hwnd: WinDef.HWND, buttons: Memory): Int {
        val list = taskbarList() ?: return -1
        return runCatching {
            vtbl(list, SLOT_THUMB_BAR_ADD_BUTTONS, hwnd, BTN_COUNT, buttons)
        }.onFailure { AppLog.put("ThumbBarAddButtons 失败", it) }.getOrDefault(-1)
    }

    // ==================== 按钮图标 (系统字形 → 预乘 DIB + 掩码 → ImageList) ====================

    /** 按钮 glyph 形状 (对照原版通知按钮图标语义: 上一首/播放/暂停/下一首/停止)。 */
    internal enum class ThumbGlyph { PREV, PLAY, PAUSE, NEXT, STOP }

    /**
     * Segoe Fluent Icons 字形码位 (Windows 11 系统字体, 与任务栏媒体按钮同源图形)。
     * Play U+E768 / Pause U+E769 / Stop U+E71A / SkipBack U+E892 / SkipForward U+E893。
     */
    internal val glyphCodePoint = mapOf(
        ThumbGlyph.PREV to 0xE892,
        ThumbGlyph.PLAY to 0xE768,
        ThumbGlyph.PAUSE to 0xE769,
        ThumbGlyph.NEXT to 0xE893,
        ThumbGlyph.STOP to 0xE71A,
    )

    /** 懒创建按钮图标列表, 顺序 [PREV, PLAY, PAUSE, NEXT, STOP] (对应 ICON_PREV..ICON_STOP)。 */
    private fun getOrCreateImageList(): Pointer? {
        imageList?.let { return it }
        synchronized(this) {
            imageList?.let { return it }
            // 列表标志与 Add 的掩码必须自洽 (ILC_MASK ⇔ 传 hbmMask), 否则任务栏渲染异常
            val himl = ComCtl32.INSTANCE
                .ImageList_Create(ICON_SIZE, ICON_SIZE, ILC_COLOR32 or ILC_MASK, 5, 0)
                ?: run {
                    AppLog.put("任务栏按钮图标: ImageList_Create 失败")
                    return null
                }
            val built = runCatching {
                val glyphs = listOf(
                    ThumbGlyph.PREV, ThumbGlyph.PLAY, ThumbGlyph.PAUSE,
                    ThumbGlyph.NEXT, ThumbGlyph.STOP,
                )
                val bitmaps = mutableListOf<Pair<WinDef.HBITMAP, WinDef.HBITMAP>>()
                try {
                    for (glyph in glyphs) {
                        val (hbm, hbmMask) = ICON_SIZE.createThumbBitmap(glyph)
                            ?: error("createThumbBitmap 失败 glyph=$glyph")
                        bitmaps += hbm to hbmMask
                        // ImageList_Add 复制位图入列, 返回后即可释放 GDI 对象
                        val idx = ComCtl32.INSTANCE.ImageList_Add(himl, hbm, hbmMask)
                        if (idx < 0) error("ImageList_Add 失败 idx=$idx")
                    }
                } finally {
                    bitmaps.forEach { (hbm, mask) ->
                        runCatching { GDI32.INSTANCE.DeleteObject(hbm) }
                        runCatching { GDI32.INSTANCE.DeleteObject(mask) }
                    }
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

    /**
     * 照 thumbbar_test.c (已实测悬停不崩、图标正常显示) 构造按钮图标:
     * 32bpp 预乘 alpha DIB + 1bpp 透明掩码, ImageList_Add(hbm, hbmMask) 添加。
     */
    private fun Int.createThumbBitmap(
        glyph: ThumbGlyph
    ): Pair<WinDef.HBITMAP, WinDef.HBITMAP>? {
        val img = drawGlyphImage(this, glyph)
        val hbm = toPremultipliedHBitmap(img) ?: return null
        val hbmMask = createMaskDib(this, img) ?: run {
            runCatching { GDI32.INSTANCE.DeleteObject(hbm) }
            return null
        }
        return hbm to hbmMask
    }

    /** 1bpp top-down DIB 掩码 (Qt qt_imageToWinHBITMAP Format_Mono 同款: CreateDIBSection)。 */
    private fun createMaskDib(size: Int, img: BufferedImage): WinDef.HBITMAP? {
        // DIB 行 4 字节对齐
        val rowBytes = ((size + 31) / 32) * 4
        val maskBits = ByteArray(rowBytes * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (((img.getRGB(x, y) ushr 24) and 0xFF) < 128) {
                    val off = y * rowBytes + x / 8
                    maskBits[off] = (maskBits[off].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
        }
        // BITMAPINFO: header(40) + 2 色调色板(8) = 48; top-down (biHeight 负)。
        // Memory 分配后不清零, 必须先 clear: 否则 biClrUsed/biClrImportant/调色板全是垃圾,
        // CreateDIBSection 直接失败 → 掩码为 null → 整个图标列表构建失败 (按钮无图的根因)
        val bmi = Memory(48)
        bmi.clear()
        bmi.setInt(0, 40)          // biSize
        bmi.setInt(4, size)        // biWidth
        bmi.setInt(8, -size)       // biHeight (负 = top-down)
        bmi.setShort(12, 1)        // biPlanes
        bmi.setShort(14, 1)        // biBitCount = 1
        bmi.setInt(16, 0)          // biCompression = BI_RGB
        bmi.setInt(32, 2)          // biClrUsed = 2 (单色掩码调色板)
        bmi.setInt(40, 0x00000000) // 调色板[0] = 黑 (不透明)
        bmi.setInt(44, 0x00FFFFFF) // 调色板[1] = 白 (透明)
        val bitsRef = PointerByReference()
        val fn = Function.getFunction("gdi32", "CreateDIBSection", Function.ALT_CONVENTION)
        val hbm = fn.invokePointer(arrayOf(null, bmi, WinGDI.DIB_RGB_COLORS, bitsRef, null, 0))
        if (hbm == null || bitsRef.value == null) return null
        bitsRef.value.write(0, maskBits, 0, maskBits.size)
        return WinDef.HBITMAP(hbm)
    }

    /** 32bpp 预乘 alpha DIB (VLC PremultipliedAlpha 同款, Taskbar 渲染要求)。 */
    private fun toPremultipliedHBitmap(img: BufferedImage): WinDef.HBITMAP? {
        val w = img.width
        val h = img.height
        val header = WinGDI.BITMAPINFOHEADER()
        header.biSize = 40
        header.biWidth = w
        header.biHeight = -h
        header.biPlanes = 1
        header.biBitCount = 32
        header.biCompression = WinGDI.BI_RGB
        val bmi = WinGDI.BITMAPINFO()
        bmi.bmiHeader = header
        val bits = PointerByReference()
        val hbm = GDI32.INSTANCE.CreateDIBSection(null, bmi, WinGDI.DIB_RGB_COLORS, bits, null, 0)
        if (hbm == null || bits.value == null) return null
        val pixels = img.getRGB(0, 0, w, h, null, 0, w)
        val dst = bits.value
        for (i in pixels.indices) {
            val argb = pixels[i]
            val a = (argb ushr 24) and 0xFF
            val r = ((argb ushr 16) and 0xFF) * a / 255
            val g = ((argb ushr 8) and 0xFF) * a / 255
            val b = (argb and 0xFF) * a / 255
            dst.setInt(i * 4L, (a shl 24) or (r shl 16) or (g shl 8) or b)
        }
        return hbm
    }

    /** 渲染 glyph 位图 (透明底): 系统字体字形优先, 不可用则回退自绘形状。 */
    private fun drawGlyphImage(size: Int, glyph: ThumbGlyph): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            val cp = glyphCodePoint[glyph] ?: 0
            val font = GLYPH_FONT
            if (font != null && font.canDisplay(cp)) {
                g.font = font.deriveFont(size.toFloat())
                g.color = Color.WHITE
                val fm = g.fontMetrics
                val str = String(Character.toChars(cp))
                val x = (size - fm.stringWidth(str)) / 2f
                val y = (size - fm.height) / 2f + fm.ascent
                g.drawString(str, x, y)
            } else {
                drawGlyphShape(g, size, glyph)
            }
        } finally {
            g.dispose()
        }
        return img
    }

    /** 系统字体 (Segoe Fluent Icons, Win11 标配; 失败则回退自绘形状)。 */
    private val GLYPH_FONT: Font? by lazy {
        runCatching { Font("Segoe Fluent Icons", Font.PLAIN, 32) }.getOrNull()
    }

    /** 绘制白色 glyph 自绘形状 (透明底, 32bpp; 字形渲染不可用时的回退)。 */
    private fun drawGlyphShape(g: Graphics2D, size: Int, glyph: ThumbGlyph) {
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

    private fun updateButtons(hwnd: WinDef.HWND, buttons: Memory): Int {
        val list = taskbarList() ?: return -1
        return runCatching {
            vtbl(list, SLOT_THUMB_BAR_UPDATE_BUTTONS, hwnd, BTN_COUNT, buttons)
        }.onFailure { AppLog.put("ThumbBarUpdateButtons 失败", it) }.getOrDefault(-1)
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


    private fun str(key: String, fallback: String): String =
        jvmGetString(key).takeIf { it != key } ?: fallback

    /**
     * 主窗口 HWND (供 DesktopSmtc 绑定 SMTC 会话)。
     * A/B 实证: 会话绑到无任务栏按钮的隐藏窗口时, 悬停任务栏 → Taskbar.View.dll
     * stowed exception 拖崩 explorer; 绑可见主窗口则正常。
     */
    internal fun mainWindowHandle(): Pointer? = mainHwnd?.pointer

    /** THUMBBUTTON 结构 stride (仅支持 x64 = 552; writeButton 偏移与任务栏链路其余部分同为 x64 假设)。 */
    private const val BUTTON_STRIDE = 552L

    private const val START_TIMEOUT_SECONDS = 10L
}
