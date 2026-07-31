package io.legado.desktop.ui.tray

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.help.toast.DesktopTrayNotifier
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.utils.FlowBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * 朗读侧接入托盘所需的最小绑定: 桌面端 [ReadAloudControllerShared] 由阅读页宿主创建
 * (当前尚无接入点, 见 DesktopReadBookPlatform 注释), 故控制器与书名/章节名由宿主注入。
 */
class ReadAloudTrayBinding(
    val controller: ReadAloudControllerShared,
    val bookName: () -> String? = { null },
    val chapterTitle: () -> String? = { null },
)

/**
 * 桌面端系统托盘: 播放/朗读状态展示 + 右键控制菜单 (听书时最小化仍可控)。
 *
 * # 托盘所有权
 * 全进程唯一 [TrayIcon] 由本对象持有。`Toaster.jvm.kt` 原先自建蓝色圆点 TrayIcon 发通知,
 * 已改为经 [DesktopTrayNotifier] 委托到这里, 避免两个图标; 未安装托盘时它退化回 stdout。
 *
 * # 显示时机
 * 图标常驻 (随宿主启动 install), 菜单随状态增删: 音频/朗读活跃时才有播放控制项,
 * 空闲时只剩"显示/退出"。常驻的原因是它同时是 toast 通知的宿主 —— 按需增删会让空闲期的
 * 通知无处可发。
 *
 * # 文案考古
 * tooltip 与菜单项对照 app 端 `AudioPlayService.createNotification` /
 * `BaseReadAloudService` 的媒体通知: 标题"正在播放/播放暂停|正在朗读/朗读暂停: 书名",
 * 副标题为章节名, action 集为 上一章 / 播放暂停 / 下一章 / 停止 (定时器桌面端未接, 不造)。
 */
object DesktopMediaTray {

    /** Windows NOTIFYICONDATA szTip 上限 128, 留余量截断。 */
    private const val TOOLTIP_MAX = 120

    private val readAloudBinding = MutableStateFlow<ReadAloudTrayBinding?>(null)

    /** 朗读控制器绑定: 阅读页启动朗读时注入, 停止朗读后置 null。 */
    var readAloud: ReadAloudTrayBinding?
        get() = readAloudBinding.value
        set(value) {
            readAloudBinding.value = value
        }

    // install/uninstall 在主线程写, refresh 在协程线程读, 故 @Volatile
    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var trayIcon: TrayIcon? = null

    @Volatile
    private var popupMenu: PopupMenu? = null

    @Volatile
    private var windowProvider: (() -> Window?)? = null

    @Volatile
    private var exitAction: (() -> Unit)? = null

    /**
     * 安装托盘 (无头模式 / 系统不支持托盘时静默 no-op)。
     *
     * @param windowProvider 主窗口提供者 (左键点击 / "显示"菜单项恢复它)
     * @param exitAction 退出动作 (通常是 Compose 的 exitApplication)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun install(windowProvider: () -> Window?, exitAction: () -> Unit) {
        if (trayIcon != null) return
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return
        this.windowProvider = windowProvider
        this.exitAction = exitAction
        val popup = PopupMenu()
        val icon = TrayIcon(loadTrayImage(), appName(), popup)
        icon.isImageAutoSize = true
        // 左键单击恢复窗口 (不加 ActionListener: Windows 上双击会与本监听重复触发)
        icon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) activateWindow()
            }
        })
        val added = runCatching { SystemTray.getSystemTray().add(icon) }
            .onFailure { AppLog.put("系统托盘图标添加失败", it) }
            .isSuccess
        if (!added) return
        trayIcon = icon
        popupMenu = popup
        // 接管 Toaster 的通知发送 (原 Toaster.jvm.kt 自建 TrayIcon 已移除)
        DesktopTrayNotifier.sender = { message -> displayMessage(message) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            s.launch { FlowBus.withSticky(EventBus.AUDIO_STATE).collect { refresh() } }
            s.launch { FlowBus.withSticky(EventBus.AUDIO_SUB_TITLE).collect { refresh() } }
            s.launch {
                readAloudBinding
                    .flatMapLatest { it?.controller?.state ?: flowOf(null) }
                    .collect { refresh() }
            }
        }
        refresh()
    }

    /** 卸载托盘 (进程退出前调用, 避免图标残留)。 */
    fun uninstall() {
        DesktopTrayNotifier.sender = null
        scope?.cancel()
        scope = null
        trayIcon?.let { icon ->
            runCatching { SystemTray.getSystemTray().remove(icon) }
        }
        trayIcon = null
        popupMenu = null
        windowProvider = null
        exitAction = null
    }

