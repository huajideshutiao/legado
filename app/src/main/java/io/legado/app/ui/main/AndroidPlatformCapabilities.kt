package io.legado.app.ui.main

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.view.ViewConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.constant.BottomNavTag
import io.legado.app.constant.EventBus
import io.legado.app.constant.appInfo
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.Review
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.CrashHandler
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.IntentData
import io.legado.app.help.IntentHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.removeType
import io.legado.app.help.book.save
import io.legado.app.help.book.toShelfJsonMap
import io.legado.app.help.book.toggleBookshelfCore
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppConfigConstants
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.update.AppUpdate
import io.legado.app.model.BookCover
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.importFromArchive
import io.legado.app.model.fileBook.importLocalFile
import io.legado.app.model.fileBook.saveBookFile
import io.legado.app.service.WebService
import io.legado.app.ui.association.AddToBookshelfHelper
import io.legado.app.ui.association.DeepLinkImportType
import io.legado.app.ui.association.FileAssociationViewModel
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.book.import.local.ImportBookViewModel
import io.legado.app.ui.book.read.config.FontItem
import io.legado.app.ui.book.read.config.HttpTtsEditDialog
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.book.source.manage.BookSourceViewModel
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.component.AppSwitch
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.config.DefaultCoverGalleryDialog
import io.legado.app.ui.config.ThemeCustomizeDialog
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.DialogTransitionSpec
import io.legado.app.ui.root.PlatformCapabilities
import io.legado.app.ui.root.RouteTransitionSampler
import io.legado.app.ui.root.RouteTransitionSpec
import io.legado.app.ui.root.TransitionEasing
import io.legado.app.ui.root.encodeBookVariableOverlayPayload
import io.legado.app.ui.root.encodeSourceVariableOverlayPayload
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.ui.route.encodeReviewListDialogPayload
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.FlowBus
import io.legado.app.utils.GSON
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.RemoteAssetsUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getClipText
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.list
import io.legado.app.utils.openFileUri
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.restart
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.verificationField
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

