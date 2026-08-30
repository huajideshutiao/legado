package io.legado.desktop.ui.tray

import androidx.compose.ui.graphics.toAwtImage
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.help.image.decodeBytesSampled
import io.legado.app.help.toast.DesktopTrayNotifier
import io.legado.app.model.ActiveReadBookRegistry
import io.legado.app.model.AudioPlayCommanders
import io.legado.app.model.AudioPlayShared
import io.legado.app.service.ReadAloudControllerShared
import io.legado.app.service.ReadAloudControllerShared.ReadAloudState
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.FlowBus
import io.legado.desktop.ui.tray.DesktopMediaTray.syncVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.Frame
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
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
import javax.swing.Icon
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
    /** 定时关闭分钟数 (对照原版通知标题的倒计时; 0=未启用)。 */
    val timeMinute: () -> Int = { 0 },
)

/**
 * 桌面端系统托盘: 播放/朗读状态展示 + 右键控制菜单 (听书时最小化仍可控)。
 *
 * # 托盘所有权
 * 全进程唯一 [TrayIcon] 由本对象持有。`Toaster.jvm.kt` 原先自建蓝色圆点 TrayIcon 发通知,
 * 已改为经 [DesktopTrayNotifier] 委托到这里, 避免两个图标; 未安装托盘时它退化回 stdout。
 *
 * # 显示时机 (与 app 端 Android 后台任务语义对齐)
 * 托盘图标是桌面端"后台任务"的呈现: app 端以"前台服务 + 常驻通知"表示后台任务
 * (音频: AudioPlayService 在 status != STOP 时 startForeground; 朗读: BaseReadAloudService
 * 在 state ∈ {PLAYING, PAUSED} 时 startForeground, 停止即 stopSelf), 桌面端用同一状态源
 * (AudioPlayShared.status / ReadAloudControllerShared.state) 驱动托盘图标显隐 ——
 * 任一后台任务活跃即显示, 全部停止即隐藏。空闲期图标不驻留; 此时 toast/进度通知经
 * [DesktopTrayNotifier] 回退 stdout (见 Toaster.jvm.kt / DesktopNotificationService)。
 * 右键菜单每次弹出时按当前状态现构建: 音频/朗读活跃时才有播放控制项, 空闲时只剩"显示/退出"。
 *
 * # 菜单实现 (统一 Swing JPopupMenu)
 * 全平台统一用 Swing 的 [JPopupMenu] (ensureNativeLookAndFeel 系统 LAF)。
 * - 为什么不用 java.awt.PopupMenu: AWT 的 [java.awt.MenuItem] 在 Windows 上是
 *   owner-draw 原生菜单, 文字由 JDK 的 `AwtFont::drawMFString` 按 fontconfig 字符集
 *   拆段后用 GDI 的窄字节 TextOut 绘制, 该路径解析不出 CJK 字形, 菜单项一律画成
 *   "豆腐块"。实测 `setFont` 确实生效 (换 28pt Serif 菜单真的变大变衬线) 但中文照样
 *   是方块, 换 "Microsoft YaHei UI" 也无效 —— 即字体不是变量, 是原生绘制路径本身
 *   不支持。Swing 的 [JPopupMenu] 由 Java2D 自绘, 走正常字体回退, 中文正常。
 * - 历史: 曾用 JNA 直调 Win32 TrackPopupMenuEx 追求 Win11 原生观感, 但隐藏 owner 的
 *   SetForegroundWindow 会取消菜单 (点击外部不关闭/右键失效等一连串问题), 已弃用。
 *   Swing 菜单点击外部自动关闭 (轻量组件标准行为), 简单可靠, 观感由系统 LAF 决定。
 *   (托盘 tooltip 与气泡通知走的是原生 Shell_NotifyIcon 宽字符路径, 中文本来就正常, 不受影响。)
 *
 * # 菜单位置
 * TrayIcon 的 MouseEvent 坐标在 Windows 上不可靠 (WTrayIconPeer 上报原生物理坐标, 多屏/系统
 * 缩放下与 AWT 逻辑坐标不一致, 实测菜单被推到屏幕角落), 弹出时改取 MouseInfo 指针屏幕坐标,
 * 再按点击点所在屏幕的工作区双向夹紧, 保证菜单完整落在可见区域内。
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

    /**
     * 1x1 透明占位图标: 顶掉 Windows LAF 默认的 16px 勾选列 (见 ensureNativeLookAndFeel)。
     * 不能 put null —— UIManager.put(key, null) 只是删除用户值, 会回落到 LAF 默认图标。
     */
    private val emptyIcon = object : Icon {
        override fun getIconWidth(): Int = 1

        override fun getIconHeight(): Int = 1

        override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) = Unit
    }

    private val readAloudBinding = MutableStateFlow<ReadAloudTrayBinding?>(null)

    /** 朗读控制器绑定: 阅读页启动朗读时注入, 停止朗读后置 null。 */
    var readAloud: ReadAloudTrayBinding?
        get() = readAloudBinding.value
        set(value) {
            readAloudBinding.value = value
        }

    // 状态监听作用域 (install 建 / uninstall 取消), 显隐与 refresh 在协程线程读, 故 @Volatile
    @Volatile
    private var monitorScope: CoroutineScope? = null

    /** 最近音频播放进度 (AUDIO_PROGRESS 事件驱动任务栏进度条)。 */
    @Volatile
    private var audioProgressMs: Int = 0

    /** 最近音频总时长 (AUDIO_SIZE 事件)。 */
    @Volatile
    private var audioDurationMs: Int = 0

    /** 最近音频定时分钟 (AUDIO_DS 事件, tooltip 标题倒计时显示)。 */
    @Volatile
    private var audioTimerMinute: Int = 0

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
     * 注册托盘宿主 (窗口恢复 / 退出回调) 并启动后台任务状态监听。
     *
     * 不再立即创建 [TrayIcon]: 图标显隐由后台任务状态驱动 (见 [syncVisibility]),
     * 空闲期不驻留, 首个后台任务开始时才真正 add 到 [SystemTray]。
     *
     * @param windowProvider 主窗口提供者 (左键点击 / "显示"菜单项恢复它)
     * @param exitAction 退出动作 (通常是 Compose 的 exitApplication)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun install(windowProvider: () -> Window?, exitAction: () -> Unit) {
        if (monitorScope != null) return
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return
        this.windowProvider = windowProvider
        this.exitAction = exitAction
        monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            // 音频状态: 事件仅作变化触发, 判定直接拉读会话标志 (isServiceRunning,
            // provider 保证先于广播落地, 见 DesktopAudioPlayProvider.endSession)
            s.launch {
                FlowBus.withSticky(EventBus.AUDIO_STATE).collect { syncVisibility() }
            }
            // 章节标题变化只刷 tooltip
            s.launch { FlowBus.withSticky(EventBus.AUDIO_SUB_TITLE).collect { refresh() } }
            // 定时分钟 (通知标题倒计时显示, 对照原版 playing_timer 1..60 分钟)。
            // 兼作会话建立的呈现触发: ensureRunning 必发一次 AUDIO_DS (SleepTimer.set 亦经
            // postMinute 广播), 对照原版服务 onCreate 即挂通知 —— 否则首章需先拉播放地址时,
            // 托盘/任务栏要等整个拉流结束后的首个 AUDIO_STATE 才上屏
            s.launch {
                FlowBus.withSticky(EventBus.AUDIO_DS).collect { value ->
                    if (value is Int) {
                        audioTimerMinute = value
                        syncVisibility()
                    }
                }
            }
            // 进度/总时长: 驱动任务栏进度条 (对照原版通知进度刷新)
            s.launch {
                FlowBus.withSticky(EventBus.AUDIO_PROGRESS).collect { value ->
                    if (value is Int) {
                        audioProgressMs = value
                        updateTaskbar()
                    }
                }
            }
            s.launch {
                FlowBus.withSticky(EventBus.AUDIO_SIZE).collect { value ->
                    if (value is Int) {
                        audioDurationMs = value
                        updateTaskbar()
                    }
                }
            }
            // 朗读状态: 绑定 StateFlow 首值立即下发, 保证启动期就完成一次显隐同步
            s.launch {
                readAloudBinding
                    .flatMapLatest { it?.controller?.state ?: flowOf(null) }
                    .collect { syncVisibility() }
            }
        }
        // 任务栏缩略图按钮/进度条/全局媒体键 (Windows 专属, 内部自检平台)
        DesktopTaskbarMedia.install()
        syncVisibility()
    }

    /** 卸载托盘 (进程退出前调用, 避免图标残留)。 */
    fun uninstall() {
        monitorScope?.cancel()
        monitorScope = null
        DesktopTrayNotifier.sender = null
        DesktopTaskbarMedia.uninstall()
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

    // ==================== 显隐 (后台任务驱动) ====================

    /**
     * 后台任务判定: 与 app 端 Android 前台服务语义对齐 (同一状态, 不同呈现)。
     *
     * - 音频: 会话寿命 = provider running (镜像 AudioPlayService 服务存活: stop → stopSelf
     *   终结, stopPlay 切章节不停服务)。不用 status != STOP —— 切章节拉流窗口里它会眨眼,
     *   而原版通知 (服务存活期) 全程保持
     * - 朗读: BaseReadAloudService 在 state ∈ {PLAYING, PAUSED} 时 startForeground,
     *   停止/完成/出错时 stopSelf
     *
     * 桌面端把"前台服务 + 通知"呈现为托盘图标, 判定状态源与 app 端完全一致。
     */
    private fun anyBackgroundActive(): Boolean {
        val audioActive = AudioPlayCommanders.getOrNull()?.isServiceRunning == true
        val aloud = readAloudBinding.value
        val aloudState = aloud?.controller?.state?.value
        val aloudActive =
            aloudState == ReadAloudState.PLAYING || aloudState == ReadAloudState.PAUSED
        return audioActive || aloudActive
    }

    /** 状态事件可在任意线程发出, 显隐操作统一收口到 EDT (SystemTray 状态单线程串行改)。 */
    private fun syncVisibility() {
        SwingUtilities.invokeLater {
            if (monitorScope == null) return@invokeLater
            if (anyBackgroundActive()) showTrayIcon() else hideTrayIcon()
            refresh()
            updateTaskbar()
        }
    }

    private fun showTrayIcon() {
        if (trayIcon != null) return
        if (monitorScope == null) return
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return
        val icon = TrayIcon(loadTrayImage(), appName())
        icon.isImageAutoSize = true
        // 左键单击 = 原版通知点击行为: 恢复窗口 + 跳到对应页面
        // (不加 ActionListener: Windows 上双击会与本监听重复触发)
        icon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    activateWindow()
                    jumpToActive()
                }
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
    }

    private fun hideTrayIcon() {
        val icon = trayIcon ?: return
        DesktopTrayNotifier.sender = null
        dismissMenu()
        runCatching { SystemTray.getSystemTray().remove(icon) }
            .onFailure { AppLog.put("系统托盘图标移除失败", it) }
        trayIcon = null
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
        val audioActive = AudioPlayCommanders.getOrNull()?.isServiceRunning == true
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
                lines += audioTitleLine(audioStatus, audioTimerMinute)
                lines += AudioPlayShared.durChapter?.title?.takeUnless { it.isEmpty() }
                    ?: str("audio_play_s", "点击打开播放界面")
            }

            aloudActive -> {
                lines += aloudTitleLine(aloud, aloudState)
                lines += aloud?.chapterTitle()?.takeUnless { it.isEmpty() }
                    ?: str("read_aloud_s", "点击打开阅读界面")
            }

            else -> lines += appName()
        }
        return lines.joinToString("\n").take(TOOLTIP_MAX)
    }

    /**
     * 音频标题行 (对照原版 createNotification):
     * pause → "播放暂停"; 定时 1..60 分钟 → "正在播放（还剩 N 分钟）"; 否则 "正在播放", 后接 ": 书名"
     */
    private fun audioTitleLine(audioStatus: Int, timerMinute: Int): String {
        val prefix = when {
            audioStatus == Status.PAUSE -> str("audio_pause", "播放暂停")
            timerMinute in 1..60 -> timerText("playing_timer", timerMinute)
            else -> str("audio_play_t", "正在播放")
        }
        return titleLine(prefix, AudioPlayShared.book?.name)
    }

    /** 朗读标题行 (对照原版 createNotification): 定时 > 0 分钟即显示倒计时 (与音频的 1..60 条件不同)。 */
    private fun aloudTitleLine(aloud: ReadAloudTrayBinding?, aloudState: ReadAloudState?): String {
        val prefix = when {
            aloudState == ReadAloudState.PAUSED -> str("read_aloud_pause", "朗读暂停")
            (aloud?.timeMinute() ?: 0) > 0 -> timerText("read_aloud_timer", aloud!!.timeMinute())
            else -> str("read_aloud_t", "正在朗读")
        }
        return titleLine(prefix, aloud?.bookName())
    }

    /** 定时倒计时文案 (jvmGetString 支持 %d 占位符; 缺 key 时兜底)。 */
    private fun timerText(key: String, minute: Int): String {
        val s = jvmGetString(key, minute)
        return if (s != key) s else "$key $minute"
    }

    private fun titleLine(prefix: String, bookName: String?): String =
        if (bookName.isNullOrEmpty()) prefix else "$prefix: $bookName"

    /** 任务栏缩略图按钮/进度条刷新 (Windows 专属, 内部自检)。 */
    private fun updateTaskbar() {
        DesktopTaskbarMedia.update(
            audioStatus = AudioPlayShared.status,
            aloud = readAloudBinding.value,
            progressMs = audioProgressMs,
            durationMs = audioDurationMs,
        )
    }

    // ==================== 跳转 (对照原版通知点击行为) ====================

    /**
     * 跳到当前后台任务对应的页面 (原版通知 contentIntent 的桌面等价):
     * - 音频活跃: 打开音频播放页 (对照 AudioPlayActivity)
     * - 朗读活跃: 打开阅读页 (对照 ReaderActivity)
     * 经 AppNavigatorProviders 全局导航 (非 Composable 代码的既有入口)。
     */
    private fun jumpToActive() {
        val navigator = AppNavigatorProviders.getOrNull() ?: return
        val audioActive = AudioPlayCommanders.getOrNull()?.isServiceRunning == true
        val aloud = readAloudBinding.value
        val aloudActive = aloud?.controller?.state?.value?.let {
            it == ReadAloudState.PLAYING || it == ReadAloudState.PAUSED
        } ?: false
        val route = when {
            // 音频优先 (与托盘菜单/媒体键一致)
            audioActive -> AudioPlayShared.book?.toReadRoute()
            aloudActive -> ActiveReadBookRegistry.current?.bookValue?.toReadRoute()
            else -> null
        }
        if (route != null) {
            runCatching { navigator.push(route) }
                .onFailure { AppLog.put("托盘跳转书籍失败", it) }
        }
    }

    // ==================== 菜单 ====================

    private fun maybeShowMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        // TrayIcon 的 MouseEvent 坐标不可靠: Windows 上 WTrayIconPeer 上报的是原生物理坐标,
        // 多屏/系统缩放下与 AWT 逻辑坐标不一致, 直接使用实测会把菜单推到屏幕角落。
        // 右键时指针必然停在托盘图标上, 取指针的 AWT 逻辑屏幕坐标最稳。
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        val x = pointer?.x ?: e.x
        val y = pointer?.y ?: e.y
        // Swing JPopupMenu 非模态 (轻量组件, show 后 EDT 继续), 可直接调用;
        // 菜单打开时再次右键由 Swing 自行处理 (点击外部关闭), 无队列阻塞问题
        runCatching { showMenu(x, y) }.onFailure { AppLog.put("托盘菜单弹出失败", it) }
    }

    /**
     * 在托盘点击处弹出菜单 (Swing JPopupMenu, 全平台统一)。
     * 位置自己算而不是交给 [JPopupMenu] 的越界翻转 —— 后者按整块屏幕算, 会把菜单压到
     * 任务栏底下 (实测末项被遮住); 这里按"工作区"(屏幕减去任务栏 inset) 夹紧,
     * 底部任务栏时向上展开。
     */
    private fun showMenu(x: Int, y: Int) {
        if (trayIcon == null) return
        ensureNativeLookAndFeel()
        val menu = buildMenu()
        val anchor = anchorDialog ?: createAnchor().also { anchorDialog = it }
        val area = workArea(x, y)
        val size = menu.preferredSize
        // 优先在指针上方展开 (底部任务栏场景), 放不下则向下; 两个方向都夹进工作区,
        // 防异常坐标 (TrayIcon 事件坐标在缩放下越界) 把菜单推出屏幕或压到任务栏底下
        val left = (area.x + area.width - size.width).coerceAtLeast(area.x)
        val px = x.coerceIn(area.x, left)
        val py = (if (y - size.height >= area.y) y - size.height else y)
            .coerceAtMost(area.y + area.height - size.height)
            .coerceAtLeast(area.y)
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

    /**
     * 托盘菜单是进程内唯一 Swing UI, 用系统 LAF 保持原生菜单外观 (字体自动取 win.menu.font)。
     *
     * 顺带修正无图标菜单的文本位置: Windows LAF 默认给每个菜单项保留 16px 勾选列 +
     * afterCheckIconGap, 并把文本起点抬到 "MenuItem.minimumTextOffset" (31px) —— 那是为
     * 带图标/勾选的原生菜单留的; 本菜单无图标, 不清理的话文本被推到行右侧 (左侧大片空白)。
     * 勾选列换成 1x1 透明占位 (不能 put null: UIManager 会回落 LAF 默认图标), 起点归零。
     * 全局 UIManager.put 安全: 全应用只有本托盘菜单一个 Swing 菜单 (其余 UI 走 Compose)。
     */
    private fun ensureNativeLookAndFeel() {
        if (lookAndFeelReady) return
        lookAndFeelReady = true
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        UIManager.put("MenuItem.minimumTextOffset", 0)
        UIManager.put("MenuItem.checkIcon", emptyIcon)
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

    /**
     * 菜单条目模型 (Swing 与 Win32 原生菜单共用): label=null 为分隔线;
     * action=null 且禁用 = 分组标题。
     */
    private class MenuEntry(
        val label: String? = null,
        val enabled: Boolean = true,
        val action: (() -> Unit)? = null,
    )

    /** 菜单内容单一来源 (Swing JPopupMenu 渲染; 音频/朗读两链共用, 防漂移)。 */
    private fun buildMenuModel(): List<MenuEntry> {
        val entries = ArrayList<MenuEntry>()
        val audioStatus = AudioPlayShared.status
        val audioActive = AudioPlayCommanders.getOrNull()?.isServiceRunning == true
        val aloud = readAloudBinding.value
        val aloudState = aloud?.controller?.state?.value
        val aloudActive = aloudState == ReadAloudState.PLAYING || aloudState == ReadAloudState.PAUSED
        // 两条链同时活跃时加标题分组, 免得两组"上一章"分不清 (文案取 app 端通知 subText)
        val grouped = audioActive && aloudActive
        if (audioActive) {
            if (grouped) entries += MenuEntry(str("audio", "音频"), enabled = false)
            addAudioEntries(entries, audioStatus)
        }
        if (aloudActive) {
            if (grouped) entries += MenuEntry(str("read_aloud", "朗读"), enabled = false)
            addReadAloudEntries(entries, aloud, aloudState == ReadAloudState.PAUSED)
        }
        if (audioActive || aloudActive) entries += MenuEntry() // 分隔线
        // 无"显示"项: 恢复窗口 + 跳转对应页面 (原版通知点击行为) 由托盘左键单击承载
        entries += MenuEntry(str("exit", "退出")) { exitAction?.invoke() }
        return entries
    }

    private fun buildMenu(): JPopupMenu {
        val menu = JPopupMenu()
        buildMenuModel().forEach { e ->
            when {
                e.label == null -> menu.addSeparator()
                e.action == null -> menu.add(header(e.label))
                else -> menu.add(item(e.label, e.action))
            }
        }
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

    /** 对照 AudioPlayService 通知 action: Timer / 上一章 / 播放暂停 / 下一章 / 停止。 */
    private fun addAudioEntries(entries: MutableList<MenuEntry>, audioStatus: Int) {
        val paused = audioStatus == Status.PAUSE
        val toggleLabel = if (paused) str("resume", "继续") else str("pause", "暂停")
        // 原版通知第 1 个 action 是 Timer (addTimer 每次 +10 分钟, 180 封顶再按归零)
        entries += MenuEntry(str("set_timer", "定时")) { AudioPlayShared.addTimer() }
        entries += MenuEntry(toggleLabel) {
            if (paused) AudioPlayShared.resume() else AudioPlayShared.pause()
        }
        entries += MenuEntry(str("previous_chapter", "上一章")) { AudioPlayShared.prev() }
        entries += MenuEntry(str("next_chapter", "下一章")) { AudioPlayShared.next() }
        // app 端通知 stop → stopSelf, 对应 shared stop() (含 saveRead), 非 stopPlay()
        entries += MenuEntry(str("stop", "停止")) { AudioPlayShared.stop() }
    }

    /** 对照 BaseReadAloudService 通知 action + IntentAction.prev/nextParagraph。 */
    private fun addReadAloudEntries(
        entries: MutableList<MenuEntry>,
        aloud: ReadAloudTrayBinding,
        paused: Boolean,
    ) {
        val controller = aloud.controller
        val toggleLabel = if (paused) str("resume", "继续") else str("pause", "暂停")
        entries += MenuEntry(toggleLabel) {
            if (paused) controller.resume() else controller.pause()
        }
        entries += MenuEntry(str("read_aloud_prev_paragraph", "朗读上一段")) {
            controller.prevParagraph()
        }
        entries += MenuEntry(str("read_aloud_next_paragraph", "朗读下一段")) {
            controller.nextParagraph()
        }
        entries += MenuEntry(str("previous_chapter", "上一章")) { controller.prevChapter() }
        entries += MenuEntry(str("next_chapter", "下一章")) { controller.nextChapter() }
        entries += MenuEntry(str("stop", "停止")) { controller.stop() }
    }

    private fun header(label: String): JMenuItem = JMenuItem(label).apply {
        isEnabled = false
    }

    private fun item(label: String, action: () -> Unit): JMenuItem =
        JMenuItem(label).apply {
            addActionListener { runCommand(action) }
        }

    /** 菜单命令切出 EDT 执行: 播放命令内部会落库/起协程, 不该压在 AWT 事件线程上。 */
    private fun runCommand(action: () -> Unit) {
        val s = monitorScope ?: return
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
                ?.getResourceAsStream("icon.png")?.use { decodeBytesSampled(it.readBytes(), 0) }
                ?.toAwtImage()
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
