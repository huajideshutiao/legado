package io.legado.desktop.ui.platform

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.sun.jna.Platform
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.AppWebDavShared
import io.legado.app.help.book.BookImageStorageProviders
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.ThemeConfigProviders
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.help.image.ReaderImageCache
import io.legado.app.help.showSourceLogin
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.help.storage.DataStorageProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.read.ReadAloudControls
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.ReadMenuAction
import io.legado.app.ui.book.read.ReadMenuColors
import io.legado.app.ui.book.read.ReadMenuController
import io.legado.app.ui.book.read.ReadMenuState
import io.legado.app.ui.book.read.ReaderDialogEvent
import io.legado.app.ui.book.read.ReaderPlatformProvider
import io.legado.app.ui.book.read.ReaderScreenModel
import io.legado.app.ui.book.read.SourceAction
import io.legado.app.ui.book.read.TopMenuState
import io.legado.app.ui.book.read.createReadMenuColors
import io.legado.app.ui.book.read.hasBgImageByPath
import io.legado.app.ui.book.read.page.AutoPagerCompose
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.dict.DictDialogHost
import io.legado.app.ui.reader.TextSelectionDialog
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.RouteResults
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.widget.dialog.encodePhotoOverlayPayload
import io.legado.app.utils.FileUtilsBase
import io.legado.app.utils.FlowBus
import io.legado.desktop.help.DesktopBattery
import io.legado.desktop.help.tts.DesktopReadAloudHost
import io.legado.desktop.ui.DesktopDialogRequest
import io.legado.desktop.ui.DesktopDialogs
import io.legado.desktop.ui.DesktopPlatformCapabilities
import io.legado.desktop.ui.DesktopWindowChrome
import io.legado.desktop.ui.component.FileDialogs
import io.legado.desktop.ui.readerWindowTint
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * desktop 端 [ReaderPlatformProvider] 真实实现: 菜单可见/可切, 导航经 [AppNavigator] 桥接。
 *
 * 对照 app 端 [io.legado.app.ui.book.read.AndroidReaderPlatformProvider]:
 * - createMenuController: 返回真实 [DesktopReadMenuController] (visibleState 可切, 非恒 false)
 * - getBatteryLevel: Windows 经 kernel32 (JNA) / macOS 经 `pmset -g batt` /
 *   Linux 经 sysfs BAT/capacity 读真实电量, 无电池/失败回落 100 (信息条恒显示电量)
 * - 顶/底栏菜单 UI 由 shared [io.legado.app.ui.book.read.ReadMenuOverlay] 渲染, 此处只持有状态
 * - 导航回调 (clickCatalog/clickSearch/clickFont/clickSetting 等) 经 [AppNavigator] 跳 shared Route
 * - 章节导航 (clickPre/clickNext/onSeekStop) 委托 [ReaderScreenModel.viewModel]
 *
 * # 不实现 (与 app 端差异)
 * - ReadAloud (朗读): app 端走 ReadAloud + BaseReadAloudService, desktop 无 Service,
 *   改由 [DesktopReadAloudHost] 驱动 ReadAloudControllerShared; 长按弹共享朗读控制面板
 * - ThemeConfig.applyDayNight: app 端切夜间主题后调 Activity 重启 UI, desktop 经
 *   [io.legado.app.help.config.ThemeConfigProviders] (FileThemeConfigProvider.applyDayNight)
 *   写 ThemeStore 色 + themeMode 并 emit RECREATE, AppTheme 重组重读新色
 * - 沉浸式色彩 (immersive/bgColor/textColor): 对照原版 ReadMenu.upColorConfig, 纯色阅读背景时
 *   菜单栏跟随阅读背景色+文字色 (shared createReadMenuColors); 图片阅读背景时走 AppTheme 默认色
 */
class DesktopReaderPlatformProvider : ReaderPlatformProvider {

    /** 长按文字选择请求 (对照 app 端 MainActivity.readerSelection, 由 [TextSelectionHost] 渲染)。 */
    internal var readerSelection by mutableStateOf<ReaderTextSelection?>(null)
        private set

    /** 窗口标题栏着色协程 (阅读页激活期间订阅背景色变化, 见 [readerWindowTint])。 */
    private var titleBarTintJob: Job? = null

    /** 标题栏着色协程作用域 (Main)。 */
    private val titleBarScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 阅读页激活期间挂窗口激活监听的目标模型 (onEnter 设置, onExit 清除)。 */
    private var lifecycleScreenModel: ReaderScreenModel? = null

    /** 窗口激活监听: 主窗口失活=其他应用在前台 (对应原版 Activity.onPause 语义), 重新激活=前台。
     *  进程内对话框/菜单同属一个 ComposeWindow, 不触发失活事件。
     *  阅读页激活期间注册, 退出阅读页注销 (对照 app 端 LifecycleObserver 桥)。 */
    private val lifecycleListener = object : AWTEventListener {
        override fun eventDispatched(event: AWTEvent) {
            val model = lifecycleScreenModel ?: return
            when (event.id) {
                WindowEvent.WINDOW_DEACTIVATED -> model.onPause()
                WindowEvent.WINDOW_ACTIVATED -> model.onResume()
            }
        }
    }

    /** 图片保存协程作用域 (Main: toast 需主线程; 下载/写盘在 IO 块内切换)。 */
    private val imageActionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 文字选择对话框协程作用域 (整章正文异步加载: 读缓存 + ContentProcessor, 完成后弹框)。 */
    private val selectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 触发标题栏着色刷新的配置变更集合。 */
    private val titleBarTintChanges = setOf(
        ReadConfigChange.BG,
        ReadConfigChange.BG_ALPHA,
        ReadConfigChange.STYLE,
    )

    /** 查词请求 (TextSelectionDialog 查词按钮 → onDict 回调暂存, 由 [TextSelectionHost] 渲染词典对话框)。 */
    internal var dictWord by mutableStateOf<String?>(null)
        private set

    /** 图片长按菜单请求 (长按坐标 + 动作上下文, 由 [ImageActionMenuHost] 渲染)。 */
    internal var imageActionMenu by mutableStateOf<ReaderImageMenuState?>(null)
        private set

    override fun createMenuController(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadMenuController = DesktopReadMenuController(navigator, screenModel)

    // 自动翻页面板停止按钮 (对照 app 端 autoPageStop → stopAutoPage: 停控制器 + 复位开关)
    override fun autoPageStop(screenModel: ReaderScreenModel) {
        (screenModel.menuController.state as? DesktopReadMenuState)?.stopAutoPage()
    }

    // 设置按钮 → 翻页动画配置 (对照 app 端 showPageAnimConfigSelector: 选择器回调忽略索引,
    // 实际动画值在界面设置弹窗配置, 只触发 upPageAnim + 重载; 与菜单 PAGE_ANIM 分支同语义)
    override fun showPageAnimConfig(screenModel: ReaderScreenModel) {
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("page_anim_config"))
    }