    // ==================== 通知 ====================

    /** 供 [DesktopTrayNotifier] 回调: 返回 false 时 Toaster 落 stdout 兜底。 */
    private fun displayMessage(message: String): Boolean {
        val icon = trayIcon ?: return false
        return runCatching {
            icon.displayMessage(appName(), message, TrayIcon.MessageType.INFO)
        }.isSuccess
    }

    // ==================== 状态刷新 ====================

    private fun refresh() {
        val icon = trayIcon ?: return
        val popup = popupMenu ?: return
        val audioStatus = AudioPlayShared.status
        val audioActive = audioStatus != Status.STOP
        val aloud = readAloudBinding.value
        val aloudState = aloud?.controller?.state?.value
        val aloudActive = aloudState == ReadAloudState.PLAYING || aloudState == ReadAloudState.PAUSED
        val tooltip = buildTooltip(audioStatus, audioActive, aloud, aloudState, aloudActive)
        // AWT 菜单/tooltip 必须在 EDT 上改
        SwingUtilities.invokeLater {
            runCatching {
                icon.toolTip = tooltip
                popup.removeAll()
                // 两条链同时活跃时加标题分组, 免得两组"上一章"分不清 (文案取 app 端通知 subText)
                val grouped = audioActive && aloudActive
                if (audioActive) {
                    if (grouped) popup.add(header(str("audio", "音频")))
                    addAudioItems(popup, audioStatus)
                }
                if (aloudActive) {
                    if (grouped) popup.add(header(str("read_aloud", "朗读")))
                    addReadAloudItems(popup, aloud!!, aloudState == ReadAloudState.PAUSED)
                }
                if (audioActive || aloudActive) popup.addSeparator()
                popup.add(item(str("show", "显示")) { activateWindow() })
                popup.add(item(str("exit", "退出")) { exitAction?.invoke() })
            }.onFailure { AppLog.put("托盘菜单刷新失败", it) }
        }
    }

    private fun buildTooltip(
        audioStatus: Int,
        audioActive: Boolean,
        aloud: ReadAloudTrayBinding?,
        aloudState: ReadAloudState?,
        aloudActive: Boolean,
    ): String {
        val lines = ArrayList<String>(2)
        when {
            audioActive -> {
                val prefix = if (audioStatus == Status.PAUSE) {
                    str("audio_pause", "播放暂停")
                } else {
                    str("audio_play_t", "正在播放")
                }
                lines += titleLine(prefix, AudioPlayShared.book?.name)
                lines += AudioPlayShared.durChapter?.title?.takeUnless { it.isEmpty() }
                    ?: str("audio_play_s", "点击打开播放界面")
            }

            aloudActive -> {
                val prefix = if (aloudState == ReadAloudState.PAUSED) {
                    str("read_aloud_pause", "朗读暂停")
                } else {
                    str("read_aloud_t", "正在朗读")
                }
                lines += titleLine(prefix, aloud?.bookName())
                lines += aloud?.chapterTitle()?.takeUnless { it.isEmpty() }
                    ?: str("read_aloud_s", "点击打开阅读界面")
            }

            else -> lines += appName()
        }
        return lines.joinToString("\n").take(TOOLTIP_MAX)
    }

    private fun titleLine(prefix: String, bookName: String?): String =
        if (bookName.isNullOrEmpty()) prefix else "$prefix: $bookName"

    // ==================== 菜单项 ====================

