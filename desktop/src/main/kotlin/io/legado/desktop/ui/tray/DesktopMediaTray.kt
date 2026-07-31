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
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.math.roundToInt

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
 * 图标常驻 (随宿主启动 install), 右键菜单每次弹出时按当前状态现构建: 音频/朗读活跃时才有播放
 * 控制项, 空闲时只剩"显示/退出"。图标常驻的原因是它同时是 toast 通知的宿主 —— 按需增删会让
 * 空闲期的通知无处可发。
 *
 * # 菜单为什么用 Swing 而不是 java.awt.PopupMenu
 * AWT 的 [java.awt.MenuItem] 在 Windows 上是 owner-draw 原生菜单, 文字由 JDK 的
 * `AwtFont::drawMFString` 按 fontconfig 字符集拆段后用 GDI 的窄字节 TextOut 绘制,
 * 该路径解析不出 CJK 字形, 菜单项一律画成"豆腐块"。实测 `setFont` 确实生效 (换 28pt Serif
 * 菜单真的变大变衬线) 但中文照样是方块, 换 "Microsoft YaHei UI" 也无效 —— 即字体不是变量,
 * 是原生绘制路径本身不支持。Swing 的 [JPopupMenu] 由 Java2D 自绘, 走正常字体回退, 中文正常。
 * (托盘 tooltip 与气泡通知走的是原生 Shell_NotifyIcon 宽字符路径, 中文本来就正常, 不受影响。)
 *
 * # 文案考古
 * tooltip 与菜单项对照 app 端 `AudioPlayService.createNotification` /
 * `BaseReadAloudService` 的媒体通知: 标题"正在播放/播放暂停|正在朗读/朗读暂停: 书名",
 * 副标题为章节名, action 集为 上一章 / 播放暂停 / 下一章 / 停止 (定时器桌面端未接, 不造)。
 */
object DesktopMediaTray {

    /** Windows NOTIFYICONDATA szTip 上限 128, 留余量截断。 */
    private const val TOOLTIP_MAX = 120

    /** JDK WTrayIconPeer.TRAY_ICON_WIDTH/HEIGHT 的逻辑基准尺寸。 */
    private const val TRAY_ICON_BASE = 16

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

    /** Swing 菜单的宿主窗口 (1x1 不可见): 托盘无 Component, [JPopupMenu] 需要一个 invoker。 */
    @Volatile
    private var anchorDialog: JDialog? = null

    @Volatile
    private var activeMenu: JPopupMenu? = null