class AndroidPlatformCapabilities(
    private val activity: MainActivity,
) : PlatformCapabilities {

    // 导入书籍: 复用 ImportBookViewModel 维护 rootDoc/subDocs 状态 (对照 ImportBookActivity)
    // 经 ViewModelProvider 绑定 activity ViewModelStore, lifecycle 由 activity 管理
    private val importViewModel by lazy {
        ViewModelProvider(activity).get(ImportBookViewModel::class.java)
    }
    private val associationViewModel by lazy {
        ViewModelProvider(activity).get(FileAssociationViewModel::class.java)
    }
    private var scanDocJob: Job? = null

    // ===== 导入书籍状态流 (对照 ImportBookActivity.initData/upDocs/scanFolder) =====

    // 文件列表: 订阅 viewModel.dataFlow, 子目录时前置"返回上级"项 (对照 initData 的 collect)
    private val importItemsState by lazy {
        MutableStateFlow<List<ImportFileItem>>(emptyList()).also { state ->
            importViewModel.dataFlowStart = { ensureRootDoc() }
            activity.lifecycleScope.launch(IO) {
                importViewModel.dataFlow.conflate().collect { docs ->
                    val items = if (importViewModel.subDocs.isNotEmpty()) {
                        listOf(ImportBook(importViewModel.subDocs.last(), isUpDir = true)) + docs
                    } else {
                        docs
                    }
                    state.value = items
                }
            }
        }
    }

    // 面包屑路径: rootDoc.name/.../当前目录/ (对照 upDocs 内 showBreadcrumb)
    private val importPathState = MutableStateFlow<String?>(null)

    // 扫描加载中 (对照 scanFolder 的 refreshProgressBar.isAutoLoading)
    private val importLoadingState = MutableStateFlow(false)

    // 空态文案可见 (对照 initRootDoc/initRootPath 的 tvEmptyMsg)
    private val importEmptyMsgState = MutableStateFlow(false)

    // ===== 全局转场动画平台 spec (方案 A: 动画单一注入点参数化) =====
    // Android: 系统 Activity 转场语义 (API 28+ 默认转场: 新页 slide_in_right 全宽滑入 +
    // fade_in, 旧页 fade_out 不位移, 300ms, @android:interpolator/fast_out_slow_in);
    // 返回: 出栈页 slide_out_right 全宽滑出+淡出, 目标页按系统 fade 转场语义淡入不位移。
    // 时长运行时动态读系统动画时长缩放 (Settings.Global ANIMATOR_DURATION_SCALE ×
    // TRANSITION_ANIMATION_SCALE 取小值: 任一关闭即关闭转场动画, 尊重用户"动画时长缩放"
    // 设置, >1 时与系统一致放慢), 读取失败回退系统规范值 (300ms/200ms/150ms)。

    /** 系统动画时长缩放 (ANIMATOR_DURATION_SCALE × TRANSITION_ANIMATION_SCALE 取小值, 读取失败回退 1f) */
    private fun systemAnimationScale(): Float {
        return runCatching {
            val resolver = activity.contentResolver
            val animatorScale = Settings.Global.getFloat(
                resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            )
            val transitionScale = Settings.Global.getFloat(
                resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f
            )
            minOf(animatorScale, transitionScale)
        }.getOrDefault(1f)
    }

    /**
     * 复用系统窗口转场动画的采样器 (读运行设备主题, ROM 定制自动生效); lazy 缓存动画实例,
     * 时长缩放每次动态读; 系统资源缺失时返回 null 回退参数化 spec。
     */
    private val systemRouteTransitionSampler: RouteTransitionSampler? by lazy {
        SystemRouteTransitionSampler.create(activity) { systemAnimationScale() }
    }

    override val routeTransitionSampler: RouteTransitionSampler?
        get() = systemRouteTransitionSampler

    /**
     * 参数化 spec: 仅在系统转场动画资源缺失 (routeTransitionSampler 为 null) 时作回退;
     * 正常路径由 [systemRouteTransitionSampler] 复用运行设备的系统动画 (含 ROM 定制)。
     */
    override val routeTransitionSpec: RouteTransitionSpec
        get() {
            val scale = systemAnimationScale()
            return RouteTransitionSpec(
                // 系统转场 300ms × 动画时长缩放 (关闭动画时 scale=0 → 0ms 瞬切, 对齐系统行为)
                pushDurationMillis = (300 * scale).toInt(),
                pushEasing = TransitionEasing.FastOutSlowIn,
                newPageSlideFraction = 1f, // 系统 slide_in_right 全宽
                oldPageShiftFraction = 0f, // 系统 fade_out 旧页不位移
                newPageFadeIn = true, // 系统 fade_in
                oldPageFadeOut = true, // 系统 fade_out
                newPageScaleFrom = 1f,
                popDurationMillis = (300 * scale).toInt(),
                popEasing = TransitionEasing.FastOutSlowIn,
                targetPageSlideFraction = 0f, // 系统返回转场 fade 语义, 目标页不位移
                outgoingSlideFraction = 1f, // 系统 slide_out_right 全宽
                targetPageFadeIn = true,
                outgoingFadeOut = true,
                targetPageScaleFrom = 1f,
            )
        }

    override val dialogTransitionSpec: DialogTransitionSpec
        get() {
            val scale = systemAnimationScale()
            return DialogTransitionSpec(
                // 系统 dialog_enter.xml 200ms decelerate_quad 缩放 0.96→1+淡入 /
                // dialog_exit.xml 150ms accelerate_quad 淡出, 时长 × 动画时长缩放
                enterDurationMillis = (200 * scale).toInt(),
                enterEasing = TransitionEasing.DecelerateQuad,
                enterScaleFrom = 0.96f,
                enterFadeIn = true,
                exitDurationMillis = (150 * scale).toInt(),
                exitEasing = TransitionEasing.AccelerateQuad,
                exitFadeOut = true,
            )
        }

    override fun exitApplication() {
        activity.finish()
    }

    override fun openExternalUrl(url: String) {
        activity.openUrl(url)
    }

    // 对照原版 OpenUrlConfirmDialog.openUrl: mimeType 非空时 setDataAndType 显式指定打开类型
    override fun openExternalUrl(url: String, mimeType: String?) {
        if (mimeType.isNullOrBlank()) {
            activity.openUrl(url)
            return
        }
        try {
            val uri = url.toUri()
            val targetIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (targetIntent.resolveActivity(appCtx.packageManager) != null) {
                appCtx.startActivity(targetIntent)
            } else {
                appCtx.toastOnUi(androidAppString("can_not_open"))
            }
        } catch (e: Exception) {
            AppLog.put("打开链接失败", e, true)
        }
    }

    override fun openWebView(url: String, sourceKey: String, sourceName: String) {
        // 移动端保留内嵌 WebViewRoute 路由语义 (对话框内嵌)
        AppNavigatorProviders.getOrNull()?.push(
            io.legado.app.ui.root.AppRoute.WebView(url, sourceKey, sourceName)
        )
    }

    override fun shareText(text: String) {
        activity.share(text)
    }

    // 按 bookUrl 查 DB 解析为 BookRef, 供 LaunchRequest.OpenBook/OpenBookInfo/OpenReader 路由导航
    override suspend fun resolveBookRef(bookUrl: String): BookRef? =
        appDb.bookDao.getBook(bookUrl)?.toRouteRef()

    // 对照 ThemeConfig.applyDayNight
    override fun applyDayNight() {
        ThemeConfig.applyDayNight(activity)
    }

    // 对照 Context.sendToClip
    override fun copyToClipboard(text: String) {
        activity.sendToClip(text)
    }

    override fun getClipboardText(): String? = getClipText()

    override fun readerBackgroundImageNames(): List<String> = RemoteAssetsUtils.getBgList()

    override fun testDirectLinkUpload(
        rule: io.legado.app.help.DirectLinkUploadRule,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        activity.lifecycleScope.launch(IO) {
            runCatching {
                DirectLinkUpload.upLoad("test.json", "{}", "application/json", rule)
            }.onSuccess { result ->
                withContext(kotlinx.coroutines.Dispatchers.Main) { onSuccess(result) }
            }.onFailure { error ->
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(error.localizedMessage ?: error.toString())
                }
            }
        }
    }

    override fun handleDeepLinkImport(typeName: String, src: String): Boolean {
        val type = runCatching { DeepLinkImportType.valueOf(typeName) }.getOrNull() ?: return false
        val navigator = AppNavigatorProviders.getOrNull() ?: return false
        return when (type) {
            DeepLinkImportType.ADD_TO_BOOKSHELF -> {
                AddToBookshelfHelper.add(navigator, activity, src)
                true
            }

            DeepLinkImportType.READ_CONFIG -> {
                associationViewModel.getBytes(src) { bytes ->
                    associationViewModel.importReadConfig(bytes) { title, message ->
                        activity.alert {
                            setTitle(title)
                            setMessage(message)
                            okButton()
                        }
                    }
                }
                true
            }

            DeepLinkImportType.UNKNOWN -> {
                associationViewModel.determineType(src) { title, message ->
                    activity.alert {
                        setTitle(title)
                        setMessage(message)
                        okButton()
                    }
                }
                true
            }

            else -> false
        }
    }

    // 对照 WebService.hostAddress
    override fun getWebServiceUrl(): String? =
        WebService.hostAddress.takeIf { it.isNotEmpty() }

    // 对照 WebService.isRun
    override fun isWebServiceRunning(): Boolean = WebService.isRun

    // 对照 WebService.start / WebService.stop
    override fun setWebService(enabled: Boolean) {
        if (enabled) WebService.start(activity) else WebService.stop(activity)
    }

    // 对照 MyTab FlowBus.withSticky(EventBus.WEB_SERVICE).collect, 桥接到 StateFlow
    private val webServiceRunningState by lazy {
        MutableStateFlow(WebService.isRun).also { state ->
            activity.lifecycleScope.launch(IO) {
                FlowBus.withSticky(EventBus.WEB_SERVICE).collect { running ->
                    if (running is Boolean) state.value = running
                }
            }
        }
    }

    override val webServiceState: StateFlow<Boolean>? get() = webServiceRunningState

    // 对照 BookInfoEditActivity.onChangeCoverSource + coverChangeTo
    // 迁 Compose Overlay: 原 showDialogFragment(ChangeCoverDialog) 已由
    // shared OverlayContentHost 的 "change_cover" key 接管 (payload="name\nauthor")
    // 结果回调: overlayResults 返回 RouteResultPayload.ChangeCover(coverUrl)
    override fun showChangeCoverDialog(book: Book, onCoverSelected: (String) -> Unit) {
        val navigator = AppNavigatorProviders.getOrNull()
        if (navigator != null) {
            activity.lifecycleScope.launch(IO) {
                navigator.showOverlay(
                    AppOverlay.Dialog(
                        "change_cover",
                        payload = "${book.name}\n${book.author}"
                    )
                )
                // 等 Overlay 结果返回 (RouteResultPayload.ChangeCover)
                val result = navigator.overlayResults.first { it.key == "change_cover" }
                (result.payload as? io.legado.app.ui.root.RouteResultPayload)?.let { payload ->
                    if (payload is io.legado.app.ui.root.RouteResultPayload.ChangeCover) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onCoverSelected(payload.coverUrl)
                        }
                    }
                }
            }
        }
    }

    // 评论: 经 shared Overlay 弹段评/书评列表 (对照 app 端 ReviewListDialog BottomSheetDialogFragment;
    // shared ReviewListDialogHost 用 AppBottomSheetDialog 还原底部弹窗语义, payload 编码见 ReviewListDialogHost.kt)
    override fun showReviewListDialog(
        book: Book,
        chapter: BookChapter?,
        paragraphIndex: Int,
        parentReview: Review?,
    ): Boolean {
        AppNavigatorProviders.getOrNull()?.showOverlay(
            AppOverlay.Dialog(
                key = "review_list",
                payload = encodeReviewListDialogPayload(book, chapter, paragraphIndex, parentReview),
            )
        )
        return true
    }

    // 图片预览 (对照原版 ContentTextView.click 的 PhotoDialog 分支;
    // chapterIndex 不消费: app 端 PhotoDialog 已保留原版章节缓存优先链路)
    override fun showImagePreview(url: String, chapterIndex: Int) {
        activity.showDialogFragment(PhotoDialog(url))
    }

    // 对照 DefaultCoverGalleryDialog
    override fun showDefaultCoverGallery(isNight: Boolean) {
        activity.showDialogFragment(DefaultCoverGalleryDialog(isNight))
    }

    // 对照 BookCover.upDefaultCover
    override fun refreshDefaultCover() {
        BookCover.upDefaultCover()
    }

    // 对照 IntentHelp.openTTSSetting
    override fun openTtsSettings() {
        IntentHelp.openTTSSetting()
    }

    // 对照 ReadAloudConfigDialog 的"+"按钮/行编辑: showDialogFragment(HttpTtsEditDialog)
    override fun showHttpTtsEditDialog(engine: HttpTTS?) {
        if (engine == null) {
            activity.showDialogFragment<HttpTtsEditDialog>()
        } else {
            activity.showDialogFragment(HttpTtsEditDialog(engine.id))
        }
    }

    // 对照 app 端 FontSelectDialog.loadFontFiles: pref 字体目录 + externalFiles/font 合并去重排序
    override suspend fun scanFontItems(): List<FontItem> = withContext(IO) {
        val fontRegex = Regex("(?i).*\\.[ot]tf")
        val items = arrayListOf<FontItem>()
        val fontPath = AppConfig.fontFolder
        if (!fontPath.isNullOrBlank()) {
            runCatching {
                if (fontPath.isContentScheme()) {
                    // SAF 目录: 优先转真实路径 (对照原版 RealPathUtil 分支), 失败则扫 DocumentFile
                    val realPath = RealPathUtil.getPath(activity, fontPath.toUri())
                    if (realPath != null) {
                        scanFontDir(items, File(realPath), fontRegex)
                    } else {
                        DocumentFile.fromTreeUri(activity, fontPath.toUri())?.listFiles()?.forEach {
                            if (it.name?.matches(fontRegex) == true) {
                                items.add(FontItem(it.uri.toString(), it.name.orEmpty()))
                            }
                        }
                    }
                } else {
                    scanFontDir(items, File(fontPath), fontRegex)
                }
            }
        }
        // 对照 getLocalFonts: externalFiles/font
        scanFontDir(items, File(FileUtils.getPath(appCtx.externalFiles, "font")), fontRegex)
        // 对照 mergeFontItems: 同名去重 (先扫的 pref 目录优先) + 按名排序
        items.distinctBy { it.name }.sortedBy { it.name }
    }

    private fun scanFontDir(items: MutableList<FontItem>, dir: File, fontRegex: Regex) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach {
            if (it.isFile && it.name.matches(fontRegex)) {
                items.add(FontItem(it.absolutePath, it.name))
            }
        }
    }

    // 对照 AppContextWrapper.getFontScale, 返回 %.1f 字符串供 shared 端 %s 替换
    override fun getFontScale(): String? =
        String.format("%.1f", AppContextWrapper.getFontScale(activity))

    // 对照 ViewConfiguration.get(ctx).scaledTouchSlop
    override fun getScaledTouchSlop(): Int =
        ViewConfiguration.get(activity).scaledTouchSlop

    // 对照 ImportBookActivity.onPickFolder / selectFolder.launch: 选完目录重建 rootDoc
    override fun pickImportFolder() {
        activity.pendingImportFolderCallback = {
            importViewModel.subDocs.clear()
            importViewModel.rootDoc = null
            ensureRootDoc()
        }
        activity.launchImportFolderPicker()
    }

    override fun openImportFile(filePath: String) {
        val uri = Uri.parse(filePath)
        activity.supportFragmentManager.commit {
            add(
                io.legado.app.ui.association.FileAssociationFragment(uri),
                "FileAssociationFragment",
            )
        }
    }

    // 对照 ImportBookActivity.onScanFolder / scanFolder
    override fun scanImportFolder() {
        ensureRootDoc()
        importViewModel.rootDoc?.let { doc ->
            val lastDoc = importViewModel.subDocs.lastOrNull() ?: doc
            importLoadingState.value = true
            scanDocJob?.cancel()
            scanDocJob = activity.lifecycleScope.launch(IO) {
                try {
                    importViewModel.scanDoc(lastDoc)
                } finally {
                    // 仅当前 job 收尾时清 loading (被新扫描替换的旧 job 不动状态)
                    if (scanDocJob == kotlinx.coroutines.currentCoroutineContext()[Job]) {
                        importLoadingState.value = false
                    }
                }
            }
        }
    }

    // ===== 导入书籍状态流 override (对照 ImportBookActivity 同名字段) =====

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = importItemsState

    override fun importBookPath(): StateFlow<String?> = importPathState

    override fun importBookLoading(): StateFlow<Boolean> = importLoadingState

    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> = importEmptyMsgState

    // 对照 ImportBookActivity.onActivityCreated 内 initData: 设置 dataFlowStart + 启动收集。
    // 目录初始化由 dataFlow 收集启动时的 dataFlowStart (ensureRootDoc) 触发, 保证只走一次
    // (避免 initRootDoc 空路径分支重复弹选择器)
    override fun initImportBookData() {
        importItemsState
    }

    // 对照 ImportBookActivity.onAlertImportFileName / alertImportFileName
    override fun alertImportFileName() {
        activity.alert(androidAppString("import_file_name")) {
            setMessage("使用js处理文件名变量src，将书名作者分别赋值到变量name author")
            val getText = editTextView(hint = "js", text = AppConfig.bookImportFileName ?: "")
            okButton {
                AppConfig.bookImportFileName = getText()
            }
            cancelButton()
        }
    }

    // 对照 ImportBookActivity.onAddSelectionToBookshelf / addSelectionToBookshelf
    override fun addImportSelectionToBookshelf(
        items: List<ImportFileItem>,
        onComplete: () -> Unit
    ) {
        val books = items.mapNotNull { it as? ImportBook }.toHashSet()
        if (books.isEmpty()) {
            onComplete()
            return
        }
        importViewModel.addToBookshelf(books) {
            books.forEach { it.isOnBookShelf = true }
            onComplete()
        }
    }

    // 对照 ImportBookActivity.onSearchTextChange / viewModel.updateCallBackFlow
    override fun updateImportBookFilter(key: String) {
        importViewModel.updateCallBackFlow(key)
    }

    // 对照 ImportBookActivity.upSort: 更新 sort + 持久化 + 非扫描中重排当前列表
    override fun updateImportBookSort(sort: Int) {
        importViewModel.sort = sort
        AppConfig.localBookImportSort = sort
        if (scanDocJob?.isActive != true) {
            importViewModel.dataCallback?.upAdapter()
        }
    }

    // 对照 ImportBookActivity.onItemClick isDir 分支 / startRead
    override fun openImportedBookReader(item: ImportFileItem) {
        (item as? ImportBook)?.let { startRead(it.file) }
    }

    // 对照 ImportBookActivity.onItemClick isDir 分支 / nextDoc
    override fun navigateImportDir(item: ImportFileItem) {
        (item as? ImportBook)?.let { nextDoc(it.file) }
    }

    // 对照 ImportBookActivity.onItemClick isUpDir 分支 / goBackDir
    override fun goBackImportDir() {
        goBackDir()
    }

    // 对照 AboutActivity.onCheckUpdate / AppUpdate.check
    override val checkUpdateSupported: Boolean get() = true

    override fun checkUpdate() {
        AppUpdate.check(activity.lifecycleScope, activity)
    }

    // 对照 AboutActivity.onShowCrashLogs / showDialogFragment<CrashLogsDialog>
    // 迁 Compose Overlay: 原 showDialogFragment<CrashLogsDialog>() 已由
    // shared OverlayContentHost 的 "crash_logs" key 接管 (CrashLogsOverlayDialogContent
    // 通过 CrashLogProvider 提供数据/读文件/清空/分享)
    override fun showCrashLogs() {
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("crash_logs"))
    }

    // 对照 AboutActivity.onSaveLog / saveLog
    override fun saveLog() {
        saveLogInternal()
    }

    // 对照 AboutActivity.onCreateHeapDump / createHeapDump
    override fun createHeapDump() {
        createHeapDumpInternal()
    }

    // 对照 AboutActivity.onShowMdFile / showMdFile
    override fun showMdFile(title: String, fileName: String) {
        showMdFileInternal(title, fileName)
    }

    // ===== 书籍详情页平台能力: 对照 BookInfoActivity 同名方法 =====

    // 对照 BookInfoActivity.onClearCache / viewModel.clearCache (直接调 BookHelp)
    override fun clearBookCache(book: Book) {
        activity.lifecycleScope.launch(IO) {
            BookHelp.clearCache(book)
        }
    }

    // 对照 BookInfoActivity.onShelfClick / deleteBook: 上架/下架, webFile 走下载导入
    override fun toggleBookshelf(
        book: Book,
        inBookshelf: Boolean,
        onComplete: (Boolean?) -> Unit,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
    ) {
        if (inBookshelf) {
            deleteBook(book, onComplete)
        } else if (book.isWebFile) {
            // webFile: 弹下载导入选择框, 导入完成后标记已上架 (对照 onShelfClick isWebFile 分支)
            handleWebFileRead(book, onWaitDialog, onAction) { onComplete(true) }
        } else {
            // 上架走 shared 统一核心 toggleBookshelfCore (对照原 addToBookshelf → Book.save),
            // 与 desktop/iOS/ohos 一致, 避免各端维护多份拷贝
            activity.lifecycleScope.launch(IO) {
                runCatching { book.toggleBookshelfCore(false) }
                    .onSuccess { onComplete(it) }
                    .onFailure {
                        AppLog.put("书架操作失败\n${it.message}", it)
                        onComplete(false)
                    }
            }
        }
    }

    // 对照 BookInfoActivity.deleteBook: 删除确认弹窗 (本地书带"删除源文件"勾选)
    private fun deleteBook(book: Book, onComplete: (Boolean?) -> Unit) {
        if (!AppConfig.bookInfoDeleteAlert) {
            delBook(book, LocalConfig.deleteBookOriginal, onComplete)
            return
        }
        activity.alert(title = androidAppString("draw"), message = androidAppString("sure_del")) {
            val deleteFile = mutableStateOf(LocalConfig.deleteBookOriginal)
            if (book.isLocal) {
                customView {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = deleteFile.value,
                                role = Role.Checkbox,
                                onValueChange = { deleteFile.value = it },
                            )
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppCheckbox(checked = deleteFile.value, onCheckedChange = null)
                        Text(
                            rememberString("delete_book_file"),
                            color = AppTheme.colors.primaryText
                        )
                    }
                }
            }
            yesButton {
                if (book.isLocal) LocalConfig.deleteBookOriginal = deleteFile.value
                delBook(book, LocalConfig.deleteBookOriginal, onComplete)
            }
            noButton()
        }
    }

    // 对照 BookInfoViewModel.delBook (BaseReadViewModel): 删章节/DB + 清缓存 + 本地书删原文件
    private fun delBook(book: Book, deleteOriginal: Boolean, onComplete: (Boolean?) -> Unit) {
        activity.lifecycleScope.launch(IO) {
            runCatching {
                // DB 部分 (章节+书籍删除) 走 shared 统一核心 toggleBookshelfCore;
                // 平台专属仅剩缓存与本地文件清理
                book.toggleBookshelfCore(true)
                BookHelp.clearCache(book)
                if (book.isLocal) FileBook.deleteBook(book, deleteOriginal)
            }.onSuccess { activity.runOnUiThread { onComplete(null) } }
                .onFailure {
                    AppLog.put("删除书籍失败\n${it.message}", it)
                    activity.runOnUiThread { onComplete(false) }
                }
        }
    }

    // 对照 BookInfoActivity.onSetBookVariable / book.showBookVariableDialog (需查源);
    // VariableDialog 已下沉 shared: 经 bookVariable Overlay 弹出 (payload 编码见 VariableOverlayDialog.kt)
    override fun showBookVariableDialog(book: Book) {
        activity.lifecycleScope.launch(IO) {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            if (source != null) {
                activity.runOnUiThread {
                    AppNavigatorProviders.getOrNull()?.showOverlay(
                        AppOverlay.Dialog(
                            key = "bookVariable",
                            payload = encodeBookVariableOverlayPayload(book, source),
                        )
                    )
                }
            }
        }
    }

    // 对照 BookInfoActivity.onSetSourceVariable / source.showSourceVariableDialog (需查源);
    // 查不到源 toast error_no_source (与原版一致), 查到时经 sourceVariable Overlay 弹出
    override fun showSourceVariableDialog(book: Book) {
        activity.lifecycleScope.launch(IO) {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                activity.toastOnUi(androidAppString("error_no_source"))
                return@launch
            }
            activity.runOnUiThread {
                AppNavigatorProviders.getOrNull()?.showOverlay(
                    AppOverlay.Dialog(
                        key = "sourceVariable",
                        payload = encodeSourceVariableOverlayPayload(source),
                    )
                )
            }
        }
    }

    // 对照 BookInfoActivity.onDispatchIntroAction / source.evalJS (需查源)
    override fun evalIntroAction(book: Book, js: String) {
        val action = js.trim().ifEmpty { return }
        activity.lifecycleScope.launch(IO) {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
            if (source == null) {
                activity.toastOnUi(androidAppString("error_no_source"))
                return@launch
            }
            try {
                source.evalJS(action) {
                    this["book"] = book
                }
            } catch (e: Exception) {
                activity.toastOnUi(e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    // ===== webFile 下载导入链路 (对照 BookInfoViewModel + BookInfoActivity) =====

    // 对照 BookInfoViewModel.uploadBook
    override fun uploadBook(book: Book, success: (() -> Unit)?) {
        activity.lifecycleScope.launch(IO) {
            try {
                val bookWebDav = AppWebDav.defaultBookWebDav
                    ?: throw NoStackTraceException("未配置webDav")
                bookWebDav.upload(book)
                book.lastCheckTime = System.currentTimeMillis()
                book.save()
                activity.runOnUiThread {
                    activity.toastOnUi("上传成功")
                    success?.invoke()
                }
            } catch (e: Throwable) {
                activity.toastOnUi(e.localizedMessage)
            }
        }
    }

    // 对照 BookInfoViewModel.downloadToLocal
    override fun downloadBookToLocal(book: Book, success: (() -> Unit)?) {
        activity.lifecycleScope.launch(IO) {
            try {
                FileBook.downloadRemoteBook(book)
                activity.runOnUiThread {
                    activity.toastOnUi("下载成功")
                    success?.invoke()
                }
            } catch (e: Throwable) {
                AppLog.put("下载远程书籍<${book.name}>失败", e, true)
            }
        }
    }

    // 对照 BookInfoActivity.onReadClick isWebFile 分支 + showWebFileDownloadAlert
    override fun handleWebFileRead(
        book: Book,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        onSuccess: ((Book) -> Unit)?,
    ) {
        activity.lifecycleScope.launch(IO) {
            // 对照 BaseReadViewModel.loadWebFile: 从 downloadUrls + AnalyzeUrl 构建 webFile 列表
            val source = if (book.origin != BookType.localTag) {
                appDb.bookSourceDao.getBookSource(book.origin)
            } else null
            val webFiles = buildWebFiles(book, source)
            activity.runOnUiThread {
                if (webFiles.isEmpty()) {
                    activity.toastOnUi("Unexpected webFileData")
                    return@runOnUiThread
                }
                showWebFileDownloadAlert(book, webFiles, source, onWaitDialog, onAction, onSuccess)
            }
        }
    }

    // 对照 BaseReadViewModel.loadWebFile
    private suspend fun buildWebFiles(
        book: Book, source: BookSource?
    ): List<FileBook.WebFile> {
        val urls = book.downloadUrls ?: return emptyList()
        val fileNameNoExtension = if (book.author.isBlank()) book.name
        else "${book.name} 作者：${book.author}"
        return urls.map {
            val analyzeUrl =
                AnalyzeUrl(it, source = source, coroutineContext = currentCoroutineContext())
            val mFileName = UrlUtil.getFileName(analyzeUrl.url, analyzeUrl.headerMap)
                ?: "$fileNameNoExtension.${analyzeUrl.type}"
            FileBook.WebFile(it, mFileName)
        }
    }

    // 对照 BookInfoActivity.showWebFileDownloadAlert
    private fun showWebFileDownloadAlert(
        book: Book,
        webFiles: List<FileBook.WebFile>,
        source: BookSource?,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        onSuccess: ((Book) -> Unit)?,
    ) {
        activity.selector(androidAppString("download_and_import_file"), webFiles) { _, webFile, _ ->
            if (webFile.isSupported) {
                importWebFile(book, webFile, onWaitDialog, onAction) { onSuccess?.invoke(it) }
            } else if (webFile.isSupportDecompress) {
                downloadWebFile(book, webFile, onWaitDialog, onAction) { path ->
                    getArchiveFilesName(path) { fileNames ->
                        if (fileNames.size == 1) {
                            importBookFromArchive(
                                path,
                                fileNames[0],
                                book,
                                onWaitDialog
                            ) { onSuccess?.invoke(it) }
                        } else {
                            showDecompressFileImportAlert(
                                path,
                                fileNames,
                                book,
                                onWaitDialog,
                                onSuccess
                            )
                        }
                    }
                }
            } else {
                activity.alert(
                    title = androidAppString("draw"),
                    message = androidAppString("file_not_supported", webFile.name)
                ) {
                    neutralButton(androidAppString("open_fun")) {
                        downloadWebFile(book, webFile, onWaitDialog, onAction) { path ->
                            activity.openFileUri(path.toUri(), "*/*")
                        }
                    }
                    noButton()
                }
            }
        }
    }

    // 对照 BookInfoActivity.showDecompressFileImportAlert
    private fun showDecompressFileImportAlert(
        archiveFilePath: String,
        fileNames: List<String>,
        book: Book,
        onWaitDialog: (Boolean) -> Unit,
        onSuccess: ((Book) -> Unit)?,
    ) {
        if (fileNames.isEmpty()) {
            activity.toastOnUi(androidAppString("unsupport_archivefile_entry"))
            return
        }
        activity.selector(androidAppString("import_select_book"), fileNames) { _, name, _ ->
            importBookFromArchive(
                archiveFilePath,
                name,
                book,
                onWaitDialog
            ) { onSuccess?.invoke(it) }
        }
    }

    // 对照 BookInfoViewModel.importWebFile
    override fun importWebFile(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        success: ((Book) -> Unit)?,
    ) {
        activity.lifecycleScope.launch(IO) {
            try {
                onWaitDialog(true)
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                val fileName = book.getExportFileName(webFile.suffix)
                val uri = FileBook.saveBookFile(webFile.url, fileName, source)
                val localBook = FileBook.importLocalFile(uri)
                val merged = FileBook.mergeBook(localBook, book)
                loadChapterListAndSave(merged)
                activity.runOnUiThread { success?.invoke(merged) }
            } catch (e: Throwable) {
                when (e) {
                    is InvalidBooksDirException -> onAction("selectBooksDir")
                    else -> AppLog.put("ImportWebFileError\n${e.localizedMessage}", e, true)
                }
            } finally {
                onWaitDialog(false)
            }
        }
    }

    // 对照 BookInfoViewModel.downloadWebFile
    override fun downloadWebFile(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        success: ((String) -> Unit)?,
    ) {
        activity.lifecycleScope.launch(IO) {
            try {
                onWaitDialog(true)
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                val fileName = book.getExportFileName(webFile.suffix)
                val uri = FileBook.saveBookFile(webFile.url, fileName, source)
                activity.runOnUiThread { success?.invoke(uri.toString()) }
            } catch (e: Throwable) {
                when (e) {
                    is InvalidBooksDirException -> onAction("selectBooksDir")
                    else -> AppLog.put("DownloadWebFileError\n${e.localizedMessage}", e, true)
                }
            } finally {
                onWaitDialog(false)
            }
        }
    }

    // 对照 BookInfoViewModel.getArchiveFilesName
    override fun getArchiveFilesName(archiveFilePath: String, onSuccess: (List<String>) -> Unit) {
        activity.lifecycleScope.launch(IO) {
            try {
                val names = ArchiveUtils.getArchiveFilesName(archiveFilePath.toUri()) {
                    AppPattern.bookFileRegex.matches(it)
                }
                activity.runOnUiThread { onSuccess.invoke(names) }
            } catch (e: Throwable) {
                AppLog.put("getArchiveEntriesName Error:\n${e.localizedMessage}", e, true)
            }
        }
    }

    // 对照 BookInfoViewModel.importBookFromArchive
    override fun importBookFromArchive(
        archiveFilePath: String,
        archiveEntryName: String,
        book: Book,
        onWaitDialog: (Boolean) -> Unit,
        success: ((Book) -> Unit)?,
    ) {
        activity.lifecycleScope.launch(IO) {
            try {
                onWaitDialog(true)
                val suffix = archiveEntryName.substringAfterLast(".")
                val saveFileName = book.getExportFileName(suffix)
                val books = FileBook.importFromArchive(archiveFilePath, saveFileName) {
                    it.contains(archiveEntryName)
                }
                val imported = books.first()
                val merged = FileBook.mergeBook(imported, book)
                loadChapterListAndSave(merged)
                activity.runOnUiThread { success?.invoke(merged) }
            } catch (e: Throwable) {
                AppLog.put("importArchiveBook Error\n${e.localizedMessage}", e, true)
            } finally {
                onWaitDialog(false)
            }
        }
    }

    // 对照 BookInfoViewModel.refreshWebDavBook
    override fun refreshWebDavBook(book: Book, success: (() -> Unit)?) {
        activity.lifecycleScope.launch(IO) {
            try {
                val remoteUrl = book.getRemoteUrl()
                if (remoteUrl != null) {
                    val bookWebDav = AppWebDav.defaultBookWebDav
                        ?: throw NoStackTraceException("webDav没有配置")
                    val remoteBook = bookWebDav.getRemoteBook(remoteUrl)
                    if (remoteBook == null) {
                        book.origin = BookType.localTag
                    } else if (remoteBook.lastModify > book.lastCheckTime) {
                        val path = bookWebDav.downloadRemoteBook(remoteBook)
                        val uri = path.toUri()
                        book.bookUrl = if (uri.isContentScheme()) uri.toString() else uri.path!!
                        book.lastCheckTime = remoteBook.lastModify
                    }
                }
                activity.runOnUiThread { success?.invoke() }
            } catch (e: Throwable) {
                AppLog.put("RefreshWebDavBookError\n${e.localizedMessage}", e, true)
            }
        }
    }

    // 对照 BookInfoViewModel.changeToLocalBook: mergeBook + loadChapterList + inBookshelf=true
    // 调用方 (importWebFile/importBookFromArchive) 已完成 mergeBook 并同步加载章节
    override fun changeToLocalBook(book: Book): Book {
        runBlocking { loadChapterListAndSave(book) }
        return book
    }

    // 对照 BaseReadViewModel.loadChapterList (本地书分支): FileBook.getChapterList + 入库
    private suspend fun loadChapterListAndSave(book: Book) {
        try {
            val chapters = FileBook.getChapterList(book)
            IntentData.chapterList = chapters
            IntentData.book = book
            book.removeType(BookType.notShelf)
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            if (appDb.bookDao.has(book.bookUrl)) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.insert(book)
            }
        } catch (e: Throwable) {
            AppLog.put("LoadChapterListError\n${e.localizedMessage}", e, true)
        }
    }

    // 对照 BookInfoActivity.upWordCount 内 FileDoc.fromFile(book.bookUrl).size
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(IO) {
        FileDoc.fromFile(bookUrl).size
    }

    // 对照 AppConst.appInfo.versionName
    override fun getAppVersionName(): String? = AppConst.appInfo.versionName

    // 对照 BookInfoActivity.Content 内 LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE
    override fun isLandscape(): Boolean =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 对照 BookInfoActivity.Content 内 setLightStatusBar(if (useDevFeat) isDarkTheme else false)
    override fun setLightStatusBarForBookInfo(useDevFeat: Boolean, isDarkTheme: Boolean) {
        activity.setLightStatusBar(if (useDevFeat) isDarkTheme else false)
    }

    // ===== 书架管理平台能力: 对照 BookshelfManageActivity 同名方法/状态 =====

    // 对照 BookshelfManageActivity.exportUseReplace (AppConfig 读取)
    override fun exportUseReplace(): Boolean = AppConfig.exportUseReplace

    // 对照 BookshelfManageActivity.enableCustomExportChecked
    override fun enableCustomExport(): Boolean = AppConfig.enableCustomExport

    // 对照 BookshelfManageActivity.exportToWebDav
    override fun exportToWebDav(): Boolean = AppConfig.exportToWebDav

    // 对照 BookshelfManageActivity.toggleEnableReplace
    override fun toggleExportUseReplace() {
        AppConfig.exportUseReplace = !AppConfig.exportUseReplace
    }

    // 对照 BookshelfManageActivity.toggleCustomExport
    override fun toggleCustomExport() {
        AppConfig.enableCustomExport = !AppConfig.enableCustomExport
    }

    // 对照 BookshelfManageActivity.toggleExportWebDav
    override fun toggleExportWebDav() {
        AppConfig.exportToWebDav = !AppConfig.exportToWebDav
    }

    // 对照 BookshelfManageActivity.showExportConfig: 导出配置对话框
    // (文件名/导出类型 txt|epub/字符集/不导出章节名, 复刻 dialog_export_config.xml)
    override fun showExportConfig() {
        val fileNameState = mutableStateOf(AppConfig.bookExportFileName.orEmpty())
        val typeState = mutableIntStateOf(if (AppConfig.exportType == 1) 1 else 0)
        val charsetState = mutableStateOf(AppConfig.exportCharset)
        val noChapterNameState = mutableStateOf(AppConfig.exportNoChapterName)
        activity.alert(androidAppString("export_config")) {
            customView {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        androidAppString("export_file_name"),
                        color = AppTheme.colors.primaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    AppTextField(
                        value = fileNameState.value,
                        onValueChange = { fileNameState.value = it },
                        singleLine = true,
                        textStyle = TextStyle(textAlign = TextAlign.Start),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        androidAppString("export_type"),
                        color = AppTheme.colors.primaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf("txt" to 0, "epub" to 1).forEach { (label, value) ->
                            Row(
                                Modifier
                                    .selectable(
                                        selected = typeState.value == value,
                                        role = Role.RadioButton,
                                        onClick = { typeState.value = value },
                                    )
                                    .padding(top = 4.dp, end = 16.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppRadioButton(
                                    selected = typeState.value == value,
                                    onClick = null,
                                )
                                Text(label, color = AppTheme.colors.primaryText, fontSize = 15.sp)
                            }
                        }
                    }
                    Text(
                        androidAppString("export_charset"),
                        color = AppTheme.colors.primaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    AppAutoCompleteField(
                        value = charsetState.value,
                        onValueChange = { charsetState.value = it },
                        label = "charset",
                        values = AppConst.charsets,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = noChapterNameState.value,
                                role = Role.Checkbox,
                                onValueChange = { noChapterNameState.value = it },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(
                            checked = noChapterNameState.value,
                            onCheckedChange = null,
                        )
                        Text(
                            androidAppString("export_no_chapter_name"),
                            color = AppTheme.colors.primaryText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            okButton {
                AppConfig.bookExportFileName = fileNameState.value
                AppConfig.exportType = if (typeState.value == 1) 1 else 0
                AppConfig.exportCharset =
                    charsetState.value.takeIf { it.isNotBlank() } ?: "UTF-8"
                AppConfig.exportNoChapterName = noChapterNameState.value
            }
            cancelButton()
        }
    }

    // 对照 BookshelfManageActivity.configExportSection: 自定义导出章节配置对话框
    // (导出全部/自定义导出 + epub 文件名JS规则 + 分卷大小 + 章节范围, 复刻 dialog_select_section_export.xml)。
    // 仅在导出到文件夹且 enableCustomExport 开启时弹出 (对照 exportDir 回调 value=="cache" 分支)。
    override fun showExportSectionConfig(path: String, books: List<Book>) {
        // 默认选中自定义导出 (对照 cbSelectExport.callOnClick())
        val allState = mutableStateOf(false)
        val customState = mutableStateOf(true)
        val fileNameState = mutableStateOf(AppConfig.episodeExportFileName.orEmpty())
        val sizeState = mutableStateOf("1")
        val scopeState = mutableStateOf("")
        // epub 文件名 JS 规则预览/校验提示 (对照 lyEtEpubFilename.helperText)
        val fileNameHelper = mutableStateOf("")
        // 章节范围错误 (对照 etInputScope.error)
        val scopeError = mutableStateOf<String?>(null)
        activity.alert(androidAppString("select_section_export")) {
            customView {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .toggleable(
                                    value = allState.value,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        // 互斥: 选中导出全部时取消自定义导出并禁用自定义项 (对照 cbAllExport 回调)
                                        allState.value = it
                                        customState.value = !it
                                        if (it) scopeError.value = null
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppCheckbox(checked = allState.value, onCheckedChange = null)
                            Text(
                                androidAppString("export_all"),
                                color = AppTheme.colors.primaryText,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Row(
                            Modifier
                                .weight(1f)
                                .toggleable(
                                    value = customState.value,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        customState.value = it
                                        allState.value = !it
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppCheckbox(checked = customState.value, onCheckedChange = null)
                            Text(
                                androidAppString("custom_export"),
                                color = AppTheme.colors.primaryText,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    // epub 文件名 JS 规则 (分卷, 对照 lyEtEpubFilename/etEpubFilename)
                    Text(
                        androidAppString("export_file_name"),
                        color = AppTheme.colors.primaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    AppTextField(
                        value = fileNameState.value,
                        onValueChange = { fileNameState.value = it },
                        singleLine = true,
                        enabled = customState.value,
                        textStyle = TextStyle(textAlign = TextAlign.Start),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused ->
                                // 失焦时校验并持久化 (对照 etEpubFilename 焦点监听)
                                if (!focused.isFocused &&
                                    tryParesExportFileName(fileNameState.value)
                                ) {
                                    AppConfig.episodeExportFileName = fileNameState.value
                                }
                            },
                        trailingIcon = {
                            // 解析示例按钮 (对照 lyEtEpubFilename 的 endIcon 点击)
                            IconButton(onClick = {
                                fileNameHelper.value =
                                    if (tryParesExportFileName(fileNameState.value)) {
                                        books.firstOrNull()?.let { book ->
                                            androidAppString("result_analyzed") + ": " +
                                                book.getExportFileName(
                                                    "epub",
                                                    1,
                                                    fileNameState.value
                                                )
                                        } ?: androidAppString("result_analyzed")
                                    } else {
                                        "Error"
                                    }
                            }) {
                                Icon(
                                    painter = rememberPainter("ic_play_24dp"),
                                    contentDescription = "Execute script",
                                    tint = AppTheme.colors.primaryText,
                                )
                            }
                        },
                    )
                    Text(
                        "Variable: name, author, epubIndex",
                        color = AppTheme.colors.secondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (fileNameHelper.value.isNotEmpty()) {
                        Text(
                            fileNameHelper.value,
                            color = AppTheme.colors.secondaryText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    // 分卷大小 (对照 lyEtEpubSize/etEpubSize)
                    AppTextField(
                        value = sizeState.value,
                        onValueChange = { new ->
                            if (new.length <= 6 && new.all { it.isDigit() }) sizeState.value = new
                        },
                        singleLine = true,
                        enabled = customState.value,
                        label = androidAppString("file_contains_number"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(textAlign = TextAlign.Start),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 章节范围 (对照 lyEtInputScope/etInputScope, 占位提示 "1-5,8,10-18")
                    AppTextField(
                        value = scopeState.value,
                        onValueChange = {
                            scopeState.value = it
                            scopeError.value = null
                        },
                        singleLine = true,
                        enabled = customState.value,
                        label = androidAppString("export_chapter_index"),
                        placeholder = "1-5,8,10-18",
                        isError = scopeError.value != null,
                        textStyle = TextStyle(textAlign = TextAlign.Start),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    scopeError.value?.let {
                        Text(
                            it,
                            color = Color(0xFFE53935),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            // 校验保留型确认: 范围非法时对话框不关闭 (对照 getButton(POSITIVE) 手动 hide 语义)
            positiveButtonRetain(androidAppString("ok")) {
                if (allState.value) {
                    activity.startExportBooks(path, books)
                    true
                } else {
                    val scopeText = scopeState.value.trim()
                    if (!verificationField(scopeText)) {
                        scopeError.value = androidAppString("error_scope_input")
                        false
                    } else {
                        val epubSize = sizeState.value.toIntOrNull() ?: 1
                        activity.startExportBooksCustom(path, books, epubSize, scopeText)
                        true
                    }
                }
            }
            cancelButton()
        }
    }

    // 对照 LocalConfig.deleteBookOriginal 读取
    override fun getDeleteBookOriginal(): Boolean = LocalConfig.deleteBookOriginal

    // 对照 LocalConfig.deleteBookOriginal 赋值
    override fun setDeleteBookOriginal(value: Boolean) {
        LocalConfig.deleteBookOriginal = value
    }

    // 对照 BookshelfManageActivity.exportAllUseBookSource / viewModel.saveAllUseBookSourceToFile
    override fun exportAllUseBookSource() {
        Coroutine.async {
            val path = "${appCtx.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            val sources = appDb.bookDao.getAllUseBookSource()
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, sources)
            }
            file
        }.onSuccess { file ->
            activity.launchExportDir("bookSource.json", file, "application/json")
        }.onError {
            activity.toastOnUi(it.stackTraceStr)
        }
    }

    // 对照 BookshelfManageActivity.exportAll
    override fun exportAllBooks(books: List<Book>) {
        val path = ACache.get().getAsString("exportBookPath")
        if (path.isNullOrEmpty()) {
            selectExportFolder(books)
        } else {
            activity.startExportBooks(path, books)
        }
    }

    // 对照 BookshelfManageActivity.exportBookshelf / viewModel.exportBookshelf
    @OptIn(ExperimentalSerializationApi::class)
    override fun exportBookshelf(books: List<Book>) {
        Coroutine.async {
            if (books.isEmpty()) throw NoStackTraceException("书籍不能为空")
            val path = "${appCtx.filesDir}/bookshelf.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            // 对齐原 GSON 行为: prettyPrint + 2 空格缩进 + 不序列化 null 字段
            // 字段清单/映射下沉 shared commonMain Book.toShelfJsonMap (13 字段, 与 iOS/desktop/鸿蒙一致)
            val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
            val jsonArray = buildJsonArray {
                books.forEach { book ->
                    add(buildJsonObject {
                        book.toShelfJsonMap().forEach { (key, value) ->
                            // buildMap 保插入序, 字段顺序与原逐字段 put 完全一致; null 已被映射跳过
                            when (value) {
                                is String -> put(key, value)
                                is Number -> put(key, value)
                                is Boolean -> put(key, value)
                                is JsonElement -> put(key, value)
                                else -> Unit
                            }
                        }
                    })
                }
            }
            FileOutputStream(file).use { out ->
                out.write(
                    json.encodeToString(JsonArray.serializer(), jsonArray)
                        .toByteArray(Charsets.UTF_8)
                )
            }
            file
        }.onSuccess { file ->
            activity.launchExportDir("bookshelf.json", file, "application/json")
        }.onError {
            activity.toastOnUi("导出书籍出错\n${it.localizedMessage}")
        }
    }

    // 对照 BookshelfManageActivity.selectExportFolder
    override fun selectExportFolder(books: List<Book>) {
        val path = ACache.get().getAsString("exportBookPath")
        activity.pendingExportBooks = books
        activity.launchExportFolderPicker(path)
    }

    // ===== 书源管理平台能力: 对照 BookSourceActivity 同名方法 =====

    // 对照 BookSourceActivity.addBookSource: 新建书源走导航 push BookSourceEdit (sourceUrl 空串表新建)
    override fun addBookSource() {
        AppNavigatorProviders.getOrNull()?.push(AppRoute.BookSourceEdit(""))
    }

    // 对照 BookSourceActivity.showGroupManage
    // 迁 Compose Overlay: 原 showDialogFragment<GroupManageDialog>() 已由
    // shared OverlayContentHost 的 "group_manage" key 接管 (GroupManageDialogContent)
    override fun showBookSourceGroupManage() {
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("group_manage"))
    }

    // 对照 BookSourceActivity.cancelCheckSource (CheckSource.stop + Debug.finishChecking)
    override fun cancelCheckSource() {
        CheckSource.stop(activity)
        Debug.finishChecking()
    }

    // 书源管理复用 app 端 BookSourceViewModel (组合委托 BookSourceViewModelShared: 16 个 DAO 方法下沉)
    private val bookSourceViewModel by lazy {
        ViewModelProvider(activity).get(BookSourceViewModel::class.java)
    }

    // 对照 BookSourceActivity.selectionAddToGroups: alert 输入分组名后批量加入
    override fun selectionAddToGroups(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        val groups = runBlocking { appDb.bookSourceDao.flowGroups().first() }
        activity.alert(androidAppString("add_group")) {
            val getGroup = editTextView(
                hint = androidAppString("group_name"),
                filterValues = groups,
            )
            okButton {
                getGroup().takeIf { it.isNotEmpty() }?.let {
                    bookSourceViewModel.selectionAddToGroups(selection, it)
                }
            }
            cancelButton()
        }
    }

    // 对照 BookSourceActivity.selectionRemoveFromGroups: alert 输入分组名后批量移出
    override fun selectionRemoveFromGroups(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        val groups = runBlocking { appDb.bookSourceDao.flowGroups().first() }
        activity.alert(androidAppString("remove_group")) {
            val getGroup = editTextView(
                hint = androidAppString("group_name"),
                filterValues = groups,
            )
            okButton {
                getGroup().takeIf { it.isNotEmpty() }?.let {
                    bookSourceViewModel.selectionRemoveFromGroups(selection, it)
                }
            }
            cancelButton()
        }
    }

    // 对照 BookSourceActivity.menu_export_selection: saveToFile + EXPORT 文件选择器
    override fun exportBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean
    ) {
        if (selection.isEmpty()) return
        bookSourceViewModel.saveToFile(
            selection = selection,
            allCount = allCount,
            sortAscending = sortAscending,
            sort = BookSourceSort.Default,
        ) { file ->
            activity.launchExportDir("bookSource.json", file, "application/json")
        }
    }

    // 对照 BookSourceActivity.menu_share_source: saveToFile + share
    override fun shareBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean
    ) {
        if (selection.isEmpty()) return
        bookSourceViewModel.saveToFile(
            selection = selection,
            allCount = allCount,
            sortAscending = sortAscending,
            sort = BookSourceSort.Default,
        ) { file ->
            activity.share(file, title = androidAppString("share_selected_source"))
        }
    }

    // 对照 BookSourceActivity.checkSource: alert 输入关键词 + CheckSource.start
    override fun checkBookSource(selection: List<BookSourcePart>) {
        if (selection.isEmpty()) return
        activity.alert(androidAppString("search_book_key")) {
            val getKey = editTextView(hint = "search word", text = CheckSource.keyword)
            okButton {
                getKey().takeIf { it.isNotEmpty() }?.let { CheckSource.keyword = it }
                CheckSource.start(activity, selection)
                val firstItem = selection.firstOrNull()
                val lastItem = selection.lastOrNull()
                Debug.isChecking = firstItem != null && lastItem != null
                // 校验进度由 BookSourceManageRoute 收集 EventBus.CHECK_SOURCE 驱动,
                // 不再需要原版 adapter 轮询刷新 (startCheckMessageRefreshJob)
            }
            // 对照原版 getButton(BUTTON_NEUTRAL) 手动监听: 打开校验设置且不关闭输入框
            neutralButtonRetain(androidAppString("check_source_config")) {
                AppNavigatorProviders.getOrNull()
                    ?.showOverlay(AppOverlay.Dialog("check_source_config"))
            }
            cancelButton()
        }
    }

    // ===== 书源编辑平台能力: 对照 BookSourceEditActivity 同名方法 =====

    // 对照 BookSourceEditActivity.login / source.showLoginDialog (route 已先 save)
    override fun showBookSourceLogin(source: BookSource) {
        source.showLoginDialog()
    }

    // 对照 BookSourceEditActivity.setSourceVariable / source.showSourceVariableDialog (route 已先 save);
    // VariableDialog 已下沉 shared: 经 sourceVariable Overlay 弹出
    override fun showBookSourceVariableDialog(source: BookSource) {
        AppNavigatorProviders.getOrNull()?.showOverlay(
            AppOverlay.Dialog(
                key = "sourceVariable",
                payload = encodeSourceVariableOverlayPayload(source),
            )
        )
    }

    // ===== 主题设置弹窗平台能力: 对照 ThemeConfigFragment 同名方法 =====

    // 对照 ThemeConfigFragment: "themeList" -> ThemeListDialog().show(childFragmentManager, "themeList")
// ThemeListDialog 应用主题后内部已 postEvent(RECREATE) 刷新, 无需额外处理
// 迁 Compose Overlay: 原 showDialogFragment(ThemeListDialog()) 已由
// shared OverlayContentHost 的 "theme_list" key 接管 (ThemeListOverlayDialogContent
// 通过 ThemeConfigProviders 获取数据, 编辑/新建委托 showThemeCustomizeDialog)
    override fun showThemeListDialog() {
        AppNavigatorProviders.getOrNull()?.showOverlay(AppOverlay.Dialog("theme_list"))
    }

    // 主题自定义编辑 (对照 ThemeCustomizeDialog.editConfig / newConfig)
// 仍走 Fragment (ThemeCustomizeDialog 未下沉 shared)
    override fun showThemeCustomizeDialog(configIndex: Int?, isNight: Boolean) {
        if (configIndex != null) {
            ThemeCustomizeDialog.editConfig(configIndex)
                .show(activity.supportFragmentManager, "themeCustomize")
        } else {
            ThemeCustomizeDialog.newConfig(isNight)
                .show(activity.supportFragmentManager, "themeCustomize")
        }
    }

    // 对照 ThemeConfigFragment: "customizeDayTheme" -> ThemeCustomizeDialog.editPrefs(false)
    override fun showCustomizeDayThemeDialog() {
        activity.showDialogFragment(ThemeCustomizeDialog.editPrefs(false))
    }

    // 对照 ThemeConfigFragment: "customizeNightTheme" -> ThemeCustomizeDialog.editPrefs(true)
    override fun showCustomizeNightThemeDialog() {
        activity.showDialogFragment(ThemeCustomizeDialog.editPrefs(true))
    }

    // 对照 ThemeConfigFragment.configBottomNav: dialog_bottom_nav_config.xml Compose 重建
    override fun showBottomNavConfigDialog() {
        val defaultNavItems = listOf(
            BottomNavConfigItem(BottomNavTag.HOME, androidAppString("home"), AppConfig.showHome),
            BottomNavConfigItem(BottomNavTag.BOOKSHELF, androidAppString("bookshelf"), true),
            BottomNavConfigItem(
                BottomNavTag.DISCOVERY,
                androidAppString("discovery"),
                AppConfig.showDiscovery
            ),
            BottomNavConfigItem(BottomNavTag.MY, androidAppString("my"), true),
        )
        // 对照原版: 保存顺序合法才采用, 否则回退默认顺序
        val savedOrder =
            AppConfig.bottomNavItemOrder.orEmpty().split(",").filter { it.isNotEmpty() }
        val defaultTags = defaultNavItems.map { it.tag }.toSet()
        val initialItems = if (savedOrder.size == defaultNavItems.size
            && savedOrder.toSet() == defaultTags
        ) {
            savedOrder.mapNotNull { tag -> defaultNavItems.find { it.tag == tag } }
        } else {
            defaultNavItems
        }
        val navItems = mutableStateListOf<BottomNavConfigItem>().apply { addAll(initialItems) }
        val height = mutableStateOf(AppConfig.bottomBarHeight)
        val iconSize = mutableStateOf(AppConfig.bottomBarIconSize)
        val labelMode = mutableStateOf(AppConfig.bottomBarLabelMode)

        activity.alert(title = androidAppString("bottom_nav_config")) {
            customView {
                BottomNavConfigContent(navItems, height, iconSize, labelMode)
            }
            okButton {
                val newShowHome = navItems.find { it.tag == BottomNavTag.HOME }?.enabled ?: true
                val newShowDiscovery =
                    navItems.find { it.tag == BottomNavTag.DISCOVERY }?.enabled ?: true
                val newOrder = navItems.joinToString(",") { it.tag }
                var changed = AppConfig.showHome != newShowHome
                    || AppConfig.showDiscovery != newShowDiscovery
                    || AppConfig.bottomNavItemOrder != newOrder
                AppConfig.showHome = newShowHome
                AppConfig.showDiscovery = newShowDiscovery
                AppConfig.bottomNavItemOrder = newOrder
                if (AppConfig.bottomBarHeight != height.value) {
                    AppConfig.bottomBarHeight = height.value; changed = true
                }
                if (AppConfig.bottomBarIconSize != iconSize.value) {
                    AppConfig.bottomBarIconSize = iconSize.value; changed = true
                }
                if (AppConfig.bottomBarLabelMode != labelMode.value) {
                    AppConfig.bottomBarLabelMode = labelMode.value; changed = true
                }
                // 对照原版: 有变更才 recreateActivities()
                if (changed) postEvent(EventBus.RECREATE, "")
            }
            neutralButtonRetain(androidAppString("reset")) {
                // 对照原版 neutralButton: 恢复默认值但不关闭对话框
                navItems.clear()
                navItems.addAll(defaultNavItems.map { it.copy(enabled = true) })
                height.value = AppConfigConstants.BOTTOM_BAR_HEIGHT_DEFAULT
                iconSize.value = AppConfigConstants.BOTTOM_BAR_ICON_DEFAULT
                labelMode.value = AppConfigConstants.BOTTOM_BAR_LABEL_DEFAULT
            }
            cancelButton()
        }
    }

    // 对照 ThemeConfigFragment.configBookshelf: 书架布局配置对话框 (dialog_bookshelf_config.xml Compose 重建)
    override fun showBookshelfLayoutDialog() {
        val bookshelfLayout = AppConfig.bookshelfLayout
        // 校验态 (对照原版 spGroupStyle 越界回 0 / rgSort 越界回 0, 并回写 pref)
        var initGroupStyle = AppConfig.bookGroupStyle
        if (initGroupStyle !in 0..1) {
            initGroupStyle = 0
            AppConfig.bookGroupStyle = 0
        }
        var initSort = AppConfig.bookshelfSort
        if (initSort !in 0..5) {
            initSort = 0
            AppConfig.bookshelfSort = 0
        }
        val groupStyle = mutableStateOf(initGroupStyle)
        val bookshelfSort = mutableStateOf(initSort)
        val fixedWidthMode = mutableStateOf(AppConfig.bookshelfFixedWidthMode)
        val gridWidthText = mutableStateOf(AppConfig.bookshelfGridWidth.toString())
        val introLines = mutableStateOf(AppConfig.bookshelfListIntroLines)
        val selectedCols = mutableStateOf(BookSource.exploreStyleCols(bookshelfLayout))
        val isVideo = mutableStateOf(BookSource.exploreStyleIsVideo(bookshelfLayout))
        val showUnread = mutableStateOf(AppConfig.showUnread)
        val showLastUpdateTime = mutableStateOf(AppConfig.showLastUpdateTime)
        val showGroupCount = mutableStateOf(AppConfig.bookshelfShowGroupCount)
        val showKind = mutableStateOf(AppConfig.bookshelfListShowKind)
        val showIntro = mutableStateOf(AppConfig.bookshelfListShowIntro)

        activity.alert(title = androidAppString("bookshelf_layout")) {
            customView {
                BookshelfLayoutConfigContent(
                    groupStyle = groupStyle,
                    bookshelfSort = bookshelfSort,
                    fixedWidthMode = fixedWidthMode,
                    gridWidthText = gridWidthText,
                    introLines = introLines,
                    selectedCols = selectedCols,
                    isVideo = isVideo,
                    showUnread = showUnread,
                    showLastUpdateTime = showLastUpdateTime,
                    showGroupCount = showGroupCount,
                    showKind = showKind,
                    showIntro = showIntro,
                )
            }
            okButton {
                var notifyMain = false
                var recreate = false
                if (AppConfig.bookGroupStyle != groupStyle.value) {
                    AppConfig.bookGroupStyle = groupStyle.value
                    notifyMain = true
                }
                if (AppConfig.showUnread != showUnread.value) {
                    AppConfig.showUnread = showUnread.value
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                if (AppConfig.showLastUpdateTime != showLastUpdateTime.value) {
                    AppConfig.showLastUpdateTime = showLastUpdateTime.value
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                if (AppConfig.bookshelfShowGroupCount != showGroupCount.value) {
                    AppConfig.bookshelfShowGroupCount = showGroupCount.value
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                if (AppConfig.bookshelfListShowKind != showKind.value) {
                    AppConfig.bookshelfListShowKind = showKind.value
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                if (AppConfig.bookshelfListShowIntro != showIntro.value) {
                    AppConfig.bookshelfListShowIntro = showIntro.value
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                if (AppConfig.bookshelfListIntroLines != introLines.value) {
                    AppConfig.bookshelfListIntroLines = introLines.value
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                if (AppConfig.bookshelfSort != bookshelfSort.value) {
                    AppConfig.bookshelfSort = bookshelfSort.value
                    // 排序变更走 BOOKSHELF_REFRESH 重建 flow (对照 BookshelfScreen2 sortTick 契约)
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                // 对照原版 makeLayoutStyle: 视频置 EXPLORE_STYLE_VIDEO_FLAG, 列数取低 3 位
                val newLayout =
                    (if (isVideo.value) BookSource.EXPLORE_STYLE_VIDEO_FLAG else 0) or
                        (selectedCols.value and BookSource.EXPLORE_STYLE_COLS_MASK)
                val newGridWidth = gridWidthText.value.toIntOrNull() ?: 120
                if (bookshelfLayout != newLayout ||
                    AppConfig.bookshelfFixedWidthMode != fixedWidthMode.value ||
                    AppConfig.bookshelfGridWidth != newGridWidth
                ) {
                    AppConfig.bookshelfLayout = newLayout
                    AppConfig.bookshelfFixedWidthMode = fixedWidthMode.value
                    AppConfig.bookshelfGridWidth = newGridWidth
                    recreate = true
                }
                if (recreate) {
                    // 对照原版 recreateActivities: 布局变更重建界面
                    postEvent(EventBus.RECREATE, "")
                } else if (notifyMain) {
                    postEvent(EventBus.NOTIFY_MAIN, false)
                }
            }
            cancelButton()
        }
    }

    // ===== 其它设置平台能力: 对照 OtherConfigHost 同名方法 =====

    // 对照 OtherConfigHost.alertLocalPassword okButton: LocalConfig.password = getText()
    override fun setLocalPassword(password: String?) {
        LocalConfig.password = password
    }

    // 对照 OtherConfigHost.localBookTreeSelect.launch DIR_SYS, 回调由 MainActivity 桥接
    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        activity.pendingBookTreeUriCallback = onSelected
        activity.launchBookTreeUriPicker()
    }

    // 对照 OtherConfigHost.onCheckSource: showDialogFragment<CheckSourceConfig>
    // 迁 Compose Overlay: 原 CheckSourceConfig() Fragment 已由
    // shared OverlayContentHost 的 "check_source_config" key 接管
    // onDismiss 回调: 监听 overlays 列表中 "check_source_config" 被移除
    override fun showCheckSourceConfigDialog(onDismiss: () -> Unit) {
        val navigator = AppNavigatorProviders.getOrNull()
        if (navigator != null) {
            activity.lifecycleScope.launch(IO) {
                // 等 Overlay 出现在栈中
                navigator.overlays.first { it.any { o -> o.key == "check_source_config" } }
                // 等 Overlay 从栈中移除
                navigator.overlays.first { it.none { o -> o.key == "check_source_config" } }
                withContext(kotlinx.coroutines.Dispatchers.Main) { onDismiss() }
            }
            navigator.showOverlay(AppOverlay.Dialog("check_source_config"))
        }
    }

    // 对照 OtherConfigHost.onUploadRule: showDialogFragment<DirectLinkUploadConfig>
    // 迁 Compose Overlay: 原 showDialogFragment<DirectLinkUploadConfig>() 已由
    // shared OverlayContentHost 的 "direct_link_upload_config" key 接管
    override fun showDirectLinkUploadConfigDialog() {
        AppNavigatorProviders.getOrNull()
            ?.showOverlay(AppOverlay.Dialog("direct_link_upload_config"))
    }

    // 对照 ConfigViewModel.clearWebViewData: 删 webview 目录 + toast + delay + restart
    override fun clearWebViewData() {
        Coroutine.async {
            FileUtils.delete(activity.getDir("webview", Context.MODE_PRIVATE))
            FileUtils.delete(activity.getDir("hws_webview", Context.MODE_PRIVATE), true)
            activity.toastOnUi(androidAppString("clear_webview_data_success"))
            delay(3000)
            appCtx.restart()
        }.onError {
            AppLog.put("清理 WebView 数据失败\n${it.localizedMessage}", it)
        }
    }

    // ===== 导入书籍私有辅助: 复刻 ImportBookActivity 同名方法 =====

    /** 首次访问导入相关方法时按 pref 初始化 rootDoc (对照 ImportBookActivity.initRootDoc) */
    private fun ensureRootDoc() {
        if (importViewModel.rootDoc != null) return
        val lastPath = AppConfig.importBookPath
        if (lastPath.isNullOrBlank()) {
            // 对照 initRootDoc: 未设置目录时显示空态并弹选择器
            importEmptyMsgState.value = true
            activity.launchImportFolderPicker()
            return
        }
        val rootUri = if (lastPath.isUri()) {
            lastPath.toUri()
        } else {
            Uri.fromFile(File(lastPath))
        }
        kotlin.runCatching {
            if (rootUri.isContentScheme()) {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(activity, rootUri)
            } else {
                androidx.documentfile.provider.DocumentFile.fromFile(File(rootUri.path!!))
            }?.let { doc ->
                if (!doc.name.isNullOrEmpty() && doc.isDirectory) {
                    importViewModel.subDocs.clear()
                    importViewModel.rootDoc = FileDoc.fromDocumentFile(doc)
                }
            }
        }.onFailure {
            importEmptyMsgState.value = true
            activity.launchImportFolderPicker()
        }
    }

    @Synchronized
    private fun nextDoc(fileDoc: FileDoc) {
        importViewModel.subDocs.add(fileDoc)
        upPath()
    }

    @Synchronized
    private fun goBackDir(): Boolean {
        return if (importViewModel.subDocs.isNotEmpty()) {
            importViewModel.subDocs.removeAt(importViewModel.subDocs.lastIndex)
            upPath()
            true
        } else {
            false
        }
    }

    @Synchronized
    private fun upPath() {
        importViewModel.rootDoc?.let {
            scanDocJob?.cancel()
            upDocs(it)
        }
    }

    private fun upDocs(rootDoc: FileDoc) {
        // 对照 ImportBookActivity.upDocs: 拼面包屑路径 + 隐藏空态 + 加载目录
        var lastDoc = rootDoc
        var path = rootDoc.name + File.separator
        for (doc in importViewModel.subDocs) {
            lastDoc = doc
            path = path + doc.name + File.separator
        }
        importPathState.value = path
        importEmptyMsgState.value = false
        importViewModel.loadDoc(lastDoc)
    }

    private fun startRead(fileDoc: FileDoc) {
        if (ArchiveUtils.isArchive(fileDoc.name)) {
            val fileNames = runCatching {
                ArchiveUtils.getArchiveFilesName(fileDoc) { AppPattern.bookFileRegex.matches(it) }
            }.getOrDefault(emptyList())
            when {
                fileNames.isEmpty() -> activity.toastOnUi(androidAppString("unsupport_archivefile_entry"))
                fileNames.size == 1 -> openArchiveBook(fileDoc, fileNames.first())
                else -> activity.selector(androidAppString("start_read"), fileNames) { _, name, _ ->
                    openArchiveBook(fileDoc, name)
                }
            }
            return
        }
        runBlocking { appDb.bookDao.getBookByFileName(fileDoc.name) }?.let {
            val filePath = fileDoc.toString()
            if (it.bookUrl != filePath) {
                it.bookUrl = filePath
                runBlocking { appDb.bookDao.insert(it) }
            }
            AppNavigatorProviders.getOrNull()?.push(it.toReadRoute())
        }
    }

    // 对照 RemoteBookActivity.startRead 的 archive 分支 + showRemoteBookDownloadAlert:
    // 默认书籍目录里找已下载的压缩包, 没有就弹确认框由调用方重新下载后重试
    override fun startReadRemoteArchive(fileName: String, onNeedDownload: () -> Unit) {
        val treeUri = AppConfig.defaultBookTreeUri ?: return
        val archiveDoc = FileDoc.fromUri(treeUri.toUri(), true).find(fileName)
        if (archiveDoc == null) {
            activity.alert(androidAppString("draw"), androidAppString("archive_not_found")) {
                okButton { onNeedDownload() }
                noButton()
            }
        } else {
            startRead(archiveDoc)
        }
    }

    private fun openArchiveBook(fileDoc: FileDoc, fileName: String) {
        runBlocking { appDb.bookDao.getBookByFileName(fileName) }?.let { book ->
            AppNavigatorProviders.getOrNull()?.push(book.toReadRoute())
            return
        }
        activity.alert(androidAppString("draw"), androidAppString("no_book_found_bookshelf")) {
            okButton {
                activity.lifecycleScope.launch(IO) {
                    val book = runCatching {
                        FileBook.importFromArchive(fileDoc.uri, fileName) { it.contains(fileName) }
                    }.getOrNull()?.firstOrNull()
                    activity.runOnUiThread {
                        book?.let { AppNavigatorProviders.getOrNull()?.push(it.toReadRoute()) }
                    }
                }
            }
            noButton()
        }
    }

    // ===== 关于页私有辅助: 复刻 AboutActivity 同名 private 方法 =====

    private fun showMdFileInternal(title: String, fileName: String) {
        val mdText = runCatching {
            activity.assets.open(fileName).bufferedReader().use { it.readText() }
        }.getOrNull() ?: javaClass.classLoader
            ?.getResourceAsStream(fileName)
            ?.bufferedReader()
            ?.use { it.readText() }

        if (mdText != null) {
            activity.showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
        } else {
            val path =
                if (fileName == "LICENSE.md") "LICENSE" else "shared/src/commonMain/resources/$fileName"
            activity.openUrl("https://github.com/huajideshutiao/legado/blob/master/$path")
        }
    }

    private fun saveLogInternal() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(backupPath.toUri(), true)
            copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDumpInternal() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(backupPath.toUri(), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = appCtx.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")

        dumpLogcat(logcatFile)

        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)

        doc.find("logs.zip")?.delete()

        zipFile.inputStream().use { input ->
            doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use {
                process.inputStream.copyTo(it)
            }
        } catch (e: Exception) {
            AppLog.put("保存Logcat失败\n$e", e)
        }
    }
}

/** 底栏配置条目 (对照 ThemeConfigFragment.configBottomNav 内 NavItem, 书架/我的不可隐藏) */
private data class BottomNavConfigItem(
    val tag: String,
    val name: String,
    val enabled: Boolean,
) {
    val locked get() = tag == BottomNavTag.BOOKSHELF || tag == BottomNavTag.MY
}

/** 对照 MainNavItem.iconKey: 启用取实心, 禁用取空心 */
private fun bottomNavIconKey(tag: String, enabled: Boolean): String = when (tag) {
    BottomNavTag.HOME -> if (enabled) "ic_bottom_home_s" else "ic_bottom_home_e"
    BottomNavTag.BOOKSHELF -> if (enabled) "ic_bottom_books_s" else "ic_bottom_books_e"
    BottomNavTag.DISCOVERY -> if (enabled) "ic_bottom_explore_s" else "ic_bottom_explore_e"
    else -> if (enabled) "ic_bottom_person_s" else "ic_bottom_person_e"
}

/**
 * 底栏配置正文 (对照 dialog_bottom_nav_config.xml Compose 重建)。
 * 顺序网格: 点按开关启用, 横向拖拽换序 (对照 rv_nav_items + ItemTouchHelper);
 * 高度/图标大小滑条 (对照 sb_height/sb_icon); 标签模式单选 (对照 rg_label_mode)。
 */
@Composable
private fun BottomNavConfigContent(
    items: SnapshotStateList<BottomNavConfigItem>,
    height: MutableState<Int>,
    iconSize: MutableState<Int>,
    labelMode: MutableState<Int>,
) {
    val colors = AppTheme.colors
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            rememberString("bottom_nav_items_order"),
            color = colors.primaryText,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        var dragIndex by remember { mutableIntStateOf(-1) }
        var dragAccum by remember { mutableFloatStateOf(0f) }
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(items) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val cellWidth = (size.width / items.size).coerceAtLeast(1)
                            dragIndex = (offset.x / cellWidth).toInt().coerceIn(0, items.lastIndex)
                            dragAccum = 0f
                        },
                        onDragEnd = { dragIndex = -1; dragAccum = 0f },
                        onDragCancel = { dragIndex = -1; dragAccum = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            if (dragIndex in items.indices) {
                                dragAccum += amount.x
                                val cellWidth = (size.width / items.size).coerceAtLeast(1)
                                // 越过半个格宽换一位 (对照 ItemTouchHelper 默认阈值)
                                while (dragAccum >= cellWidth / 2f && dragIndex < items.lastIndex) {
                                    Collections.swap(items, dragIndex, dragIndex + 1)
                                    dragIndex++
                                    dragAccum -= cellWidth
                                }
                                while (dragAccum <= -cellWidth / 2f && dragIndex > 0) {
                                    Collections.swap(items, dragIndex, dragIndex - 1)
                                    dragIndex--
                                    dragAccum += cellWidth
                                }
                            }
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = !item.locked) {
                            items[index] = item.copy(enabled = !item.enabled)
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tint = if (item.enabled) colors.accent else colors.primaryText
                    Icon(
                        painter = rememberPainter(bottomNavIconKey(item.tag, item.enabled)),
                        contentDescription = item.name,
                        tint = tint,
                        modifier = Modifier.size(iconSize.value.dp),
                    )
                    Text(
                        item.name,
                        color = tint,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 高度滑条 (对照 sb_height: MIN 36, 范围 36..80)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                rememberString("bottom_bar_height"),
                color = colors.primaryText,
                modifier = Modifier.weight(1f),
            )
            Text("${height.value}dp", color = colors.primaryText)
        }
        AppSlider(
            value = height.value - AppConfigConstants.BOTTOM_BAR_HEIGHT_MIN,
            max = AppConfigConstants.BOTTOM_BAR_HEIGHT_MAX - AppConfigConstants.BOTTOM_BAR_HEIGHT_MIN,
            onValueChange = { height.value = it + AppConfigConstants.BOTTOM_BAR_HEIGHT_MIN },
        )
        // 图标大小滑条 (对照 sb_icon: MIN 18, 范围 18..36)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                rememberString("bottom_bar_icon_size"),
                color = colors.primaryText,
                modifier = Modifier.weight(1f),
            )
            Text("${iconSize.value}dp", color = colors.primaryText)
        }
        AppSlider(
            value = iconSize.value - AppConfigConstants.BOTTOM_BAR_ICON_MIN,
            max = AppConfigConstants.BOTTOM_BAR_ICON_MAX - AppConfigConstants.BOTTOM_BAR_ICON_MIN,
            onValueChange = { iconSize.value = it + AppConfigConstants.BOTTOM_BAR_ICON_MIN },
        )
        // 标签模式单选 (对照 rg_label_mode: 0=隐藏 1=常显 2=仅选中 3=自动, 可横向滚动)
        Text(
            rememberString("bottom_bar_label_mode"),
            color = colors.primaryText,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val labelRes = listOf(
                rememberString("bottom_bar_label_unlabeled"),
                rememberString("bottom_bar_label_labeled"),
                rememberString("bottom_bar_label_selected"),
                rememberString("bottom_bar_label_auto"),
            )
            labelRes.forEachIndexed { i, res ->
                Row(
                    Modifier
                        .selectable(
                            selected = labelMode.value == i,
                            onClick = { labelMode.value = i })
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppRadioButton(selected = labelMode.value == i, onClick = null)
                    Text(res, color = colors.primaryText)
                }
            }
        }
    }
}

/**
 * 书架布局配置正文 (对照 dialog_bookshelf_config.xml Compose 重建)。
 * 分组样式/样式/固定宽/列数/简介行数/排序等逐项等价; 列表模式专属项按 isList 显隐
 * (对照原版 updateListOnlyVisibility: 非固定宽且列数 <= 1 视为列表)。
 */
@Composable
private fun BookshelfLayoutConfigContent(
    groupStyle: MutableState<Int>,
    bookshelfSort: MutableState<Int>,
    fixedWidthMode: MutableState<Boolean>,
    gridWidthText: MutableState<String>,
    introLines: MutableState<Int>,
    selectedCols: MutableState<Int>,
    isVideo: MutableState<Boolean>,
    showUnread: MutableState<Boolean>,
    showLastUpdateTime: MutableState<Boolean>,
    showGroupCount: MutableState<Boolean>,
    showKind: MutableState<Boolean>,
    showIntro: MutableState<Boolean>,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val groupStyles = rememberStringArray("group_style")
    val itemStyles = rememberStringArray("explore_item_style")
    val sortLabels = arrayOf(
        rememberString("bookshelf_px_0"), rememberString("bookshelf_px_1"), rememberString("bookshelf_px_2"),
        rememberString("bookshelf_px_3"), rememberString("bookshelf_px_4"), rememberString("bookshelf_px_5"),
    )
    // 列表模式: 非固定宽且列数 <= 1 (对照原版 updateListOnlyVisibility)
    val isList = !fixedWidthMode.value && selectedCols.value <= 1

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        ConfigDropdownRow(
            label = rememberString("group_style"),
            options = groupStyles,
            selectedIndex = groupStyle.value,
            onSelect = { groupStyle.value = it },
        )
        ConfigDropdownRow(
            label = rememberString("explore_style"),
            options = itemStyles,
            selectedIndex = if (isVideo.value) 1 else 0,
            onSelect = { isVideo.value = it == 1 },
        )
        ConfigSwitchRow(rememberString("show_unread"), showUnread.value) {
            showUnread.value = it
        }
        ConfigSwitchRow(rememberString("bookshelf_show_group_count"), showGroupCount.value) {
            showGroupCount.value = it
        }
        ConfigSwitchRow(rememberString("fixed_width_mode"), fixedWidthMode.value) {
            fixedWidthMode.value = it
        }
        // 视图小节 (对照原版 tv_layout_title)
        Text(
            rememberString("view"),
            color = colors.accent,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        // 列数 (对照原版 sb_column_count 0..6, 固定宽模式隐藏)
        if (!fixedWidthMode.value) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rememberString("column_count"),
                    color = colors.primaryText,
                    modifier = Modifier.padding(end = 8.dp),
                )
                AppSlider(
                    value = selectedCols.value,
                    max = 6,
                    onValueChange = { selectedCols.value = it },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    selectedCols.value.toString(),
                    color = colors.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        // 列表模式专属项
        if (isList) {
            ConfigSwitchRow(rememberString("bookshelf_list_show_kind"), showKind.value) {
                showKind.value = it
            }
            ConfigSwitchRow(rememberString("bookshelf_list_show_intro"), showIntro.value) {
                showIntro.value = it
            }
            // 简介行数 1..5 (对照原版 tv_intro_lines_minus/plus, 未开简介降透明度)
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (showIntro.value) 1f else 0.4f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    rememberString("bookshelf_list_intro_lines"),
                    color = colors.primaryText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "-",
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { if (introLines.value > 1) introLines.value-- },
                )
                Text(
                    introLines.value.toString(),
                    color = colors.primaryText,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    "+",
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { if (introLines.value < 5) introLines.value++ },
                )
            }
            ConfigSwitchRow(
                rememberString("show_last_update_time"),
                showLastUpdateTime.value
            ) {
                showLastUpdateTime.value = it
            }
        }
        // 固定宽模式: 网格宽度 dp (对照原版 ll_fixed_width / et_grid_width)
        if (fixedWidthMode.value) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(rememberString("grid_width_dp"), color = colors.primaryText)
                AppTextField(
                    value = gridWidthText.value,
                    onValueChange = { gridWidthText.value = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(textAlign = TextAlign.Center),
                    modifier = Modifier.weight(1f),
                )
                Text("dp", color = colors.primaryText)
            }
        }
        // 排序小节 (对照原版 rg_sort 6 项单选)
        Text(
            rememberString("sort"),
            color = colors.accent,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Column(Modifier.selectableGroup()) {
            sortLabels.forEachIndexed { i, label ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = bookshelfSort.value == i,
                            role = Role.RadioButton,
                            onClick = { bookshelfSort.value = i },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppRadioButton(selected = bookshelfSort.value == i, onClick = null)
                    Text(label, color = colors.primaryText, fontSize = 15.sp)
                }
            }
        }
    }
}

/** 标签 + 下拉单行 (对照原版 AppCompatSpinner 行) */
@Composable
private fun ConfigDropdownRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = AppTheme.colors
    val expanded = remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.primaryText, modifier = Modifier.weight(1f))
        Box {
            Row(
                Modifier
                    .clickable { expanded.value = true }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    options.getOrElse(selectedIndex) { "" },
                    color = colors.primaryText,
                    fontSize = 14.sp,
                )
                Icon(
                    painter = rememberPainter("ic_arrow_drop_down"),
                    contentDescription = null,
                    tint = colors.secondaryText,
                )
            }
            AppDropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false }) {
                options.forEachIndexed { i, item ->
                    DropdownMenuItem(onClick = { expanded.value = false; onSelect(i) }) {
                        Text(item, color = colors.primaryText)
                    }
                }
            }
        }
    }
}

/** 标签 + 开关行 (对照原版 SwitchCompat 行) */
@Composable
private fun ConfigSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AppTheme.colors.primaryText, modifier = Modifier.weight(1f))
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