    // 自动翻页滑条抬手 → 重新应用当前 TTS 语速 (对照 app 端 upTtsSpeechRate: 重读配置 +
    // pause/resume 让新语速立刻作用到当前段; 本方法不写配置, 只按现配置重放)
    override fun upTtsSpeechRate(screenModel: ReaderScreenModel) {
        val prefs = runCatching { PreferenceProviders.get() }.getOrNull() ?: return
        val rate = if (prefs.getBoolean(PreferKey.ttsFollowSys, true)) {
            5
        } else {
            prefs.getInt(PreferKey.ttsSpeechRate, 5)
        }
        DesktopReadAloudHost.setSpeechRate(rate)
    }

    override fun getBatteryLevel(): Int = DesktopBattery.getBatteryLevel()

    /**
     * 阅读页进入: 把窗口系统标题栏着色为阅读背景色 (视为状态栏着色,
     * 对照 app 端状态栏随阅读背景变化; 用户要求 2026-08-06)。
     * 订阅配置变更实时刷新; 阅读页退出 ([onExit]) 时清除回落 AppTheme 主题色。
     */
    override fun onEnter(screenModel: ReaderScreenModel) {
        // 窗口激活监听 → shared ScreenModel (对照 app 端 LifecycleObserver 桥:
        // 失活时 onPause 计时结束+进度落库上传+取消预下载, 激活时 onResume 开始计时+web 进度恢复)
        lifecycleScreenModel = screenModel
        Toolkit.getDefaultToolkit().addAWTEventListener(
            lifecycleListener,
            AWTEvent.WINDOW_EVENT_MASK,
        )
        titleBarTintJob?.cancel()
        titleBarTintJob = titleBarScope.launch {
            fun updateTint() {
                val color = runCatching {
                    ReadBookConfigProviders.get().config.curBgColor()
                }.getOrNull() ?: return
                // 标题栏不着半透明, 取不透明阅读背景色
                readerWindowTint.value = Color(color).copy(alpha = 1f)
            }
            updateTint()
            // RECREATE 也要刷: 切日/夜换的是 curBgColor 的日/夜分支, 不发 ReadConfigChange,
            // app 端靠 Activity 重启重取, 桌面端只重组 ⇒ 漏订阅时控制条残留上一套底色
            merge(
                ReadBookEvents.configChange.filter { changes ->
                    changes.any { it in titleBarTintChanges }
                },
                FlowBus.with(EventBus.RECREATE),
            ).collect { updateTint() }
        }
    }

    /** 阅读页退出: 清除标题栏着色, 回落 AppTheme 主题色; 收起图片长按菜单避免残留。 */
    override fun onExit(screenModel: ReaderScreenModel) {
        Toolkit.getDefaultToolkit().removeAWTEventListener(lifecycleListener)
        lifecycleScreenModel = null
        titleBarTintJob?.cancel()
        titleBarTintJob = null
        readerWindowTint.value = null
        imageActionMenu = null
    }

    override fun onLongPress(screenModel: ReaderScreenModel) {
        // 对照 app 端 AndroidReaderPlatformProvider.onLongPress: 携带章节名 + 整章正文,
        // 由共享 TextSelectionDialog (SelectionContainer 包 Text) 承载拖选/复制/查词。
        // 文字长按已由页内选择接管 (ReadViewComposable → onTextSelected); 此处为图片/
        // 空白长按回落路径。整章正文需异步加载 (读缓存 + ContentProcessor, 对照原版
        // ContentEditViewModel.initContent), 不再用当前页文本 (curTextPage)。
        val chapterName = screenModel.currentChapter?.title.orEmpty()
        selectionScope.launch {
            val content = screenModel.loadChapterFullText().orEmpty()
            readerSelection = ReaderTextSelection(
                chapterName = chapterName,
                content = content,
                onReplace = onReplace(screenModel),
                onBookmark = onBookmark(screenModel),
                onReadAloud = onReadAloud(),
                onSearchContent = onSearchContent(screenModel),
                onShare = onShare(),
            )
        }
    }

    /** 页内文字选择完成: 复用共享 [TextSelectionDialog], 注入选中文本 (锚点坐标桌面端对话框形态不使用)。
     *  对话框独立承载选中文本, 页内高亮不再需要, 立即清除选择 (移动端浮动菜单是
     *  onMenuActionFinally 才清的原版语义, 桌面端对话框形态直接清, 2026-08-06 用户要求)。 */
    override fun onTextSelected(
        screenModel: ReaderScreenModel,
        text: String,
        anchorX: Float,
        anchorY: Float,
    ) {
        if (text.isBlank()) return
        // 弹对话框即清除页内选择 (读选区已完成, 高亮不再有意义且干扰后续点按)
        ReadBookEvents.postSelectionCancel()
        // 整章正文异步加载 (用于内容区拖选 + "复制全部"), 不再用当前页文本;
        // 加载完成后一并弹框 (内容区展示选中文本, 无需再等待)
        val chapterName = screenModel.currentChapter?.title.orEmpty()
        selectionScope.launch {
            val content = screenModel.loadChapterFullText().orEmpty()
            readerSelection = ReaderTextSelection(
                chapterName = chapterName,
                content = content,
                selectedText = text,
                onReplace = onReplace(screenModel),
                onBookmark = onBookmark(screenModel),
                onReadAloud = onReadAloud(),
                onSearchContent = onSearchContent(screenModel),
                onShare = onShare(),
            )
        }
    }

    /**
     * 图片长按: 弹图片操作菜单 (查看大图/刷新/保存, 对齐移动端三项; 对照原版
     * ReadBookActivity.onImageLongPress 的 show/refresh/save 三分支, "选择目录"
     * iOS/鸿蒙均未做故桌面也不做)。菜单由 [ImageActionMenuHost] 锚定在长按坐标处渲染。
     */
    override fun onImageLongPress(
        screenModel: ReaderScreenModel,
        src: String,
        x: Float,
        y: Float,
    ) {
        if (src.isBlank()) return
        imageActionMenu = ReaderImageMenuState(screenModel, src, x, y)
    }

    /** 查看大图: 弹共享全屏大图 Overlay (key="photo" → PhotoViewOverlayDialog, 全屏黑底+缩放;
     *  带书源身份走防盗链 header + coverDecodeJs 封面解密, 与列表封面同款身份, 本地书不传)。
     *  payload 携带当前章节索引: 对话框优先查阅读时已落盘的章节图片缓存
     *  (BookImageStorage, 对照原版 PhotoDialog.loadPhoto 的章节缓存文件分支)。 */
    private fun viewImage(screenModel: ReaderScreenModel, src: String) {
        val book = screenModel.currentBook
        AppNavigatorProviders.getOrNull()?.showOverlay(
            AppOverlay.Dialog(
                key = "photo",
                payload = encodePhotoOverlayPayload(
                    src, screenModel.viewModel.durChapterIndex.value
                ),
                sourceOrigin = book?.origin?.takeIf { !book.isLocal && it.isNotBlank() },
            )
        )
    }

