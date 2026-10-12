package io.legado.app.ui.main

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.RoundedCorner
import android.view.ViewConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.App
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.appInfo
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.exception.LocalBookSourceFileDeleteException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.CrashHandler
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.IntentHelp
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.BookChapterLoader
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.delBookCore
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.toShelfJsonMap
import io.legado.app.help.book.toggleBookshelfCore
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.effectiveBookExportDir
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.copyToFileAtomically
import io.legado.app.help.file.AndroidDirAuthorizations
import io.legado.app.help.i18n.androidAppString
import io.legado.app.help.writeTextAtomically
import io.legado.app.lib.theme.MonetColorExtract
import io.legado.app.model.BookCover
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.importFromArchive
import io.legado.app.service.WebService
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.import.ImportBookDirHost
import io.legado.app.ui.book.import.ImportDirRef
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.book.import.local.ImportBookShared
import io.legado.app.ui.book.import.local.ImportBookViewModel
import io.legado.app.ui.book.info.WebFileImportShared
import io.legado.app.ui.book.manage.reportLocalBookSourceFileDeleteFailure
import io.legado.app.ui.book.read.config.FontItem
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.ui.book.source.manage.BookSourceViewModel
import io.legado.app.ui.compose.component.AppAutoCompleteField
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.platform.rememberStringArray
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.config.MODE_EDIT_CONFIG
import io.legado.app.ui.config.MODE_EDIT_PREFS
import io.legado.app.ui.config.MODE_NEW_CONFIG
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.BookRef
import io.legado.app.ui.root.DialogTransitionSpec
import io.legado.app.ui.root.RouteTransitionSpec
import io.legado.app.ui.root.SharedPlatformCapabilities
import io.legado.app.ui.root.TransitionEasing
import io.legado.app.ui.root.encodeBookVariableOverlayPayload
import io.legado.app.ui.root.encodeSourceVariableOverlayPayload
import io.legado.app.ui.root.ensureBookSaveDirSelected
import io.legado.app.ui.root.toReadRoute
import io.legado.app.ui.root.toRouteRef
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.copyToDocumentAtomically
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getClipText
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isPad
import io.legado.app.utils.isTv
import io.legado.app.utils.isUri
import io.legado.app.utils.list
import io.legado.app.utils.toJson
import io.legado.app.utils.openFileUri
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.restart
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.verificationField
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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
import java.io.File
import java.io.InputStream

/**
 * 澎湃 OS activity 转场插值器参数 (miui-services.jar
 * `AppTransitionInjector$ActivityTranstionInterpolator`, 由 createActivityOpenCloseTransition
 * 以 response=0.8 / damping=0.95 构造)。
 */
private val MiuiActivityTransitionEasing =
    TransitionEasing.Spring(response = 0.8f, damping = 0.95f)