    @Volatile
    private var lookAndFeelReady = false

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
        val icon = TrayIcon(loadTrayImage(), appName())
        icon.isImageAutoSize = true
        // 左键单击恢复窗口 (不加 ActionListener: Windows 上双击会与本监听重复触发)
        // 右键弹 Swing 菜单: 刻意不给 TrayIcon 绑 java.awt.PopupMenu (中文会画成方块, 见类注释)
        icon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) activateWindow()
            }

            // popupTrigger 的时机各平台不同 (Windows 在 released, macOS/Linux 在 pressed), 都接
            override fun mousePressed(e: MouseEvent) = maybeShowMenu(e)

            override fun mouseReleased(e: MouseEvent) = maybeShowMenu(e)
        })
        val added = runCatching { SystemTray.getSystemTray().add(icon) }
            .onFailure { AppLog.put("系统托盘图标添加失败", it) }
            .isSuccess
        if (!added) return
        trayIcon = icon
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
        val dialog = anchorDialog
        anchorDialog = null
        activeMenu = null
        if (dialog != null) SwingUtilities.invokeLater { runCatching { dialog.dispose() } }
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

    /** 状态变化只需刷 tooltip; 菜单是右键时现构建的, 不必预先同步。 */
    private fun refresh() {
        val icon = trayIcon ?: return
        val audioStatus = AudioPlayShared.status
        val audioActive = audioStatus != Status.STOP
        val aloud = readAloudBinding.value
        val aloudState = aloud?.controller?.state?.value
        val aloudActive = aloudState == ReadAloudState.PLAYING || aloudState == ReadAloudState.PAUSED
        val tooltip = buildTooltip(audioStatus, audioActive, aloud, aloudState, aloudActive)
        SwingUtilities.invokeLater {
            runCatching { icon.toolTip = tooltip }
                .onFailure { AppLog.put("托盘提示刷新失败", it) }
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

    // ==================== 菜单 ====================

    private fun maybeShowMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        // TrayIcon 的 MouseEvent 坐标本就是屏幕坐标 (托盘无 Component 参照系)
        val x = e.x
        val y = e.y
        SwingUtilities.invokeLater {
            runCatching { showMenu(x, y) }.onFailure { AppLog.put("托盘菜单弹出失败", it) }
        }
    }

    /**
     * 在托盘点击处弹出菜单。
     *
     * 位置自己算而不是交给 [JPopupMenu] 的越界翻转 —— 后者按整块屏幕算, 会把菜单压到任务栏
     * 底下 (实测末项被遮住); 这里按"工作区"(屏幕减去任务栏 inset) 夹紧, 底部任务栏时向上展开。
     */
    private fun showMenu(x: Int, y: Int) {
        if (trayIcon == null) return
        ensureNativeLookAndFeel()
        val menu = buildMenu()
        val anchor = anchorDialog ?: createAnchor().also { anchorDialog = it }
        val area = workArea(x, y)
        val size = menu.preferredSize
        val left = (area.x + area.width - size.width).coerceAtLeast(area.x)
        val px = x.coerceIn(area.x, left)
        val py = if (y - size.height >= area.y) y - size.height else y
        anchor.setLocation(px, py)
        anchor.isVisible = true
        // 宿主窗口必须真正拿到前台焦点, 否则点别处时收不到 windowLostFocus, 菜单会赖着不走
        anchor.toFront()
        anchor.requestFocus()
        activeMenu = menu
        menu.show(anchor, 0, 0)
    }

    private fun createAnchor(): JDialog = JDialog().apply {
        isUndecorated = true
        isAlwaysOnTop = true
        setSize(1, 1)
        addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent) = dismissMenu()
        })
    }

    private fun dismissMenu() {
        activeMenu?.isVisible = false
        activeMenu = null
        anchorDialog?.isVisible = false
    }

    /** 托盘菜单是进程内唯一 Swing UI, 用系统 LAF 保持原生菜单外观 (字体自动取 win.menu.font)。 */
    private fun ensureNativeLookAndFeel() {
        if (lookAndFeelReady) return
        lookAndFeelReady = true
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    /** 点击点所在屏幕的工作区 (多显示器下不能只看主屏)。 */
    private fun workArea(x: Int, y: Int): Rectangle {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val config = env.screenDevices.asSequence()
            .map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(x, y) }
            ?: env.defaultScreenDevice.defaultConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }

    private fun buildMenu(): JPopupMenu {
        val menu = JPopupMenu()
        val audioStatus = AudioPlayShared.status
        val audioActive = audioStatus != Status.STOP
        val aloud = readAloudBinding.value
        val aloudState = aloud?.controller?.state?.value
        val aloudActive = aloudState == ReadAloudState.PLAYING || aloudState == ReadAloudState.PAUSED
        // 两条链同时活跃时加标题分组, 免得两组"上一章"分不清 (文案取 app 端通知 subText)
        val grouped = audioActive && aloudActive
        if (audioActive) {
            if (grouped) menu.add(header(str("audio", "音频")))
            addAudioItems(menu, audioStatus)
        }
        if (aloudActive) {
            if (grouped) menu.add(header(str("read_aloud", "朗读")))
            addReadAloudItems(menu, aloud!!, aloudState == ReadAloudState.PAUSED)
        }
        if (audioActive || aloudActive) menu.addSeparator()
        menu.add(item(str("show", "显示")) { activateWindow() })
        menu.add(item(str("exit", "退出")) { exitAction?.invoke() })
        // 选中菜单项 / ESC 关闭时把宿主窗口一并收掉, 免得 1x1 置顶窗赖在前台
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                anchorDialog?.isVisible = false
            }

            override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
        })
        return menu
    }

    /** 对照 AudioPlayService 通知 action: 上一章 / 播放暂停 / 下一章 / 停止。 */
    private fun addAudioItems(popup: JPopupMenu, audioStatus: Int) {
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
        popup: JPopupMenu,
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

    private fun header(label: String): JMenuItem = JMenuItem(label).apply { isEnabled = false }

    private fun item(label: String, action: () -> Unit): JMenuItem =
        JMenuItem(label).apply { addActionListener { runCommand(action) } }

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
     * 托盘图标: 复用窗口图标 classpath 资源 icon.png (192x192), 缩放到托盘原生像素尺寸。
     *
     * 尺寸必须按 `16 * 屏幕缩放比` 算而不能用 [SystemTray.getTrayIconSize] —— 后者返回的是逻辑
     * 16x16, 而 JDK 的 WTrayIconPeer 生成原生图标时用的是 `Region.clipScale(16, scaleX)`
     * (125% DPI 下 = 20px)。按 16 缩完再被 JDK 拉到 20, 两次重采样就把"阅读"糊成一团方块。
     * 尺寸对齐后 [TrayIcon.setImageAutoSize] 的绘制退化为 1:1 blit, 不再二次重采样。
     *
     * 注: AWT 无 macOS template image API, 深色菜单栏下无法自动反色。
     */
    private fun loadTrayImage(): Image {
        val transform = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.defaultTransform
        }.getOrNull()
        val width = trayPixels(transform?.scaleX)
        val height = trayPixels(transform?.scaleY)
        val raw = runCatching {
            Thread.currentThread().contextClassLoader
                ?.getResourceAsStream("icon.png")?.use { ImageIO.read(it) }
        }.getOrNull() ?: return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        return scaleHighQuality(raw, width, height)
    }

    private fun trayPixels(scale: Double?): Int =
        (TRAY_ICON_BASE * (scale?.takeIf { it > 0 } ?: 1.0)).roundToInt().coerceAtLeast(1)

    /**
     * 逐级折半 + 双线性重采样: Java2D 的 drawImage 默认最近邻, 192→20 一步缩会丢掉大半笔画;
     * 折半链能把细节先平均进去。同时把索引色 PNG 转成 ARGB (托盘原生图标需要 alpha)。
     */
    private fun scaleHighQuality(src: BufferedImage, width: Int, height: Int): BufferedImage {
        var current: Image = src
        var w = src.width
        var h = src.height
        while (w / 2 >= width && h / 2 >= height) {
            w /= 2
            h /= 2
            current = drawScaled(current, w, h)
        }
        return drawScaled(current, width, height)
    }

    private fun drawScaled(src: Image, width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { out ->
            val g = out.createGraphics()
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, width, height, null)
            g.dispose()
        }

    private fun appName(): String = str("app_name", "阅读")

    /** shared composeResources 缺 key 时 jvmGetString 原样返回 key, 用中文兜底。 */
    private fun str(key: String, fallback: String): String =
        jvmGetString(key).takeIf { it != key } ?: fallback
}