    /**
     * 刷新图片: 删该图磁盘缓存文件 + 清共享内存缓存 + 重排 (对照原版 viewModel.refreshImage
     * 的删缓存文件+清内存缓存+loadContent; 桌面阅读页图片走 ReaderImageResolver → 磁盘
     * BookImageStorage 缓存, 只清内存缓存会读到旧磁盘字节, 故按单图删文件, 其余图片缓存不动)。
     */
    private fun refreshImage(screenModel: ReaderScreenModel, src: String) {
        val book = screenModel.currentBook
        val chapter = screenModel.currentChapter
        if (book != null && chapter != null) {
            BookImageStorageProviders.get().getImagePath(book, chapter, src)?.let { File(it).delete() }
        }
        ReaderImageCache.clear()
        ReadBookEvents.postConfig(ReadConfigChange.LOAD_CONTENT)
    }

    /** 保存图片: 原生保存对话框选路径 → 下载解码字节 (书源防盗链+解密链路) → 落盘, toast 提示路径。 */
    private fun saveImage(screenModel: ReaderScreenModel, src: String) {
        val book = screenModel.currentBook
        val bookSource = screenModel.viewModel.bookSource.value
        imageActionScope.launch {
            Toasters.get().toast("正在保存")
            // 阻塞式选择器必须切 IO (对照漫画阅读页 onSaveImage 的 withContext(IoDispatcher))
            val destPath = withContext(Dispatchers.IO) {
                FileDialogs.pickSaveFile(
                    title = "保存图片",
                    defaultName = "legado-${System.currentTimeMillis()}.jpg",
                    initialDir = defaultImageSaveDir(),
                )?.absolutePath
            } ?: return@launch
            val savedPath = withContext(Dispatchers.IO) {
                writeImageBytes(src, book, bookSource, File(destPath))
            }
            Toasters.get().toast(if (savedPath != null) "保存成功\n$savedPath" else "保存失败")
        }
    }

    /**
     * 选择目录保存 (T8, 对照 app 端 showImageActionMenu 的 selectFolder 分支):
     * 阻塞式选目录 → 在该目录下落盘当前图（文件名取时间戳，建议名 .jpg, 写入后按魔数修正扩展名）。
     * 桌面无 SAF 默认保存目录持久化 (app 端 ACache.imagePathKey), 故"选择目录"直接把当前图存进所选目录,
     * 语义对齐 app 的 selectImageDir 分支 (选目录后继续保存)。
     */
    private fun saveImageToSelectedDir(screenModel: ReaderScreenModel, src: String) {
        val book = screenModel.currentBook
        val bookSource = screenModel.viewModel.bookSource.value
        imageActionScope.launch {
            Toasters.get().toast("正在保存")
            val dir = withContext(Dispatchers.IO) {
                FileDialogs.pickDirectory(title = "选择保存目录")
            } ?: return@launch
            val savedPath = withContext(Dispatchers.IO) {
                writeImageBytes(
                    src, book, bookSource,
                    File(dir, "legado-${System.currentTimeMillis()}.jpg"),
                )
            }
            Toasters.get().toast(if (savedPath != null) "保存成功\n$savedPath" else "保存失败")
            // 目录选择后作为后续保存对话框默认起始目录 (对照 app 端写入 imagePathKey 的记忆语义)
            if (savedPath != null && dir.isDirectory) {
                lastImageSaveDir = dir
            }
        }
    }

    /** 下载解码字节写入 [dest], 返回最终绝对路径 (写入后按魔数修正扩展名); 失败返回 null。 */
    private suspend fun writeImageBytes(
        src: String,
        book: Book?,
        bookSource: io.legado.app.data.entities.BookSource?,
        dest: File,
    ): String? = try {
        val bytes = ImageBitmapLoader().loadBytes(src, book, bookSource)
            ?: return null
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        // 建议名恒为 .jpg, 与实际格式不符时按魔数改名 (shared 统一 helper, 与漫画保存一致)
        FileUtilsBase.fixImageExtension(dest).absolutePath
    } catch (e: Exception) {
        AppLog.put("保存图片出错\n${e.localizedMessage}", e)
        null
    }

    /** 保存对话框默认起始目录 (用户可见产物目录 桌面/legado, 与漫画保存一致)。 */
    private fun defaultImageSaveDir(): File? = runCatching {
        (lastImageSaveDir ?: File(DataStorageProviders.get().userExportDir))
            .takeIf { it.isDirectory || it.mkdirs() }
    }.getOrNull()

    /** 最近一次"选择目录"保存时选定的目录 (T8, 供后续"保存"对话框默认起始目录)。 */
    @Volatile
    private var lastImageSaveDir: File? = null

    /** 替换 (T1: 已提共享 ReaderScreenModel.replaceTextCallback, 保留闭包装配) */
    private fun onReplace(screenModel: ReaderScreenModel): (String) -> Unit =
        screenModel.replaceTextCallback()

    /** 书签 (T1: 已提共享 ReaderScreenModel.bookmarkTextCallback) */
    private fun onBookmark(screenModel: ReaderScreenModel): (String) -> Unit =
        screenModel.bookmarkTextCallback()

    /**
     * 朗读选中文字 (T7: 按 contentSelectSpeakMod 偏好, 对照 app 端 AndroidReaderPlatformProvider.
     * onReadAloud 语义: ==1 朗读当前进度章节; 桌面无单句 TTS, 否则分支也回退为朗读当前进度章节)。
     */
    private fun onReadAloud(): (String) -> Unit = { _ ->
        when (
            PreferenceProviders.get().getInt(PreferKey.contentSelectSpeakMod, 0)
        ) {
            1 -> DesktopReadAloudHost.play()
            // 桌面端无单句 TTS (无 View 层选区/无系统单句 TTS 引擎), 回退从当前进度朗读章节
            else -> DesktopReadAloudHost.play()
        }
    }

    /** 全文搜索 (T1: 已提共享 ReaderScreenModel.searchContentTextCallback) */
    private fun onSearchContent(screenModel: ReaderScreenModel): (String) -> Unit =
        screenModel.searchContentTextCallback()

    /** 分享 (对照原版 menu_share_str; 桌面端无系统分享 UI, 写剪贴板) */
    private fun onShare(): (String) -> Unit = { text ->
        DesktopPlatformCapabilities.shareText(text)
    }