class AndroidPlatformCapabilities(
    private val activity: MainActivity,
) : SharedPlatformCapabilities {

    /** 对照 app 端 Activity 的 lifecycleScope (随 Activity 销毁取消)。 */
    override val capabilityScope: CoroutineScope
        get() = activity.lifecycleScope

    // 导入书籍: 目录状态机在 data/commonMain ImportBookShared (四端一份),
    // 本类只注入 FileDoc/SAF 列举能力; 上架仍走 ImportBookViewModel (需要 Context toast)
    private val importViewModel by lazy {
        ViewModelProvider(activity).get(ImportBookViewModel::class.java)
    }

    /** FileDoc 的目录引用包装 (状态机经它列举/条目化) */
    private inner class AndroidImportDirRef(val fileDoc: FileDoc) : ImportDirRef {
        override val displayName: String get() = fileDoc.name
        override val isDirectory: Boolean get() = fileDoc.isDir
        override val pathKey: String get() = fileDoc.uri.toString()

        override suspend fun listChildren(): List<ImportDirRef>? =
            fileDoc.list()?.map { AndroidImportDirRef(it) }

        override suspend fun toItem(isUpDir: Boolean): ImportFileItem =
            ImportBook(fileDoc, isUpDir = isUpDir)
    }

    private val importDirHost = object : ImportBookDirHost {
        override fun dirRefOf(item: ImportFileItem): ImportDirRef? =
            (item as? ImportBook)?.takeIf { it.file.isDir }?.let { AndroidImportDirRef(it.file) }

        override suspend fun resolveRoot(pathKey: String): ImportDirRef? {
            var path = pathKey
            if (path.isBlank()) {
                if (AppConfig.importBookPath.isNullOrBlank()) {
                    AppConfig.importBookPath = AppConfig.defaultBookTreeUri
                }
                path = AppConfig.importBookPath ?: return null
            }
            val rootUri = if (path.isUri()) path.toUri() else Uri.fromFile(File(path))
            val doc = runCatching {
                if (rootUri.isContentScheme()) {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(activity, rootUri)
                } else {
                    androidx.documentfile.provider.DocumentFile.fromFile(File(rootUri.path!!))
                }
            }.getOrNull()
            if (doc == null || doc.name.isNullOrEmpty() || !doc.isDirectory) return null
            return AndroidImportDirRef(FileDoc.fromDocumentFile(doc))
        }
    }

    init {
        ImportBookShared.attach(importDirHost)
    }

    /**
     * 选目录流程的在途标记: 存储说明框弹出期间与选择器返回之前都为真, 两者共用。
     *
     * 期间新来的请求一律不重复拉起 (系统同一时刻只允许一个选择器 / 说明框已在展示),
     * 只把接续动作换成最新的一次 (见 [launchImportFolderPicker])。
     */
    private var importFolderPickerPending = false

    /** 在途期间最新的接续动作 (选定后执行; 取消、或说明框那条链收尾时丢弃)。 */
    private var pendingImportFolderAction: (() -> Unit)? = null

    /**
     * 本次进页面是否已问过"书籍保存位置" (对照原版 BaseImportBookActivity.setBookStorage 只在
     * onActivityCreated 执行一次); 退出页面时复位, 下次进入重新问。
     */
    private var importStorageChecked = false

    // ===== 全局转场动画平台 spec (方案 A: 动画单一注入点参数化) =====
    // Android: 澎湃 OS activity 转场语义, 逐字复刻 miui-services.jar 的
    // com.android.server.wm.AppTransitionInjector.createActivityOpenCloseTransition:
    // 前进 = 新页全宽滑入 (不淡入) + 旧页左移 25% 并压暗到 0.5;
    // 返回 = 目标页自左 25% 滑回并从 0.5 恢复 + 出栈页全宽滑出 (不淡出);
    // 整个 AnimationSet 共用弹簧插值器 (response 0.8 / damping 0.95), 时长 500ms。
    //
    // 不读主题 windowAnimationStyle 的转场动画资源: 澎湃的转场在 system_server 里用代码
    // 构造, framework-res 的 Animation.Activity 及全部 RRO overlay 仍是 AOSP 原版
    // (96dp 位移 / 450ms), 读资源只能得到 AOSP 观感而非本机系统观感。
    //
    // 时长运行时动态读系统动画时长缩放 (Settings.Global ANIMATOR_DURATION_SCALE ×
    // TRANSITION_ANIMATION_SCALE 取小值: 任一关闭即关闭转场动画, 尊重用户"动画时长缩放"
    // 设置, >1 时与系统一致放慢); 对话框沿用系统 dialog 动画资源规范 200ms/150ms。

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
     * 屏幕圆角半径 px, 逐字复刻 AppTransitionInjector.initDisplayRoundCorner:
     * 取四角中非零者的最小值 (minRadius 忽略 0 的角), 取不到或全 0 时回退 60px。
     * 不缓存: 折叠屏切换屏幕后半径会变, 每次读 spec 时重取 (取值来自 DisplayInfo, 很廉价)。
     */
    private fun displayCornerRadiusPx(): Float {
        val fallback = 60f
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback
        val display = activity.display ?: return fallback
        val radii = intArrayOf(
            RoundedCorner.POSITION_TOP_LEFT,
            RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT,
        ).map { display.getRoundedCorner(it)?.radius?.toFloat() ?: 0f }
        val min = radii.filter { it > 0f }.minOrNull() ?: return fallback
        return min
    }

    override val routeTransitionSpec: RouteTransitionSpec
        get() {
            val scale = systemAnimationScale()
            val duration = (500 * scale).toInt()
            return RouteTransitionSpec(
                pushDurationMillis = duration,
                pushEasing = MiuiActivityTransitionEasing,
                newPageSlideFraction = 1f, // 新页全宽滑入
                oldPageShiftFraction = 0.25f, // 旧页左移 25%
                newPageFadeIn = false, // 新页不淡入 (AlphaAnimation(1,1))
                oldPageFadeOut = false, // 旧页走压暗蒙版而非淡出
                newPageScaleFrom = 1f,
                popDurationMillis = duration,
                popEasing = MiuiActivityTransitionEasing,
                targetPageSlideFraction = 0.25f, // 目标页自左 25% 滑回
                outgoingSlideFraction = 1f, // 出栈页全宽滑出
                targetPageFadeIn = false,
                outgoingFadeOut = false, // 出栈页不淡出 (AlphaAnimation(1,1))
                targetPageScaleFrom = 1f,
                underPageDimAlpha = 0.5f, // 旧页/目标页压暗到 0.5
                pageCornerRadiusPx = displayCornerRadiusPx(), // setHasRoundedCorners 语义
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
            if (targetIntent.resolveActivity(App.instance.packageManager) != null) {
                App.instance.startActivity(targetIntent)
            } else {
                App.instance.toastOnUi(androidAppString("can_not_open"))
            }
        } catch (e: Exception) {
            AppLog.put("打开链接失败", e, true)
        }
    }

    override fun openWebView(url: String, sourceKey: String, sourceName: String) {
        // 移动端保留内嵌 WebViewRoute 路由语义 (对话框内嵌)
        AppNavigatorProviders.get().push(
            io.legado.app.ui.root.AppRoute.WebView(
                url = url,
                sourceKey = sourceKey,
                sourceName = sourceName,
            )
        )
    }

    override fun shareText(text: String) {
        activity.share(text)
    }

    /**
     * 网页搜索 (对照原版 TextActionMenu.menu_browser): URL 直接 ACTION_VIEW,
     * 非 URL 走 ACTION_WEB_SEARCH 交系统选择应用。
     * 无浏览器/无应用响应属于设备环境条件 (非本端不变量被破坏), 故 toast 提示而非崩阅读页。
     */
    override fun searchWeb(text: String) {
        runCatching {
            val intent = if (text.isAbsUrl()) {
                Intent(Intent.ACTION_VIEW).apply { data = text.toUri() }
            } else {
                Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, text) }
            }
            activity.startActivity(intent)
        }.onFailure {
            it.printOnDebug()
            activity.toastOnUi(it.localizedMessage ?: "ERROR")
        }
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

    override fun upLoadFile(
        fileName: String,
        file: Any,
        contentType: String,
        onResult: (String?) -> Unit
    ) {
        activity.lifecycleScope.launch(IO) {
            runCatching {
                DirectLinkUpload.upLoad(fileName, file, contentType)
            }.onSuccess { url ->
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(url) }
            }.onFailure { error ->
                AppLog.put("上传文件失败\n${error.localizedMessage}", error)
                activity.toastOnUi(error.localizedMessage ?: error.toString())
                withContext(kotlinx.coroutines.Dispatchers.Main) { onResult(null) }
            }
        }
    }

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

    // 起停走 Android Service 壳; 运行态/地址由 PlatformCapabilities 默认实现回读 WebServerManager
    override fun setWebService(enabled: Boolean) {
        if (enabled) WebService.start(activity) else WebService.stop(activity)
    }

    // 换封面源 / 段评列表 / 默认封面图集: 走 shared Overlay (与桌面/iOS/鸿蒙同一实现,
    // 见 SharedPlatformCapabilities)

    // 图集列表缓存已下沉 BookCoverShared (raw 串记忆化自动失效), 仅需清 Drawable 解码缓存
    override fun refreshDefaultCover() {
        BookCover.evictDrawableCache()
    }

    // 对照 IntentHelp.openTTSSetting
    override fun openTtsSettings() {
        IntentHelp.openTTSSetting()
    }

    // 对照 ReadAloudConfigDialog 的"+"按钮/行编辑: 其他端经 showOverlay("httpTtsEdit") 弹共享编辑窗口
    override fun showHttpTtsEditDialog(engine: HttpTTS?) {
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog("httpTtsEdit", payload = engine?.id?.toString())
        )
    }

    // 对照 app 端 FontSelectDialog.loadFontFiles: pref 字体目录 + externalFiles/font 合并去重排序
    override suspend fun scanFontItems(): List<FontItem> = withContext(IO) {
        val fontRegex = Regex("(?i).*\\.[ot]tf")
        val items = arrayListOf<FontItem>()
        val fontPath = AppConfig.fontFolder
        if (!fontPath.isNullOrBlank()) {
            runCatching {
                if (fontPath.isContentScheme()) {
                    // 选定的字体目录是 SAF 树引用: 一律用已授权的系统列举 (对照原版 loadFontFiles
                    // 的 FileDoc.list 分支), 不转文件系统路径 —— 只有主存储卷转得出真实路径,
                    // 其它卷与第三方提供方得到的是假路径, 照它列举只会得到空列表
                    FileDoc.fromUri(fontPath.toUri(), true)
                        .list { !it.isDir && it.name.matches(fontRegex) }
                        ?.forEach { doc ->
                            items.add(FontItem(doc.uri.toString(), doc.name))
                        }
                } else {
                    scanFontDir(items, File(fontPath), fontRegex)
                }
            }.onFailure {
                AppLog.put("字体目录读取失败, 本次只用内置字体目录: $fontPath\n${it.message}")
            }
        }
        // 对照 getLocalFonts: externalFiles/font
        scanFontDir(items, File(FileUtils.getPath(App.instance.externalFiles, "font")), fontRegex)
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

    // 对照 ViewConfiguration.get(ctx).scaledTouchSlop
    override fun getScaledTouchSlop(): Int =
        ViewConfiguration.get(activity).scaledTouchSlop

    // 对照 ImportBookActivity.onPickFolder / selectFolder.launch: 选完目录重建 rootDoc
    override fun pickImportFolder() {
        launchImportFolderPicker { rebuildImportRoot() }
    }

    /** 由 MainActivity 的选目录回调转发: 选定与取消都到此收尾 (清在途状态)。 */
    internal fun onImportFolderSelected(selected: Boolean) {
        importFolderPickerPending = false
        val action = pendingImportFolderAction
        pendingImportFolderAction = null
        if (selected) action?.invoke()
    }

    /**
     * 导入页退出时的收尾: 停掉本页发起的目录扫描/读取, 否则页面已关仍在后台遍历目录;
     * 并复位"已问过存储位置", 下次进入重新走一遍。
     * 选目录选择器在途时保留在途请求 (选择器返回时照旧消费)。
     */
    override fun onImportBookPageCleared() {
        ImportBookShared.cancelPageJobs()
        importStorageChecked = false
    }

    /**
     * 拉起选目录选择器; [onSelected] 在用户选定后执行 (取消时丢弃)。
     *
     * 已有流程在途时只把接续动作换成最新的一次, 不重复拉起选择器 (系统同一时刻只允许一个):
     * 原先直接 return, 调用方期待的后续动作会被静默丢掉。
     */
    private fun launchImportFolderPicker(onSelected: () -> Unit) {
        pendingImportFolderAction = onSelected
        if (importFolderPickerPending) return
        importFolderPickerPending = true
        activity.launchImportFolderPicker()
    }

    /**
     * 选定新目录后重建根目录并读一次目录 (对照原版 selectFolder 回调里的 initRootDoc(true)):
     * 直接按新选定的目录重建, 不再经过存储位置引导。
     */
    private fun rebuildImportRoot() {
        activity.lifecycleScope.launch {
            val path = AppConfig.importBookPath
            val ref = path?.takeIf { it.isNotBlank() }?.let { importDirHost.resolveRoot(it) }
            if (ref != null) {
                ImportBookShared.setRoot(ref)
            } else {
                ImportBookShared.clearImportRoot()
            }
        }
    }

    override fun openImportFile(filePath: String) {
        val uri = filePath.toUri()
        activity.supportFragmentManager.commit {
            add(
                io.legado.app.ui.association.FileAssociationFragment(uri),
                "FileAssociationFragment",
            )
        }
    }

    // 对照 ImportBookActivity.onScanFolder / scanFolder
    override fun scanImportFolder() {
        ImportBookShared.scanImportFolder()
    }

    // ===== 导入书籍状态流 override (对照 ImportBookActivity 同名字段; 源在 ImportBookShared) =====

    override fun importBookItems(): StateFlow<List<ImportFileItem>> = ImportBookShared.items

    override fun importBookPath(): StateFlow<String?> = ImportBookShared.path

    override fun importBookLoading(): StateFlow<Boolean> = ImportBookShared.loading

    override fun importBookEmptyMsgVisible(): StateFlow<Boolean> = ImportBookShared.emptyMsgVisible

    override fun importBookHasParentDir(): StateFlow<Boolean> = ImportBookShared.hasParentDir

    /**
     * 对照 ImportBookActivity.onActivityCreated: setBookStorage + initData。
     *
     * 单页导航下页面跨进出复用, 故"每次进页面重走一遍初始化"由本方法承担
     * (目录状态机已有根目录时只重读当前目录)。
     *
     * @return true = 尚无可用目录, 页面据此拉起一次选择器
     */
    override suspend fun initImportBookData(): Boolean {
        if (!importStorageChecked) {
            importStorageChecked = true
            ensureImportStorage()
        }
        return ImportBookShared.initPage()
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
        // 压缩包导入需要书籍保存位置: 未设置时经既有"书籍保存位置"选择器选定后继续导入
        importViewModel.addToBookshelf(books, onNeedBooksDir = { ensureBookSaveDirSelected() }) {
            books.forEach { it.isOnBookShelf = true }
            onComplete()
        }
    }

    // 对照 ImportBookActivity.onSearchTextChange / viewModel.updateCallBackFlow
    override fun updateImportBookFilter(key: String) {
        ImportBookShared.updateImportBookFilter(key)
    }

    // 对照 ImportBookActivity.upSort: 更新排序 + 持久化 + 非扫描中重排当前列表
    override fun updateImportBookSort(sort: Int) {
        ImportBookShared.updateImportBookSort(sort)
    }

    // 对照 ImportBookActivity.onItemClick isDir 分支 / startRead
    override fun openImportedBookReader(item: ImportFileItem) {
        (item as? ImportBook)?.let { startRead(it.file) }
    }

    // 对照 ImportBookActivity.onItemClick isDir 分支 / nextDoc
    override fun navigateImportDir(item: ImportFileItem) {
        ImportBookShared.navigateImportDir(item)
    }

    // 对照 ImportBookActivity.onItemClick isUpDir 分支 / goBackDir
    override fun goBackImportDir() {
        ImportBookShared.goBackImportDir()
    }

    // 对照原版 AboutFragment.saveLog
    override fun saveLog() {
        saveLogInternal()
    }

    // 对照原版 AboutFragment.createHeapDump
    override fun createHeapDump() {
        createHeapDumpInternal()
    }

    // ===== 书籍详情页平台能力: 对照 BookInfoActivity 同名方法 =====

    // 对照 BookInfoActivity.onShelfClick / deleteBook: 上架/下架, webFile 走下载导入
    override fun toggleBookshelf(
        book: Book,
        inBookshelf: Boolean,
        onComplete: (Boolean?) -> Unit,
        onWaitDialog: (Boolean) -> Unit,
        onAction: (String) -> Unit,
        chapters: List<BookChapter>?,
    ) {
        if (inBookshelf) {
            deleteBook(book, onComplete)
        } else if (book.isWebFile) {
            // webFile: 弹下载导入选择框, 导入完成后标记已上架 (对照 onShelfClick isWebFile 分支)
            handleWebFileRead(book, onWaitDialog, onAction) { onComplete(true) }
        } else {
            // 上架走 shared 统一核心 toggleBookshelfCore (对照原 addToBookshelf → Book.save),
            // 与 desktop/iOS/ohos 一致, 避免各端维护多份拷贝
            // onComplete 里调用方要做 pop/dispatch 等 UI 操作, 回主线程 (对照 delBook)
            activity.lifecycleScope.launch(IO) {
                runCatching { book.toggleBookshelfCore(false, chapters) }
                    .onSuccess { activity.runOnUiThread { onComplete(it) } }
                    .onFailure {
                        AppLog.put("书架操作失败\n${it.message}", it)
                        activity.runOnUiThread { onComplete(false) }
                    }
            }
        }
    }

    // 对照 BookInfoActivity.deleteBook: 下架确认框由本能力面弹 (系统 alert), 勾选值也由它自己取
    override fun confirmsDeleteBookInCapability(): Boolean = true

    /**
     * 下架走本能力面自己的流程: 系统 alert 问一句 → 删记录 → 清正文缓存 + 封面图片缓存 →
     * 按勾选删本地书源文件 (见 [deleteBook] / [delBook])。
     *
     * 调用方传来的 [deleteOriginal] 不使用: 弹框后的勾选值才是准的 (初始值同样取自偏好)。
     */
    override fun deleteBookshelfBook(
        book: Book,
        @Suppress("UNUSED_PARAMETER") deleteOriginal: Boolean,
        onComplete: (Boolean?) -> Unit,
    ) {
        deleteBook(book, onComplete)
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
                            .padding(vertical = DesignTokens.spacingDefault),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacingMd),
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
                // 删章节/删书/标记 notShelf/本地源文件走 shared 统一核心 delBookCore
                book.delBookCore(deleteOriginal)
                // 正文缓存
                BookHelp.clearCache(book)
                // 封面缓存 (对照 BaseReadViewModel.delBook; MultiDiskCache.remove 传裸 url 即双区双删)
                runCatching {
                    val cache = coil3.SingletonImageLoader.get(activity).diskCache
                    book.coverUrl?.let { cache?.remove(it) }
                }
            }.onSuccess { activity.runOnUiThread { onComplete(null) } }
                .onFailure { e ->
                    AppLog.put("删除书籍失败\n${e.message}", e)
                    // 只有源文件没删掉才是"库里已删、文件还在" (见 delBookCore), 与书架删除同一文案
                    if (e is LocalBookSourceFileDeleteException) {
                        reportLocalBookSourceFileDeleteFailure(book, e)
                    }
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
                    AppNavigatorProviders.get().showOverlay(
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
                AppNavigatorProviders.get().showOverlay(
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

    // 对照 BookInfoActivity.uploadBook: 远端已有同名书时先确认, 否则误触会覆盖远端且无法撤销
    override fun uploadBook(book: Book, success: (() -> Unit)?) {
        if (book.getRemoteUrl() == null) {
            doUploadBook(book, success)
            return
        }
        activity.alert(
            title = androidAppString("draw"),
            message = androidAppString("sure_upload"),
        ) {
            okButton { doUploadBook(book, success) }
            cancelButton()
        }
    }

    // 对照 BookInfoViewModel.uploadBook
    private fun doUploadBook(book: Book, success: (() -> Unit)?) {
        activity.lifecycleScope.launch(IO) {
            try {
                val bookWebDav = AppWebDav.defaultBookWebDav
                    ?: throw NoStackTraceException("未配置webDav")
                AndroidDirAuthorizations.ensureBookFileUsage(book)
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
                // 保存位置未设置时走既有的"去设置目录"出口 (与其它三端同一入口/同一偏好键)
                if (e is InvalidBooksDirException) selectBookSaveDir()
                AppLog.put("下载远程书籍<${book.name}>失败", e, true)
            }
        }
    }

    /**
     * 拉起既有的 SAF"书籍保存位置"选择器并记住选定目录 (与"其它设置 → 书籍保存位置"
     * 同一入口/同一偏好键); 用户取消时不动既有配置。
     */
    private fun selectBookSaveDir() {
        activity.runOnUiThread {
            pickBookTreeUri { uri -> if (uri != null) AppConfig.defaultBookTreeUri = uri }
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
            // 构建 webFile 列表的平台无关段已下沉 WebFileImportShared (同 BaseReadViewModel.loadWebFile)
            val webFiles = WebFileImportShared.buildWebFiles(book, source)
            activity.runOnUiThread {
                if (webFiles.isEmpty()) {
                    activity.toastOnUi("Unexpected webFileData")
                    return@runOnUiThread
                }
                showWebFileDownloadAlert(book, webFiles, source, onWaitDialog, onAction, onSuccess)
            }
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
        // 原版走 execute{}, 其 executeContext 默认 Dispatchers.Main, 故等待框/动作/成功
        // 三类回调都在主线程; 这里统一 runOnUiThread 回主线程与原版对齐
        activity.lifecycleScope.launch(IO) {
            try {
                activity.runOnUiThread { onWaitDialog(true) }
                // 平台无关段 (取源 / 下载保存 / 导入本地 / 合并) 已下沉 WebFileImportShared;
                // 目录落库统一走 BookChapterLoader.fetchFromSource (与阅读页同一条链),
                // 不再另写一份"取目录 + 落库"
                val merged = WebFileImportShared.importWebFile(book, webFile)
                BookChapterLoader.fetchFromSource(
                    merged,
                    WebFileImportShared.bookSourceOf(merged),
                )
                activity.runOnUiThread { success?.invoke(merged) }
            } catch (e: Throwable) {
                when (e) {
                    is InvalidBooksDirException -> activity.runOnUiThread {
                        onAction("selectBooksDir")
                    }

                    else -> AppLog.put("ImportWebFileError\n${e.localizedMessage}", e, true)
                }
            } finally {
                activity.runOnUiThread { onWaitDialog(false) }
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
                activity.runOnUiThread { onWaitDialog(true) }
                // 平台无关段 (取源 / 下载保存) 已下沉 WebFileImportShared
                val path = WebFileImportShared.downloadWebFile(book, webFile)
                activity.runOnUiThread { success?.invoke(path) }
            } catch (e: Throwable) {
                when (e) {
                    is InvalidBooksDirException -> activity.runOnUiThread {
                        onAction("selectBooksDir")
                    }

                    else -> AppLog.put("DownloadWebFileError\n${e.localizedMessage}", e, true)
                }
            } finally {
                activity.runOnUiThread { onWaitDialog(false) }
            }
        }
    }

    // 对照 BookInfoViewModel.getArchiveFilesName
    override fun getArchiveFilesName(archiveFilePath: String, onSuccess: (List<String>) -> Unit) {
        activity.lifecycleScope.launch(IO) {
            try {
                // 后缀校验 + 书籍条目过滤 + 读失败返空列表的平台无关段已下沉 WebFileImportShared
                val names = WebFileImportShared.getArchiveFilesName(archiveFilePath)
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
                activity.runOnUiThread { onWaitDialog(true) }
                // 平台无关段 (按条目名导入 + 合并) 已下沉 WebFileImportShared;
                // 目录落库统一走 BookChapterLoader.fetchFromSource (与阅读页同一条链)
                val merged = WebFileImportShared.importBookFromArchive(
                    archiveFilePath,
                    archiveEntryName,
                    book,
                )
                BookChapterLoader.fetchFromSource(
                    merged,
                    WebFileImportShared.bookSourceOf(merged),
                )
                activity.runOnUiThread { success?.invoke(merged) }
            } catch (e: Throwable) {
                AppLog.put("importArchiveBook Error\n${e.localizedMessage}", e, true)
            } finally {
                activity.runOnUiThread { onWaitDialog(false) }
            }
        }
    }

    // 对照 BookInfoViewModel.refreshWebDavBook
    override suspend fun refreshWebDavBook(book: Book) {
        val remoteUrl = book.getRemoteUrl() ?: return
        val bookWebDav = AppWebDav.defaultBookWebDav
            ?: throw NoStackTraceException("webDav没有配置")
        val remoteBook = bookWebDav.getRemoteBook(remoteUrl)
        if (remoteBook == null) {
            book.origin = BookType.localTag
            return
        }
        if (remoteBook.lastModify > book.lastCheckTime) {
            val uri = try {
                bookWebDav.downloadRemoteBook(remoteBook)
            } catch (e: InvalidBooksDirException) {
                // 保存位置未设置: 走既有"去设置目录"出口 (与其它三端同一入口/同一偏好键),
                // 异常照旧上抛 (调用方仍记日志), 只是多了一次用户可见的出口
                selectBookSaveDir()
                throw e
            }.toUri()
            book.bookUrl = if (uri.isContentScheme()) uri.toString() else uri.path!!
            book.lastCheckTime = remoteBook.lastModify
        }
    }

    // 对照 BookInfoActivity.upWordCount 内 FileDoc.fromFile(book.bookUrl).size
    override suspend fun localBookFileSize(bookUrl: String): Long = withContext(IO) {
        AndroidDirAuthorizations.ensureBookFileUsage(bookUrl)
        FileDoc.fromFile(bookUrl).size
    }

    // 对照 AppConst.appInfo.versionName
    override fun getAppVersionName(): String? = AppConst.appInfo.versionName

    // 对照 BookInfoActivity.Content 内 LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE
    override fun isLandscape(): Boolean =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 对照 ChapterProvider.upLayout 的 appCtx.isPad（"平板/横屏双页" auto 分支）
    override fun isTablet(): Boolean = activity.isPad

    // 对照原版 BaseReadBookActivity 的 isTv（阅读页首启引导的电视机型分支）
    override fun isTelevision(): Boolean = activity.isTv

    // 对照原版 BaseActivity.isInMultiWindow: 多窗口下阅读页不避让状态栏、不进沉浸式布局
    override val isInMultiWindow: Boolean get() = activity.isInMultiWindow

    // ===== 书架管理平台能力: 对照 BookshelfManageActivity 同名方法/状态 =====

    // 对照 BookshelfManageActivity.enableCustomExportChecked
    override fun enableCustomExport(): Boolean = AppConfig.enableCustomExport

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
                        .padding(vertical = DesignTokens.spacingDefault)
                ) {
                    Text(
                        androidAppString("export_file_name"),
                        color = AppTheme.colors.primaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = DesignTokens.spacingXs),
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
                        modifier = Modifier.padding(top = DesignTokens.spacingMd, bottom = DesignTokens.spacingXs),
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
                                        selected = typeState.intValue == value,
                                        role = Role.RadioButton,
                                        onClick = { typeState.intValue = value },
                                    )
                                    .padding(top = DesignTokens.spacingXs, end = DesignTokens.spacingLg, bottom = DesignTokens.spacingXs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppRadioButton(
                                    selected = typeState.intValue == value,
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
                        modifier = Modifier.padding(top = DesignTokens.spacingDefault, bottom = DesignTokens.spacingXs),
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
                            .padding(vertical = DesignTokens.spacingDefault),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(
                            checked = noChapterNameState.value,
                            onCheckedChange = null,
                        )
                        Text(
                            androidAppString("export_no_chapter_name"),
                            color = AppTheme.colors.primaryText,
                            modifier = Modifier.padding(start = DesignTokens.spacingDefault),
                        )
                    }
                }
            }
            okButton {
                AppConfig.bookExportFileName = fileNameState.value
                AppConfig.exportType = if (typeState.intValue == 1) 1 else 0
                AppConfig.exportCharset =
                    charsetState.value.takeIf { it.isNotBlank() } ?: "UTF-8"
                AppConfig.exportNoChapterName = noChapterNameState.value
            }
            cancelButton()
        }
    }

    // 对照 BookshelfManageActivity.configExportSection: 自定义导出章节配置对话框
    // (导出全部/自定义导出 + epub 文件名JS规则 + 分卷大小 + 章节范围, 复刻 dialog_select_section_export.xml)。
    // 仅在选择导出文件夹且 enableCustomExport() 成立 (开关开启且导出类型为 epub) 时弹出
    // (对照 exportDir 回调 value=="cache" 分支)。
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
                        .padding(vertical = DesignTokens.spacingDefault)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = DesignTokens.spacingXs),
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
                                .padding(vertical = DesignTokens.spacingDefault),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppCheckbox(checked = allState.value, onCheckedChange = null)
                            Text(
                                androidAppString("export_all"),
                                color = AppTheme.colors.primaryText,
                                modifier = Modifier.padding(start = DesignTokens.spacingDefault),
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
                                .padding(vertical = DesignTokens.spacingDefault),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppCheckbox(checked = customState.value, onCheckedChange = null)
                            Text(
                                androidAppString("custom_export"),
                                color = AppTheme.colors.primaryText,
                                modifier = Modifier.padding(start = DesignTokens.spacingDefault),
                            )
                        }
                    }
                    // epub 文件名 JS 规则 (分卷, 对照 lyEtEpubFilename/etEpubFilename)
                    Text(
                        androidAppString("export_file_name"),
                        color = AppTheme.colors.primaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = DesignTokens.spacingDefault, bottom = DesignTokens.spacingXs),
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
            val path = "${App.instance.filesDir}/shareBookSource.json"
            val sources = appDb.bookDao.getAllUseBookSource()
            // 整份落盘: 导出步骤与书源恢复读的都是这个固定路径, 覆盖写被中断会拿到半截 JSON
            check(writeTextAtomically(path, GSON.toJson(sources))) { "写入失败: $path" }
            File(path)
        }.onSuccess { file ->
            activity.launchExportDir("bookSource.json", file, "application/json")
        }.onError {
            activity.toastOnUi(it.stackTraceStr)
        }
    }

    // 对照 BookshelfManageActivity.exportAll: 已选过导出目录 → 直接导出;
    // 未选过 → 拉起目录选择器 (写公共目录要 SAF 授权, 没有可用惯例目录)
    override fun exportAllBooks(books: List<Book>) {
        val path = effectiveBookExportDir()
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
            val path = "${App.instance.filesDir}/bookshelf.json"
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
            // 整份落盘: 导出步骤读的是这个固定路径, 覆盖写被中断会拿到半截 JSON
            check(
                writeTextAtomically(
                    path,
                    json.encodeToString(JsonArray.serializer(), jsonArray),
                )
            ) { "写入失败: $path" }
            File(path)
        }.onSuccess { file ->
            activity.launchExportDir("bookshelf.json", file, "application/json")
        }.onError {
            activity.toastOnUi("导出书籍出错\n${it.localizedMessage}")
        }
    }

    // 对照 BookshelfManageActivity.selectExportFolder (既有的"选择导出文件夹"入口):
    // 读设备本地记忆的导出目录 ([effectiveBookExportDir]) 作选择器默认项
    // (写出选择结果由选择器回调负责, 见 MainActivity 的 exportDir)
    override fun selectExportFolder(books: List<Book>) {
        val path = effectiveBookExportDir()
        activity.pendingExportBooks = books
        activity.launchExportFolderPicker(path)
    }

    // ===== 书源管理平台能力: 对照 BookSourceActivity 同名方法 =====

    // 新建书源 / 变量弹窗 / 主题列表: 走 shared 实现 (见 SharedPlatformCapabilities)

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
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        if (selection.isEmpty()) return
        bookSourceViewModel.saveToFile(
            selection = selection,
            allCount = allCount,
            sortAscending = sortAscending,
            sort = sort,
        ) { file ->
            activity.launchExportDir("bookSource.json", file, "application/json")
        }
    }

    // 对照 BookSourceActivity.menu_share_source: saveToFile + share
    // 传当前 sort 不一致
    override fun shareBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort
    ) {
        if (selection.isEmpty()) return
        bookSourceViewModel.saveToFile(
            selection = selection,
            allCount = allCount,
            sortAscending = sortAscending,
            sort = sort,
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
                // 校验期间的亮屏由 shared BookSourceManageRoute 按 Debug.checkState.isChecking
                // 统一驱动 (四端一处接线), 此处不再各自开关
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
                AppNavigatorProviders.get()
                    .showOverlay(AppOverlay.Dialog("check_source_config"))
            }
            cancelButton()
        }
    }

    // 对照 BookSourceActivity.resumeCheckSource: 校验中重进书源管理页时让 Service 重发一次
    // 进度事件 (IntentAction.resume → upNotification → postEvent(CHECK_SOURCE)) 点亮进度条。
    // 亮屏由 shared 路由按 Debug.checkState.isChecking 统一驱动, 本方法不再单独开关。
    // "是否校验中" 由路由按 Debug.isChecking 判定 (对照原版 if (!Debug.isChecking) return)
    override fun resumeCheckSource() {
        CheckSource.resume(activity)
    }

    // ===== 书源编辑平台能力: 对照 BookSourceEditActivity 同名方法 =====

    // ===== 主题设置弹窗平台能力: 对照 ThemeConfigFragment 同名方法 =====

    // 主题自定义编辑 (对照 ThemeCustomizeDialog.editConfig / newConfig)
    // 经 showOverlay("theme_customize") 弹 shared 完整实现 (含背景图/模糊, 选图走 FilePickerService)
    override fun showThemeCustomizeDialog(configIndex: Int?, isNight: Boolean) {
        val mode = if (configIndex == null) MODE_NEW_CONFIG else MODE_EDIT_CONFIG
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog("theme_customize", payload = "$mode,${configIndex ?: -1},$isNight")
        )
    }

    // 对照 ThemeConfigFragment: "customizeDayTheme" -> ThemeCustomizeDialog.editPrefs(false)
    override fun showCustomizeDayThemeDialog() {
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,false")
        )
    }

    // 对照 ThemeConfigFragment: "customizeNightTheme" -> ThemeCustomizeDialog.editPrefs(true)
    override fun showCustomizeNightThemeDialog() {
        AppNavigatorProviders.get().showOverlay(
            AppOverlay.Dialog("theme_customize", payload = "$MODE_EDIT_PREFS,-1,true")
        )
    }

    // 莫奈取色: 壁纸取色 API 仅 Android 12+ (S=31) 提供; eInk 显隐门在 ThemeConfigRoute
    // (对齐 AppTheme 读取层判定, 本端不再重复判断)
    override val monetColorExtractSupported: Boolean
        get() = MonetColorExtract.isSupported()

    // 一键取当前壁纸主色派生日/夜主题色; 取不到时 toast 且不改任何状态 (含主题 pref/刷新)
    override fun applyMonetColorFromWallpaper() {
        MonetColorExtract.applyFromWallpaper(activity) {
            activity.toastOnUi(androidAppString("monet_color_extract_failed"))
        }
    }

    // 换桌面图标 (对照 master ThemeConfigFragment: launcherIcon -> LauncherIconHelp.changeIcon):
    // setComponentEnabledSetting 切换 WelcomeActivity / Launcher1/4/5 的启用态, 桌面图标即变
    override fun changeLauncherIcon(icon: String) {
        LauncherIconHelp.changeIcon(icon)
    }

    override val launcherIconChangeSupported: Boolean get() = true

    // AudioPlayService / WebService 用 MediaPlaybackLock 真持唤醒锁, 两个唤醒锁开关只在本端显示
    override val wakeLockSupported: Boolean get() = true

    // 以下几项均为安卓独有能力, 其余端无消费方 (其他设置里相应条目自动隐藏)
    // Cronet: App.onCreate registerAndroidCronetProvider 注册了 CronetProvider
    override val cronetSupported: Boolean get() = true

    // 语言: AppContextWrapper.wrap 包 Context (attachBaseContext), 改完重启生效
    override val languageSwitchSupported: Boolean get() = true

    // 对照原版 OtherConfigFragment 的 PreferKey.language -> appCtx.restart()
    override fun applyAppLanguage() {
        App.instance.restart()
    }

    // 媒体按键: MediaButtonReceiver 读 mediaButtonOnExit / readAloudByMediaButton
    override val mediaButtonSupported: Boolean get() = true

    // 音频焦点: AudioFocusController 真抢焦点 (ignoreAudioFocus 短路它)
    override val audioFocusSupported: Boolean get() = true

    // 堆转储记录: CrashHandler 在 OOM 时读 recordHeapDump 决定要不要 doHeapDump
    override val heapDumpRecordSupported: Boolean get() = true

    // 文字操作菜单 (PROCESS_TEXT): 对应 manifest 里的 ProcessTextActivity activity-alias
    override val processTextSupported: Boolean get() = true

    /**
     * 读文字操作菜单开启态, 逐字对照原版 OtherConfigFragment.isProcessTextEnabled:
     * 读 alias 组件启用态而非读 pref —— 用户可能在系统设置里改过。
     * DEFAULT 态 (从未手动改过) 按 manifest 的 exported=true 算开启, 所以只排 DISABLED。
     */
    override fun isProcessTextEnabled(): Boolean =
        activity.packageManager.getComponentEnabledSetting(processTextComponent) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /** 对照原版 setProcessTextEnable: setComponentEnabledSetting 切 alias 启用态。 */
    override fun setProcessTextEnabled(enabled: Boolean) {
        activity.packageManager.setComponentEnabledSetting(
            processTextComponent,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** PROCESS_TEXT activity-alias (对照原版 OtherConfigFragment.componentName)。 */
    private val processTextComponent by lazy {
        ComponentName(App.instance, "io.legado.app.ui.association.ProcessTextActivity")
    }

    // ===== 其它设置平台能力: 对照 OtherConfigHost 同名方法 =====

    // 对照 OtherConfigHost.alertLocalPassword okButton: LocalConfig.password = getText()
    // 写的是 "local" SP 文件 (备份加密口令与 default SP 分文件存放)
    override fun setLocalPassword(password: String?) {
        LocalConfig.password = password
    }

    // 对照 OtherConfigHost.localBookTreeSelect.launch DIR_SYS, 回调由 MainActivity 桥接
    override fun pickBookTreeUri(onSelected: (String?) -> Unit) {
        activity.pendingBookTreeUriCallback = onSelected
        activity.launchBookTreeUriPicker()
    }

    // 对照 app 端 BaseReadBookActivity 的 FileDoc.fromUri(uri, true).find(book.originName):
    // SAF 逐层列举, 所选目录找不到再进一级子目录找
    // 只有普通文件算命中, 同名项不是文件时继续往后找 (与桌面/鸿蒙同一判据)
    override fun findBookFileInDir(dirUri: String, fileName: String): String? {
        val dir = runCatching { FileDoc.fromUri(dirUri.toUri(), true) }.getOrNull() ?: return null
        val children = dir.list() ?: return null
        children.firstOrNull { !it.isDir && it.name == fileName }?.let { return it.uri.toString() }
        return children.asSequence()
            .filter { it.isDir }
            .firstNotNullOfOrNull { sub ->
                sub.list()?.firstOrNull { !it.isDir && it.name == fileName }
            }
            ?.uri?.toString()
    }

    // 对照 ConfigViewModel.clearWebViewData: 删 webview 目录 + toast + delay + restart
    override fun clearWebViewData() {
        Coroutine.async {
            FileUtils.delete(activity.getDir("webview", Context.MODE_PRIVATE))
            FileUtils.delete(activity.getDir("hws_webview", Context.MODE_PRIVATE), true)
            activity.toastOnUi(androidAppString("clear_webview_data_success"))
            delay(3000)
            App.instance.restart()
        }.onError {
            AppLog.put("清理 WebView 数据失败\n${it.localizedMessage}", it)
        }
    }

    // ===== 导入书籍私有辅助: 复刻 ImportBookActivity 同名方法 =====

    /**
     * 对照 BaseImportBookActivity.setBookStorage: 未设置"书籍保存位置"时弹存储限制说明并等
     * 用户选定; 选定即同时作为导入目录 (对照 onActivityCreated 的
     * `importBookPath = defaultBookTreeUri`)。用户取消时保持未设置, 由调用方按空目录处理。
     *
     * 只在进入页面时问一次 (对照原版该流程只在 onActivityCreated 执行); 用户此后主动选目录
     * 直接读所选目录, 不再经过本流程。
     */
    private suspend fun ensureImportStorage() {
        if (!AppConfig.defaultBookTreeUri.isNullOrBlank()) return
        val selected = CompletableDeferred<Boolean>()
        activity.alert(
            androidAppString("select_book_folder"),
            androidAppString("book_storage_help"),
        ) {
            okButton {
                // 同步占位: 弹层关闭即返回, 先占住避免调用方再拉一个选择器
                importFolderPickerPending = true
                activity.lifecycleScope.launch {
                    var ok = false
                    try {
                        ok = ensureBookSaveDirSelected()
                        if (ok && !AppConfig.defaultBookTreeUri.isNullOrBlank()) {
                            AppConfig.importBookPath = AppConfig.defaultBookTreeUri
                        }
                    } finally {
                        // 无论选定/取消/协程被取消都收尾: 保证等在这里的初始化不会永久挂起。
                        // 在途期间攒下的接续动作一并丢弃 —— 本流程收尾后调用方不会再发该请求,
                        // 留着只会在下一次选择器返回时陈旧执行
                        importFolderPickerPending = false
                        pendingImportFolderAction = null
                        selected.complete(ok)
                    }
                }
            }
            cancelButton { selected.complete(false) }
            onCancelled { selected.complete(false) }
        }
        selected.await()
    }

    private fun startRead(fileDoc: FileDoc) {
        // 原版 ImportBookActivity.startRead 在主线程直查 Room (allowMainThreadQueries);
        // 迁移后 DAO 是挂起函数, 用 runBlocking 会真把主线程卡在 Room 执行器上,
        // 改为协程内挂起查询, 后续 toast/selector/push 仍按原顺序在主线程执行
        activity.lifecycleScope.launch {
            if (ArchiveUtils.isArchive(fileDoc.name)) {
                val fileNames = withContext(IO) {
                    runCatching {
                        ArchiveUtils.getArchiveFilesName(fileDoc) {
                            AppPattern.bookFileRegex.matches(it)
                        }
                    }.getOrDefault(emptyList())
                }
                when {
                    fileNames.isEmpty() -> activity.toastOnUi(androidAppString("unsupport_archivefile_entry"))
                    fileNames.size == 1 -> openArchiveBook(fileDoc, fileNames.first())
                    else -> activity.selector(
                        androidAppString("start_read"),
                        fileNames
                    ) { _, name, _ ->
                        openArchiveBook(fileDoc, name)
                    }
                }
                return@launch
            }
            val book = withContext(IO) { appDb.bookDao.getBookByFileName(fileDoc.name) }
                ?: return@launch
            val filePath = fileDoc.toString()
            if (book.bookUrl != filePath) {
                book.bookUrl = filePath
                withContext(IO) { appDb.bookDao.insert(book) }
            }
            AppNavigatorProviders.get().push(book.toReadRoute())
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
        // 同 startRead: 查库走协程挂起, 不用 runBlocking 卡主线程
        activity.lifecycleScope.launch {
            val cached = withContext(IO) { appDb.bookDao.getBookByFileName(fileName) }
            if (cached != null) {
                AppNavigatorProviders.get().push(cached.toReadRoute())
                return@launch
            }
            activity.alert(androidAppString("draw"), androidAppString("no_book_found_bookshelf")) {
                okButton {
                    activity.lifecycleScope.launch(IO) {
                        suspend fun importFromArchive() = FileBook.importFromArchive(
                            fileDoc.uri, fileName,
                        ) { it.contains(fileName) }
                        // 压缩包解压产物落"书籍保存位置": 未设置时经既有选择器选定后继续导入
                        val book = runCatching { importFromArchive() }
                            .recoverCatching { e ->
                                if (e is InvalidBooksDirException && ensureBookSaveDirSelected()) {
                                    importFromArchive()
                                } else {
                                    throw e
                                }
                            }
                            .getOrNull()?.firstOrNull()
                        activity.runOnUiThread {
                            book?.let { AppNavigatorProviders.get().push(it.toReadRoute()) }
                        }
                    }
                }
                noButton()
            }
        }
    }

    // ===== 关于页私有辅助: 复刻原版 AboutFragment 同名 private 方法 =====

    private fun saveLogInternal() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                App.instance.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                App.instance.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(backupPath.toUri(), true)
            copyLogs(doc)
            copyHeapDump(doc)
            App.instance.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDumpInternal() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                App.instance.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                App.instance.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            App.instance.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(backupPath.toUri(), true)
            if (!copyHeapDump(doc)) {
                App.instance.toastOnUi("未找到堆转储文件")
            } else {
                App.instance.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 把本地文件整份写进 [dirDoc] 下的 [fileName]: content:// 目录走 SAF 临时文档 + 改名就位
     * ([copyToDocumentAtomically]), file:// 目录走同目录唯一临时件 + 改名 ([copyToFileAtomically])。
     * 备份目录是用户可见目录, 没有孤儿临时件的回收方, 两条通道都在各自的每条退出路径上清掉中间件。
     * 写不成时抛异常 (调用方的 onError 如实报失败)。SAF 目录里目标名上已有同名旧文档时由
     * [copyToDocumentAtomically] 先把它挪成契约过渡件, 就位成功后删过渡件, 失败则改回目标名;
     * 就位成立时目标名上是完整的旧件或新件, 提供方不支持同目录就位时直写回落, 目标名上可能出现半截。
     */
    private fun writeIntoDir(dirDoc: FileDoc, fileName: String, input: InputStream) {
        val dir = dirDoc.asFile()
        if (dir != null) {
            copyToFileAtomically(File(dir, fileName).path, input)
            return
        }
        check(copyToDocumentAtomically(App.instance, dirDoc.uri, fileName, input)) {
            "写入备份目录失败: $fileName"
        }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = App.instance.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")

        dumpLogcat(logcatFile)

        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)

        zipFile.inputStream().use { input ->
            writeIntoDir(doc, "logs.zip", input)
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(App.instance.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            writeIntoDir(heapDumpDoc, heapFile.name, input)
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