    /** 对照 AudioPlayService 通知 action: 上一章 / 播放暂停 / 下一章 / 停止。 */
    private fun addAudioItems(popup: PopupMenu, audioStatus: Int) {
        val paused = audioStatus == Status.PAUSE
        val toggleLabel = if (paused) str("resume", "继续") else str("pause", "暂停")
        popup.add(item(toggleLabel) {
            if (paused) AudioPlayShared.resume() else AudioPlayShared.pause()
        })
        popup.add(item(str("previous_chapter", "上一章")) { AudioPlayShared.prev() })
        popup.add(item(str("next_chapter", "下一章")) { AudioPlayShared.next() })
        // app 端通知 stop → stopSelf, 对应 shared stop() (含 saveRead), 非 stopPlay()
        popup.add(item(str("stop", "停止")) { AudioPlayShared.stop() })
    }

    /** 对照 BaseReadAloudService 通知 action + IntentAction.prev/nextParagraph。 */
    private fun addReadAloudItems(
        popup: PopupMenu,
        aloud: ReadAloudTrayBinding,
        paused: Boolean,
    ) {
        val controller = aloud.controller
        val toggleLabel = if (paused) str("resume", "继续") else str("pause", "暂停")
        popup.add(item(toggleLabel) {
            if (paused) controller.resume() else controller.pause()
        })
        popup.add(item(str("read_aloud_prev_paragraph", "朗读上一段")) { controller.prevParagraph() })
        popup.add(item(str("read_aloud_next_paragraph", "朗读下一段")) { controller.nextParagraph() })
        popup.add(item(str("previous_chapter", "上一章")) { controller.prevChapter() })
        popup.add(item(str("next_chapter", "下一章")) { controller.nextChapter() })
        popup.add(item(str("stop", "停止")) { controller.stop() })
    }

    private fun header(label: String): MenuItem = MenuItem(label).apply { isEnabled = false }

    private fun item(label: String, action: () -> Unit): MenuItem =
        MenuItem(label).apply { addActionListener { runCommand(action) } }

    /** 菜单命令切出 EDT 执行: 播放命令内部会落库/起协程, 不该压在 AWT 事件线程上。 */
    private fun runCommand(action: () -> Unit) {
        val s = scope ?: return
        s.launch {
            runCatching { action() }.onFailure { AppLog.put("托盘菜单命令执行失败", it) }
        }
    }

    // ==================== 窗口 ====================

    /** 取消最小化 → 临时置顶抬到最前 → 请求焦点 (同 SingleInstanceGuard.activateWindow)。 */
    private fun activateWindow() {
        val window = windowProvider?.invoke() ?: return
        SwingUtilities.invokeLater {
            runCatching {
                (window as? Frame)?.let { frame ->
                    if (frame.extendedState and Frame.ICONIFIED != 0) {
                        frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
                    }
                }
                window.isVisible = true
                val wasAlwaysOnTop = window.isAlwaysOnTop
                if (!wasAlwaysOnTop && window.isAlwaysOnTopSupported) {
                    window.isAlwaysOnTop = true
                    window.toFront()
                    window.isAlwaysOnTop = false
                } else {
                    window.toFront()
                }
                window.requestFocus()
            }
        }
    }

    // ==================== 资源 ====================

    /**
     * 托盘图标: 复用窗口图标 classpath 资源 icon.png, 按平台托盘尺寸缩放
     * (Windows 通常 16x16, macOS 菜单栏 ~22x22)。
     * 注: AWT 无 macOS template image API, 深色菜单栏下无法自动反色。
     */
    private fun loadTrayImage(): Image {
        val traySize = runCatching { SystemTray.getSystemTray().trayIconSize }.getOrNull()
        val width = traySize?.width?.takeIf { it > 0 } ?: 16
        val height = traySize?.height?.takeIf { it > 0 } ?: 16
        val raw = runCatching {
            Thread.currentThread().contextClassLoader
                ?.getResourceAsStream("icon.png")?.use { ImageIO.read(it) }
        }.getOrNull() ?: return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        return raw.getScaledInstance(width, height, Image.SCALE_SMOOTH)
    }

    private fun appName(): String = str("app_name", "阅读")

    /** shared composeResources 缺 key 时 jvmGetString 原样返回 key, 用中文兜底。 */
    private fun str(key: String, fallback: String): String =
        jvmGetString(key).takeIf { it != key } ?: fallback
}