    /**
     * 长按文字选择对话框宿主 (挂在桌面 Compose 根, 对照 app 端 MainActivity 的
     * `readerSelection?.let { TextSelectionDialog(...) }` 分支)。剪贴板读写 / 打开外链
     * 走 [DesktopPlatformCapabilities], 复用共享 [TextSelectionDialog] 不另写一套。
     */
    @Composable
    fun TextSelectionHost() {
        val selection = readerSelection
        if (selection != null) {
            TextSelectionDialog(
                chapterName = selection.chapterName,
                content = selection.content,
                onDismiss = { readerSelection = null },
                clipTextProvider = { DesktopPlatformCapabilities.getClipboardText() },
                clipTextSink = { DesktopPlatformCapabilities.copyToClipboard(it) },
                openUrl = { DesktopPlatformCapabilities.openExternalUrl(it) },
                // 查词: 打开共享词典查询对话框 (对照原版 TextActionMenu menu_dict → DictDialog)
                onDict = { dictWord = it },
                selectedText = selection.selectedText,
                onReplace = selection.onReplace,
                onBookmark = selection.onBookmark,
                onReadAloud = selection.onReadAloud,
                onSearchContent = selection.onSearchContent,
                onShare = selection.onShare,
            )
        }
        // 查词对话框 (选中词/剪贴板词 → 词典查询, 本地/在线词典规则)
        val word = dictWord
        if (word != null) {
            DictDialogHost(
                word = word,
                onDismiss = { dictWord = null },
            )
        }
    }

    /**
     * 图片长按菜单宿主 (挂在桌面 Compose 根, 与 [TextSelectionHost] 并列; 对照
     * IosImageActionMenu 浮动菜单, 桌面用 AppDropdownMenu 锚定在长按坐标处)。
     *
     * 坐标换算: 长按回调坐标是阅读视口局部坐标, 宿主在窗口根坐标空间; 非 mac/非全屏时
     * 自绘控制栏占顶部 40dp (DesktopTitleBar 同款高度 token), 锚点 y 加回该偏移。
     */
    @Composable
    fun ImageActionMenuHost() {
        val menu = imageActionMenu ?: return
        val titleBarTopPx = if (Platform.isMac() || DesktopWindowChrome.fullscreen) {
            0f
        } else {
            with(LocalDensity.current) { AppTheme.DesignTokens.viewHeightLarge.toPx() }
        }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.offset {
                    IntOffset(menu.x.roundToInt(), (menu.y + titleBarTopPx).roundToInt())
                }
            ) {
                AppDropdownMenu(expanded = true, onDismissRequest = { imageActionMenu = null }) {
                    // 查看大图 (对照原版 show → PhotoDialog)
                    DropdownMenuItem(
                        onClick = {
                            imageActionMenu = null
                            viewImage(menu.screenModel, menu.src)
                        }
                    ) {
                        Text("查看大图")
                    }
                    // 刷新 (对照原版 refresh → refreshImage)
                    DropdownMenuItem(
                        onClick = {
                            imageActionMenu = null
                            refreshImage(menu.screenModel, menu.src)
                        }
                    ) {
                        Text("刷新")
                    }
                    // 保存 (对照原版 save → saveImage; 桌面无相册, 走文件保存对话框)
                    DropdownMenuItem(
                        onClick = {
                            imageActionMenu = null
                            saveImage(menu.screenModel, menu.src)
                        }
                    ) {
                        Text("保存")
                    }
                    // 选择目录 (T8: 对照原版 selectFolder → 选目录后保存; 桌面无 SAF 默认保存目录
                    // 持久化, 直接选目录并保存当前图, 语义对齐 app 的 selectImageDir 分支)
                    DropdownMenuItem(
                        onClick = {
                            imageActionMenu = null
                            saveImageToSelectedDir(menu.screenModel, menu.src)
                        }
                    ) {
                        Text("选择目录")
                    }
                }
            }
        }
    }

    override fun readAloudControls(
        navigator: AppNavigator,
        screenModel: ReaderScreenModel,
    ): ReadAloudControls = DesktopReadAloudControls(navigator, screenModel)
}

/** 长按文字选择请求载荷: 章节名 + 整章正文 (+ 可选页内选中文本, null=整章拖选形态) +
 *  动作回调 (对照 app 端 MainActivity.ReaderTextSelection, 由选中对话框按钮触发)。 */
internal data class ReaderTextSelection(
    val chapterName: String,
    val content: String,
    val selectedText: String? = null,
    val onReplace: (String) -> Unit = {},
    val onBookmark: (String) -> Unit = {},
    val onReadAloud: (String) -> Unit = {},
    val onSearchContent: (String) -> Unit = {},
    val onShare: (String) -> Unit = {},
)

/** 图片长按菜单请求载荷: 长按图片 src + 视口坐标 + 阅读上下文 (由
 *  [DesktopReaderPlatformProvider.ImageActionMenuHost] 渲染)。 */
internal data class ReaderImageMenuState(
    val screenModel: ReaderScreenModel,
    val src: String,
    val x: Float,
    val y: Float,
)

/**
 * 桌面端朗读控制桥: 面板动作落到 [DesktopReadAloudHost] + 偏好项。
 *
 * 语速/跟随系统/定时默认值直接读写 PreferKey (与原版 AppConfig 同 key), 桌面端没有
 * AppConfig setter, 与 [DesktopReadMenuState] 写 themeMode 的做法一致。
 */
private class DesktopReadAloudControls(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadAloudControls {

    private val prefs get() = PreferenceProviders.get()

    override val isPlaying: Boolean get() = !DesktopReadAloudHost.isPause

    override val timerMinute: Int
        get() = DesktopReadAloudHost.timeMinute
            .takeIf { it > 0 }
            ?: prefs.getInt(PreferKey.ttsTimer, 0)

    override val speechRate: Int get() = prefs.getInt(PreferKey.ttsSpeechRate, 5)

    override val followSys: Boolean get() = prefs.getBoolean(PreferKey.ttsFollowSys, true)

    override fun playPause() = DesktopReadAloudHost.toggle()

    override fun stop() = DesktopReadAloudHost.stop()

    override fun prevChapter() {
        screenModel.viewModel.moveToPrevChapter()
    }

    override fun nextChapter() {
        screenModel.viewModel.moveToNextChapter()
    }

    override fun prevParagraph() = DesktopReadAloudHost.prevParagraph()

    override fun nextParagraph() = DesktopReadAloudHost.nextParagraph()

    override fun setTimer(minute: Int) {
        DesktopReadAloudHost.setTimer(minute)
    }

    override fun setSpeechRate(rate: Int) {
        prefs.putInt(PreferKey.ttsSpeechRate, rate.coerceIn(0, 45))
        DesktopReadAloudHost.setSpeechRate(rate)
    }

    override fun setFollowSys(follow: Boolean) {
        prefs.putBoolean(PreferKey.ttsFollowSys, follow)
        // 跟随系统时回落默认语速 (对照原版 AppConfig.speechRatePlay)
        DesktopReadAloudHost.setSpeechRate(if (follow) 5 else speechRate)
    }

    override fun openChapterList() {
        // 对照原版 朗读面板目录按钮 → TocDialog 底部弹窗
        screenModel.postDialogEvent(ReaderDialogEvent.Toc)
    }

    override fun openSettings() {
        // 对照原版 ReadAloudDialog 设置按钮 → ReadAloudConfigDialog
        screenModel.postDialogEvent(ReaderDialogEvent.ReadAloudConfig)
    }

    override fun toBackstage() {
        navigator.pop()
    }
}

private class DesktopReadMenuController(
    navigator: AppNavigator,
    screenModel: ReaderScreenModel,
) : ReadMenuController {
    override val state: ReadMenuState = DesktopReadMenuState(navigator, screenModel)
    override fun showMenu() = (state as DesktopReadMenuState).show()
    override fun hideMenu() = (state as DesktopReadMenuState).hide()
}

/**
 * desktop 阅读菜单状态: visibleState 可切 (非恒 false), 字段从 screenModel.viewModel 取实时值。
 *
 * 颜色用 AppTheme 默认 (非沉浸式); 菜单显隐时刷新动态项 (顶栏可见性/书源按钮/章节信息),
 * 行为对齐 app 端 AndroidReaderMenuState.show()。
 */
private class DesktopReadMenuState(
    private val navigator: AppNavigator,
    private val screenModel: ReaderScreenModel,
) : ReadMenuState {

    /** 自动翻页控制器协程作用域 (对照 app 端 AndroidReaderMenuState.autoPageScope, Main)。 */
    private val autoPageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 章节链接开窗: 查询书源 + startBrowser 开窗在 IO 线程 (AnalyzeUrl 可能执行 header JS)。 */
    private val chapterLinkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val visibleState = MutableTransitionState(false)
    override var animate: Boolean = true
        private set
    override val isVisible: Boolean get() = visibleState.currentState || visibleState.targetState
    override val canShowMenu: Boolean get() = true

    // 菜单栏配色 (对照原版 ReadMenu.upColorConfig, 逻辑见 shared createReadMenuColors):
    // 纯色阅读背景时 immersive=true, 顶/底栏用阅读背景色(含 bgAlpha 透明度)+阅读文字色,
    // 文字对比度由阅读配色自身保证; 图片阅读背景时沉浸式为 false, ReadMenuOverlay 走
    // AppTheme 默认色 (与原版非沉浸式行为一致)。fallbackBgColor 统一传主题底栏背景色
    // (T6: 原传 0 的历史差异已对齐, 见 ReadMenuThemeHelper.createReadMenuColors KDoc;
    // 经 DesktopThemeStoreProvider 读持久层, 与 AppTheme.colors.bottomBackground 同源,
    // 无需 @Composable 上下文)。
    private val menuTheme: ReadMenuColors
        get() = createReadMenuColors(
            ReadBookConfigProviders.get().config,
            DesktopThemeStoreProvider().bottomBackground.toArgb(),
        )
    override val immersive: Boolean get() = menuTheme.immersive
    override val bgColor: Int get() = menuTheme.bgColor
    override val textColor: Int get() = menuTheme.textColor

    // 窗口背景图时顶栏透明让背景图透出; 与 LegadoApp 壁纸层同一数据源
    // (判定收敛 shared hasBgImageByPath, 对照 Android upColorConfig)
    override val hasBgImage: Boolean
        get() = hasBgImageByPath(DesktopThemeStoreProvider().bgImagePath)

    // 顶栏 (快照状态, 由 refresh()/reset() 更新: 普通 getter 读 StateFlow.value 在组合期
    // 不追踪, 切章后书名/章节名会冻结; 对照原版 upBookView/upMenuView 显式刷新)
    override var title: String? by mutableStateOf(null)
        private set
    override var chapterName: String? by mutableStateOf(null)
        private set
    override var chapterUrl: String? by mutableStateOf(null)
        private set
    override var chapterNameVisible by mutableStateOf(false)
        private set
    override var chapterUrlVisible by mutableStateOf(false)
        private set
    override var sourceActionText by mutableStateOf("")
        private set
    override var sourceActionVisible by mutableStateOf(false)
        private set

    // 顶栏下方的章节名/章节链接行 (对照 app 端 AndroidReaderMenuState:
    // titleBarAdditionVisible = AppConfig.showReadTitleBarAddition, 默认 true;
    // 桌面端无对应设置页入口, 读同一 pref, 缺失回落 true 恢复显示)
    override var titleBarAdditionVisible by mutableStateOf(
        runCatching {
            PreferenceProviders.get().getBoolean(PreferKey.showReadTitleAddition, true)
        }.getOrDefault(true)
    )
        private set
    override val topMenu = TopMenuState()

    // 底栏进度条 (快照状态, 由 upSeekBar()/refresh() 更新: 普通 getter 读 StateFlow.value
    // 在组合期不追踪, 翻页/切章后进度条与上下章可用状态会冻结;
    // 对照原版 ReadMenu.upSeekBar/upMenuView 实时刷新)
    override var seekMax: Int by mutableStateOf(0)
        private set
    override var seekValue: Int by mutableStateOf(0)
        private set
    override var prevEnabled by mutableStateOf(false)
        private set
    override var nextEnabled by mutableStateOf(false)
        private set
    override var autoPage by mutableStateOf(false)
    override var isNightTheme by mutableStateOf(AppConfigProviders.get().isNightTheme)
        private set

    // 滚动模式朗读重定位: 暂停期间页面是否变化 (对照 app 端 AndroidReaderMenuState.aloudPageChanged)
    private var aloudPageChanged = false

    init {
        // 页面变化 → aloudPageChanged (对照 app 端 AndroidReaderMenuState.init:
        // 经 ReadBookEvents.seekBarChange 桥接: onPageChanged/onChapterChanged 均触发)
        autoPageScope.launch {
            ReadBookEvents.seekBarChange.collect { aloudPageChanged = true }
        }
    }

    fun show() {
        animate = !AppConfigProviders.get().isEInkMode
        refresh()
        isNightTheme = AppConfigProviders.get().isNightTheme
        visibleState.targetState = true
    }

    fun hide() {
        visibleState.targetState = false
    }

    // 书源操作按钮 (对照 app 端 AndroidReaderMenuState.upSourceAction)
    private fun upSourceAction() {
        val book = screenModel.viewModel.book.value
        val source = screenModel.viewModel.bookSource.value
        sourceActionText = source?.bookSourceName ?: "书源"
        sourceActionVisible = book?.let { !it.isLocal } ?: false
    }

    // 顶栏菜单可见/勾选状态 (对照 app 端 AndroidReaderMenuState.upTopMenu)
    private fun upTopMenu() {
        val book = screenModel.viewModel.book.value ?: return
        topMenu.onLine = !book.isLocal
        topMenu.isLocalTxt = book.isLocalTxt
        topMenu.isEpub = book.isEpub
        topMenu.enableReplaceChecked = book.getUseReplaceRule()
        topMenu.reSegmentChecked = book.config.reSegment
        topMenu.delRubyChecked = book.config.delTag and Book.rubyTag == Book.rubyTag
        topMenu.delHChecked = book.config.delTag and Book.hTag == Book.hTag
        // 去重勾选态 (T3, 对照 app 端 onMenuOpened → menu_same_title_removed.isChecked)
        topMenu.sameTitleRemovedChecked =
            screenModel.viewModel.curTextChapter.value?.sameTitleRemoved == true
        // 云进度同步可见性 (T3, 对照 app 端 onMenuOpened: !book.isNotShelf && AppWebDav.isOk;
        // 桌面经 shared AppWebDavShared 同一推导路径)
        topMenu.syncProgressVisible = !book.isNotShelf && AppWebDavShared.isOk
    }

    override fun onTransitionIdle(shown: Boolean) = Unit
    override fun onBgClick() = hide()

    override fun onChapterViewClick() {
        val book = screenModel.viewModel.book.value ?: return
        if (book.isLocal) return
        val url = chapterUrl.orEmpty()
        // 长按可切换浏览器/应用内打开方式 (对照 app 端 ReadMenu.onChapterViewClick)
        if (PreferenceProviders.get().getBoolean(PreferKey.readUrlOpenInBrowser, false)) {
            DesktopPlatformCapabilities.openExternalUrl(url.substringBefore(",{"))
            return
        }
        // 2026-08-06 用户拍板: 不再推 WebViewRoute 中转界面, 直接开内置浏览器窗口
        // (java.startBrowser 同链路: cookie 经书源 key 回写, 登录态可复用;
        //  传原始 chapterUrl 可能含 `,{...}` 请求头, 由 AnalyzeUrl 解析)
        chapterLinkScope.launch {
            val source = AppDbProviders.get().bookSourceDao.getBookSource(book.origin)
            if (source != null) {
                SourceVerificationHelpShared.startBrowser(
                    source, url, book.originName, saveResult = false, refetchAfterSuccess = false
                )
            } else {
                DesktopPlatformCapabilities.openExternalUrl(url.substringBefore(",{"))
            }
        }
    }

    // 章节链接长按: 弹选择框切换浏览器/应用内打开 (对照 app 端 ReadMenu.onChapterViewLongClick
    // 的 activity.alert, 写同一 PreferKey.readUrlOpenInBrowser)
    override fun onChapterViewLongClick() {
        val book = screenModel.viewModel.book.value ?: return
        if (book.isLocal) return
        DesktopDialogs.show(
            DesktopDialogRequest.Confirm(
                title = "打开方式",
                message = "是否使用外部浏览器打开？",
                okText = "是",
                noText = "否",
                onOk = {
                    PreferenceProviders.get().putBoolean(PreferKey.readUrlOpenInBrowser, true)
                },
                onNo = {
                    PreferenceProviders.get().putBoolean(PreferKey.readUrlOpenInBrowser, false)
                },
            )
        )
    }

    override fun onOverflowOpened() {
        screenModel.updateSourceMenu()
    }

    override fun sourceLoginVisible(): Boolean = screenModel.sourceLoginVisible()

    // 购买按钮显示条件已下沉 ReaderScreenModel.sourcePayVisible (对照原版 ReadMenu)
    override fun sourcePayVisible(): Boolean = screenModel.sourcePayVisible()

    override fun onSourceAction(action: SourceAction) {
        when (action) {
            SourceAction.LOGIN -> {
                val source = screenModel.viewModel.bookSource.value ?: return
                // 统一登录入口: URL 登录桌面端直开登录窗口 (2026-08-07); 表单登录弹 Overlay,
                // 带上当前书与当前章 (对照原版 showLogin 预置 IntentData)
                showSourceLogin(
                    source.getKey(),
                    source,
                    screenModel.currentBook,
                    screenModel.currentChapter,
                )
            }

            SourceAction.EDIT_SOURCE -> {
                val origin = screenModel.viewModel.book.value?.origin ?: return
                navigator.push(AppRoute.BookSourceEdit(origin), RouteResults.BOOK_SOURCE_EDIT)
            }

            SourceAction.DISABLE_SOURCE -> screenModel.viewModel.disableSource()

            // 购买当前章: 确认弹窗由 ReaderRoute ChapterPay 渲染, 确认后执行书源 payAction JS
            // (对照原版 ReadMenu menu_chapter_pay -> payAction)
            SourceAction.CHAPTER_PAY ->
                screenModel.postDialogEvent(ReaderDialogEvent.ChapterPay)

            // 源/书变量编辑 (对照原版 ReadMenu showSourceVariableDialog/showBookVariableDialog)
            SourceAction.SET_SOURCE_VARIABLE -> screenModel.showSourceVariableDialog()
            SourceAction.SET_BOOK_VARIABLE -> screenModel.showBookVariableDialog()
        }
    }

    override fun openBookInfoActivity() {
        screenModel.currentBook?.let {
            navigator.push(AppRoute.BookInfo(it.toRouteRef()), RouteResults.BOOK_INFO)
        }
    }

    override fun supportFinishAfterTransition() {
        navigator.pop()
    }

    override fun onTopMenuAction(action: ReadMenuAction) {
        when (action) {
            ReadMenuAction.CHANGE_SOURCE,
            ReadMenuAction.BOOK_CHANGE_SOURCE -> {
                // 对照原版 换源 → ChangeBookSourceDialog 底部弹窗
                screenModel.postDialogEvent(ReaderDialogEvent.ChangeSource)
            }

            ReadMenuAction.CHAPTER_CHANGE_SOURCE -> {
                // 对照原版 章节换源 → ChangeChapterSourceDialog 底部弹窗
                screenModel.postDialogEvent(ReaderDialogEvent.ChangeChapterSource)
            }

            ReadMenuAction.REFRESH_DUR -> screenModel.viewModel.refreshCurrentChapter()

            ReadMenuAction.ADD_BOOKMARK -> {
                val book = screenModel.viewModel.book.value ?: return
                val page = screenModel.viewModel.curTextPage.value
                val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author).apply {
                    chapterIndex = screenModel.viewModel.durChapterIndex.value
                    chapterPos = screenModel.viewModel.durChapterPos.value
                    chapterName = page?.title ?: screenModel.currentChapter?.title ?: ""
                    bookText = page?.text?.trim() ?: ""
                }
                screenModel.postDialogEvent(ReaderDialogEvent.AddBookmark(bookmark))
            }

            ReadMenuAction.EDIT_CONTENT -> screenModel.postDialogEvent(ReaderDialogEvent.EditContent)
            ReadMenuAction.REVIEW -> screenModel.currentBook?.let { book ->
                val chapter = screenModel.currentChapter
                if (!PlatformCapabilityProviders.get().showReviewListDialog(book, chapter, 0)) {
                    navigator.push(AppRoute.ReviewList(book.toRouteRef()))
                }
            }

            ReadMenuAction.LOG -> screenModel.postDialogEvent(ReaderDialogEvent.Log)

            // ===== 溢出菜单动作 (T2: 补齐各分支, 全部接到 shared 能力: viewModel / 事件广播 /
            // ===== 导航 / 弹窗事件, 对齐 iOS/OHOS/app 语义) =====

            // 刷新后续章节 (对照原版 menu_refresh_after → viewModel.refreshContentAfter)
            ReadMenuAction.REFRESH_AFTER -> {
                val book = screenModel.viewModel.book.value ?: return
                screenModel.viewModel.refreshContentAfter(book)
            }

            // 刷新全部 (对照原版 menu_refresh_all → viewModel.refreshContentAll)
            ReadMenuAction.REFRESH_ALL -> screenModel.viewModel.refreshContentAll()

            // 离线缓存 (对照原版 menu_download → DownloadDialog 弹窗 → CacheBookShared.start)
            ReadMenuAction.DOWNLOAD ->
                screenModel.postDialogEvent(ReaderDialogEvent.Download)

            // 本地 TXT 目录正则 (对照原版 menu_toc_regex → TxtTocRule 路由)
            ReadMenuAction.TOC_REGEX -> navigator.push(AppRoute.TxtTocRule)

            // 设置编码 (对照原版 menu_set_charset → CharsetDialog 弹窗 → viewModel.setCharset)
            ReadMenuAction.SET_CHARSET ->
                screenModel.postDialogEvent(ReaderDialogEvent.SetCharset)

            // 翻页动画: 选择器回调忽略索引, 实际动画值在界面设置弹窗配置,
            // 此处直接发配置事件触发重载 (与 app 端 showPageAnimConfigSelector 等价)
            ReadMenuAction.PAGE_ANIM ->
                ReadBookEvents.postConfig(ReadConfigChange.PAGE_ANIM, ReadConfigChange.LOAD_CONTENT)

            // 模拟追读 (对照原版 menu_simulated_reading → SimulatedReadingDialog)
            ReadMenuAction.SIMULATED_READING ->
                screenModel.postDialogEvent(ReaderDialogEvent.SimulatedReading)

            // 启用替换: 翻转 useReplaceRule + 刷新替换规则缓存 + 同章重载 (shared viewModel)
            ReadMenuAction.ENABLE_REPLACE -> screenModel.viewModel.toggleUseReplaceRule()

            // 去重: 翻转当前章去重标记并重载 (shared viewModel 已含未找到重复标题提示语义)
            ReadMenuAction.SAME_TITLE_REMOVED -> screenModel.viewModel.reverseRemoveSameTitle()

            // 重新分段: 翻转 reSegment + 落库 + 同章重载
            ReadMenuAction.RE_SEGMENT -> screenModel.viewModel.toggleReSegment()

            // 图片样式 (对照原版 menu_image_style → ImageStyleDialog)
            ReadMenuAction.IMAGE_STYLE ->
                screenModel.postDialogEvent(ReaderDialogEvent.ImageStyle)

            // 更新目录: 清解析缓存后回源重拉目录
            ReadMenuAction.UPDATE_TOC -> screenModel.viewModel.updateToc()

            // 云进度手动同步 (上传/同步成功 toast; 项仅在 syncProgressVisible 时渲染)
            ReadMenuAction.SYNC_PROGRESS -> screenModel.viewModel.syncProgressManual(
                uploadSuccessAction = { Toasters.get().toast("上传成功") },
                syncSuccessAction = { Toasters.get().toast("同步成功") },
            )

            // epub 去除 ruby/h 标签: 翻转 delTag + 落库 + 全章清缓存重载
            ReadMenuAction.DEL_RUBY_TAG -> screenModel.viewModel.toggleDelTag(Book.rubyTag)
            ReadMenuAction.DEL_H_TAG -> screenModel.viewModel.toggleDelTag(Book.hTag)

            // 帮助: 原版 showHelp 打开本地 web 帮助页 (依赖 Android 资源), 桌面未移植, no-op
            ReadMenuAction.HELP -> Unit

            else -> Unit
        }
    }

    override fun onSeekDragStart() = Unit
    override fun onSeekStop(progress: Int) {
        // 推导逻辑下沉 shared: page 模式按页跳转, chapter 模式首次弹确认后跳章 (T5)。
        // 平台槽只负责"确认对话框是否由平台弹"——桌面经 DesktopDialogs 弹确认。
        screenModel.onSeekStop(progress) { onConfirm ->
            DesktopDialogs.show(
                DesktopDialogRequest.Confirm(
                    title = "章节跳转确认",
                    message = "确定要跳转章节吗？",
                    okText = "确定",
                    noText = "取消",
                    onOk = onConfirm,
                    onNo = {},
                )
            )
        }
    }

    override fun clickSearch() {
        // 缓存的结果只有 query 与当前一致才回填 (对照 ReadBookActivity.openSearchActivity)
        val initialResults = screenModel.searchResultList
            ?.takeIf { results -> results.firstOrNull()?.query == screenModel.searchContentQuery }
        navigator.push(
            AppRoute.SearchContent(
                index = screenModel.searchResultIndex,
                word = screenModel.searchContentQuery.takeIf { it.isNotEmpty() },
                initialResults = initialResults,
                book = screenModel.viewModel.book.value?.toRouteRef(),
            ),
            resultKey = RouteResults.SEARCH_CONTENT,
        )
    }

    // 自动翻页: 切换状态 + 启停控制器 (对照 app 端 AndroidReaderMenuState.clickAutoPage;
    // 桌面端无 ReadAloud 单例, 朗读与自动翻页共用翻页动作, 无需先停朗读)
    override fun clickAutoPage() {
        if (autoPage) {
            stopAutoPage()
        } else {
            startAutoPage()
        }
    }

    /** 自动翻页控制器 (对照 app 端 AndroidReaderMenuState.autoPager, shared AutoPagerCompose 承载)。 */
    private var autoPager: AutoPagerCompose? = null

    /**
     * 启动自动翻页: 三模式语义与 app 端一致 (E-Ink 定时整页翻 / 非 E-Ink 揭示动画覆盖层 /
     * 滚动模式连续滚动), 由 shared [AutoPagerCompose] 驱动 [ReadBookViewModelShared].
     * 翻到全书末尾自动停 (pager.onEnd)。
     */
    private fun startAutoPage() {
        stopAutoPage()
        autoPage = true
        autoPager = AutoPagerCompose(
            viewModel = screenModel.viewModel,
            scope = autoPageScope,
            // 每拍现读速度配置 (对照原版每次 postDelayed 现取 ReadBookConfig.autoReadSpeed)
            autoReadSpeed = {
                ReadBookConfigProviders.get().autoReadSpeed.coerceAtLeast(1)
            },
        ).also { pager ->
            pager.onEnd = { stopAutoPage() }
            pager.start()
        }
    }

    /** 停止自动翻页: 复位控制器 + 复位开关 (对照 app 端 stopAutoPage)。 */
    fun stopAutoPage() {
        autoPager?.stop()
        autoPager = null
        autoPage = false
    }

    override fun clickReplaceRule() {
        // 对照原版 openReplaceRule → EffectiveReplacesDialog (runMenuOut 先收菜单)
        hide()
        screenModel.postDialogEvent(ReaderDialogEvent.EffectiveReplaces)
    }

    // 夜间主题切换 (对照 app 端 clickNightTheme, 经 ThemeConfigProviders 应用主题色 + 触发重组)
    override fun clickNightTheme() {
        val newNight = !isNightTheme
        ThemeConfigProviders.get().applyDayNight(newNight)
        isNightTheme = newNight
    }

    override fun clickPre() {
        // 手动切章时停自动翻页 (T4, 对齐 app 端 clickPre)
        stopAutoPage()
        screenModel.viewModel.moveToPrevChapter()
    }

    override fun clickNext() {
        // 手动切章时停自动翻页 (T4, 对齐 app 端 clickNext)
        stopAutoPage()
        screenModel.viewModel.moveToNextChapter()
    }

    override fun clickCatalog() {
        // 对照原版 目录按钮 → TocDialog 底部弹窗 (runMenuOut 先收菜单)
        hide()
        screenModel.postDialogEvent(ReaderDialogEvent.Toc)
    }

    // 朗读按钮短按 (T7: 补齐 app 端 AndroidReaderPlatformProvider.clickReadAloud 状态机:
    // stopAutoPage → 滚动模式从可视区起点朗读 / 非滚动 play; 暂停时滚动模式且 aloudPageChanged
    // 重定位可视区起点再 resume, 否则 resume; 运行中 → pause)。桌面保留短按同步弹朗读面板。
    override fun clickReadAloud() {
        // 对照原版 onClickReadAloud 第一行 autoPageStop
        stopAutoPage()
        when {
            !DesktopReadAloudHost.isRun -> {
                // 桌面无 upReadAloudClass 概念 (无朗读分类), 直接 play
                if (screenModel.viewModel.isScrollPageAnim) {
                    screenModel.viewModel.readAloudFromVisibleStart()
                } else {
                    DesktopReadAloudHost.play()
                }
            }

            DesktopReadAloudHost.isPause -> {
                // 滚动模式且暂停期间翻过页: 重定位到新可视段起点 (对照原版 pageChanged 分支)
                if (screenModel.viewModel.isScrollPageAnim && aloudPageChanged) {
                    aloudPageChanged = false
                    screenModel.viewModel.readAloudFromVisibleStart()
                } else {
                    DesktopReadAloudHost.resume()
                }
            }

            else -> DesktopReadAloudHost.pause()
        }
        screenModel.postDialogEvent(ReaderDialogEvent.ReadAloud)
    }

    // 长按朗读: 弹共享朗读控制面板 (对照原版 ReadMenu 长按 → showReadAloudDialog)
    override fun longClickReadAloud() {
        screenModel.postDialogEvent(ReaderDialogEvent.ReadAloud)
    }

    override fun clickFont() {
        // 对照原版 showReadStyle → ReadStyleDialog (底部弹窗, runMenuOut 先收菜单)
        hide()
        screenModel.postDialogEvent(ReaderDialogEvent.ReadStyle)
    }

    override fun clickSetting() {
        // 对照原版 showMoreSetting → MoreConfigDialog (底部弹窗, runMenuOut 先收菜单)
        hide()
        screenModel.postDialogEvent(ReaderDialogEvent.MoreConfig)
    }

    // 刷新当前章节 (顶栏刷新图标短按)
    override fun onRefresh() {
        screenModel.viewModel.refreshCurrentChapter()
    }

    /** 顶栏/底栏展示数据 (对照原版 upBookView: 书名/章节名/章节链接/上下章可用性) */
    private fun upMenuView() {
        val book = screenModel.viewModel.book.value
        title = book?.name
        val curChapter = screenModel.currentChapter
        chapterName = curChapter?.title
        chapterUrl = curChapter?.url
        chapterNameVisible = !chapterName.isNullOrEmpty()
        chapterUrlVisible = !chapterUrl.isNullOrEmpty() && book?.isLocal == false
        titleBarAdditionVisible = runCatching {
            PreferenceProviders.get().getBoolean(PreferKey.showReadTitleAddition, true)
        }.getOrDefault(true)
        prevEnabled = screenModel.viewModel.canMoveToPrevChapter()
        nextEnabled = screenModel.viewModel.canMoveToNextChapter()
    }

    // 菜单数据刷新事件 → 重算顶栏/底栏展示状态 (对照原版 menuRefresh → upMenuView())
    override fun refresh() {
        upTopMenu()
        upMenuView()
        // 源名/可见性也要跟着刷: 书源编辑保存后 menuState.refresh() 是唯一刷新入口,
        // 不在此重算则菜单仍展开时顶部源按钮停留在旧源名
        upSourceAction()
    }

    // 进度条刷新 (对照原版 seekBarChange → readMenu.upSeekBar; page 模式按页跳转/页码,
    // 与 app 端 onSeekStop "page" 分支同源, T5)
    override fun upSeekBar() {
        val pageBehavior = PreferenceProviders.get()
            .getString(PreferKey.progressBarBehavior, "page") == "page"
        seekMax = if (pageBehavior) {
            (screenModel.viewModel.curTextChapter.value?.pageSize?.minus(1) ?: -1)
                .coerceAtLeast(0)
        } else {
            (screenModel.viewModel.simulatedChapterSize - 1).coerceAtLeast(0)
        }
        seekValue = if (pageBehavior) {
            screenModel.viewModel.durPageIndex.value
        } else {
            screenModel.viewModel.durChapterIndex.value
        }
    }

    // 菜单/顶栏重建 (对照原版 actionBarChange → readMenu.reset)
    override fun reset() {
        upTopMenu()
        upMenuView()
    }
}
