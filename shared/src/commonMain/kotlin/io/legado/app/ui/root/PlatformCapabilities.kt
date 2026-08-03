package io.legado.app.ui.root

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.Review
import io.legado.app.help.DirectLinkUploadRule
import io.legado.app.help.toast.Toasters
import io.legado.app.model.fileBook.FileBook
import io.legado.app.ui.book.import.ImportFileItem
import io.legado.app.ui.book.read.config.FontItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** AppRoot 只依赖能力面；实现由唯一系统入口注册。 */
interface PlatformCapabilities {
    fun unsupported(capability: String) {
        Toasters.get().toast("当前平台暂不支持：$capability")
    }

    fun exitApplication()
    fun openExternalUrl(url: String)
    fun shareText(text: String)

    // 书籍路由解析: shared 无 DB 能力, 按 bookUrl 解析为 BookRef 供 LaunchRequest 路由导航
    suspend fun resolveBookRef(bookUrl: String): BookRef? = null

    // 主题: 切换日夜模式 (对照 app 端 ThemeConfig.applyDayNight)
    fun applyDayNight() = unsupported("applyDayNight")

    // 剪贴板: 复制文本 (对照 app 端 sendToClip)
    fun copyToClipboard(text: String) = unsupported("copyToClipboard")

    fun getClipboardText(): String? = null

    fun testDirectLinkUpload(
        rule: DirectLinkUploadRule,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) = unsupported("测试直链上传")

    // 处理尚未下沉的 deep link 类型 (平台可保留原生兼容链)
    fun handleDeepLinkImport(type: String, src: String): Boolean = false

    // Web 服务: 获取当前运行地址 (对照 app 端 WebService.hostAddress)
    fun getWebServiceUrl(): String? = null

    // Web 服务: 当前是否运行 (对照 app 端 WebService.isRun), 供 MyConfigRoute 初始化开关态
    fun isWebServiceRunning(): Boolean = false

    // Web 服务: 启停服务 (对照 app 端 WebService.start / WebService.stop), 由 MyConfigRoute 开关回调调用
    fun setWebService(enabled: Boolean) = unsupported("setWebService")

    // Web 服务: 运行状态变化流 (对照 app 端 FlowBus.withSticky(EventBus.WEB_SERVICE)),
    // 平台可选实现; null 时 MyConfigRoute 回退本地 mutableStateOf
    val webServiceState: StateFlow<Boolean>? get() = null

    // 换封面源弹窗 (对照 app 端 ChangeCoverDialog)
    fun showChangeCoverDialog(book: Book, onCoverSelected: (String) -> Unit) =
        unsupported("更换封面源")

    // 评论列表对话框 (对照 app 端 ReviewListDialog BottomSheetDialogFragment)
    // 返回 true 表示平台已弹出对话框; false 时调用方回退共享列表页
    fun showReviewListDialog(
        book: Book,
        chapter: BookChapter?,
        paragraphIndex: Int,
        parentReview: Review? = null,
    ): Boolean = false

    // 图片预览对话框 (对照 app 端 PhotoDialog, 阅读页点图预览), 未实现端提示不支持
    fun showImagePreview(url: String) = unsupported("图片预览")

    // 默认封面画廊弹窗 (对照 app 端 DefaultCoverGalleryDialog)
    fun showDefaultCoverGallery(isNight: Boolean) = unsupported("选择默认封面")

    // 刷新默认封面缓存 (对照 app 端 BookCover.upDefaultCover)
    fun refreshDefaultCover() = unsupported("刷新默认封面")

    // 跳转系统 TTS 设置页 (app 端 IntentHelp.openTTSSetting)
    fun openTtsSettings() = unsupported("系统 TTS 设置")

    /** 弹出 HttpTTS 引擎新增/编辑对话框 (对照 app 端 HttpTtsEditDialog, engine=null 新增) */
    fun showHttpTtsEditDialog(engine: HttpTTS?) = unsupported("编辑 TTS 引擎")

    // 获取系统字体缩放值 (对照 app 端 AppContextWrapper.getFontScale), 默认 null 供各端按需覆写
    fun getFontScale(): String? = null

    // 触摸滑动阈值 (对照 app 端 ViewConfiguration.get(ctx).scaledTouchSlop), 默认 0
    fun getScaledTouchSlop(): Int = 0

    // 导入本地书籍平台能力 (各端按需 override, 未实现端统一给出明确提示)
    // 对照 app 端 ImportBookActivity 同名方法
    /** 选择导入根目录 (对照 onPickFolder / selectFolder.launch) */
    fun pickImportFolder() = unsupported("选择导入目录")

    /** 扫描当前文件夹及子文件夹 (对照 onScanFolder / scanFolder) */
    fun scanImportFolder() = unsupported("扫描导入目录")

    /** 弹出文件名导入 js 编辑框 (对照 onAlertImportFileName / alertImportFileName) */
    fun alertImportFileName() = unsupported("按文件名导入")

    /** 将选中条目上架 (对照 onAddSelectionToBookshelf / addSelectionToBookshelf), 完成后回调 onComplete */
    fun addImportSelectionToBookshelf(items: List<ImportFileItem>, onComplete: () -> Unit = {}) {
        unsupported("导入所选书籍")
    }

    /** 更新搜索过滤关键字 (对照 onSearchTextChange / viewModel.updateCallBackFlow) */
    fun updateImportBookFilter(key: String) = unsupported("筛选导入书籍")

    /** 更新排序方式 (对照 upSort / viewModel.sort + AppConfig.localBookImportSort) */
    fun updateImportBookSort(sort: Int) = unsupported("切换导入排序")

    /** 打开已上架书籍阅读页 (对照 startRead / startReadBook) */
    fun openImportedBookReader(item: ImportFileItem) = unsupported("打开导入书籍")

    /** 进入子目录 (对照 onItemClick isDir 分支 / nextDoc) */
    fun navigateImportDir(item: ImportFileItem) = unsupported("进入导入目录")

    /** 返回上级目录 (对照 onItemClick isUpDir 分支 / goBackDir) */
    fun goBackImportDir() = unsupported("返回上级导入目录")

    // 关于页平台能力 (各端按需 override, 未实现端统一给出明确提示)
    // 对照 app 端 AboutActivity 同名方法
    /** 检查更新 (对照 onCheckUpdate / AppUpdate.check) */
    fun checkUpdate() = unsupported("检查更新")

    /** 显示崩溃日志 (对照 onShowCrashLogs / showDialogFragment<CrashLogsDialog>) */
    fun showCrashLogs() = unsupported("查看崩溃日志")

    /** 保存日志到备份目录 (对照 onSaveLog / saveLog) */
    fun saveLog() = unsupported("保存日志")

    /** 创建堆转储 (对照 onCreateHeapDump / createHeapDump) */
    fun createHeapDump() = unsupported("创建堆转储")

    /** 显示 MD 文件 (对照 onShowMdFile / showMdFile) */
    fun showMdFile(title: String, fileName: String) = unsupported("查看说明文档")

    // 书籍详情页平台能力 (各端按需 override, 未实现端统一给出明确提示)
    // 对照 app 端 BookInfoActivity 同名方法
    /** 上传书籍到远程 (对照 onUploadBook) */
    fun uploadBook(book: Book, success: (() -> Unit)? = null) = unsupported("上传书籍")

    /** 下载到本地 (对照 onDownloadToLocal) */
    fun downloadBookToLocal(book: Book, success: (() -> Unit)? = null) =
        unsupported("下载书籍到本地")

    /** 源变量弹窗 (对照 onSetSourceVariable) */
    fun showSourceVariableDialog(book: Book) = unsupported("编辑书源变量")

    /** 书籍变量弹窗 (对照 onSetBookVariable) */
    fun showBookVariableDialog(book: Book) = unsupported("编辑书籍变量")

    /** 清缓存 (对照 onClearCache) */
    fun clearBookCache(book: Book) = unsupported("清除书籍缓存")

    /** 书架操作: 上架/下架 (对照 onShelfClick, onComplete: null=删除, true=已上架, false=取消) */
    fun toggleBookshelf(
        book: Book,
        inBookshelf: Boolean,
        onComplete: (Boolean?) -> Unit,
        onWaitDialog: (Boolean) -> Unit = {},
        onAction: (String) -> Unit = {},
    ) {
        unsupported("书架操作")
    }

    // webFile 下载导入相关 (对照 BookInfoViewModel.importWebFile 等方法)
    /** 导入 webFile 到本地书籍 (对照 BookInfoViewModel.importWebFile) */
    fun importWebFile(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit = {},
        onAction: (String) -> Unit = {},
        success: ((Book) -> Unit)? = null,
    ) = unsupported("导入 Web 文件")

    /** 下载 webFile 返回文件路径 (对照 BookInfoViewModel.downloadWebFile) */
    fun downloadWebFile(
        book: Book,
        webFile: FileBook.WebFile,
        onWaitDialog: (Boolean) -> Unit = {},
        onAction: (String) -> Unit = {},
        success: ((String) -> Unit)? = null,
    ) = unsupported("下载 Web 文件")

    /** 获取压缩包内文件名列表 (对照 BookInfoViewModel.getArchiveFilesName) */
    fun getArchiveFilesName(archiveFilePath: String, onSuccess: (List<String>) -> Unit) =
        unsupported("获取压缩包文件名")

    /** 从压缩包导入书籍 (对照 BookInfoViewModel.importBookFromArchive) */
    fun importBookFromArchive(
        archiveFilePath: String,
        archiveEntryName: String,
        book: Book,
        onWaitDialog: (Boolean) -> Unit = {},
        success: ((Book) -> Unit)? = null,
    ) = unsupported("从压缩包导入书籍")

    /** 异步刷新 WebDav 书籍 (对照 BookInfoViewModel.refreshWebDavBook) */
    fun refreshWebDavBook(book: Book, success: (() -> Unit)? = null) =
        unsupported("刷新 WebDav 书籍")

    /** 本地书合并并加载章节 (对照 BookInfoViewModel.changeToLocalBook) */
    fun changeToLocalBook(book: Book): Book {
        unsupported("本地书合并")
        return book
    }

    /** webFile 下载导入后阅读 (对照 onReadClick isWebFile 分支) */
    fun handleWebFileRead(
        book: Book,
        onWaitDialog: (Boolean) -> Unit = {},
        onAction: (String) -> Unit = {},
        onSuccess: ((Book) -> Unit)? = null,
    ) = unsupported("下载并阅读 Web 文件")

    /** 简介动作 JS 派发 (对照 onDispatchIntroAction / source.evalJS) */
    fun evalIntroAction(book: Book, js: String) = unsupported("执行简介动作")

    /**
     * 本地书文件字节数 (对照 upWordCount 内 FileDoc.fromFile(book.bookUrl).size)。
     * 返回 0 表示不可得, 调用方按原版逻辑跳过大小展示。
     */
    suspend fun localBookFileSize(bookUrl: String): Long = 0L

    // 书籍详情页三态相关平台能力 (各端按需 override, 默认值避免破坏未实现端)
    // 对照 BookInfoActivity.Content 内 isLandscape / setLightStatusBar 计算
    /** 当前是否横屏 (对照 LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE) */
    fun isLandscape(): Boolean = false

    /** 设置状态栏图标色 (对照 setLightStatusBar(if (useDevFeat) isDarkTheme else false)) */
    fun setLightStatusBarForBookInfo(useDevFeat: Boolean, isDarkTheme: Boolean) {}

    // 设置项按平台过滤: 无系统栏/无屏幕方向的端 (桌面) 隐藏对应开关, 拨了也没效果
    /** 是否有系统状态栏/导航栏 (决定 MoreConfig 的隐藏状态栏/导航栏开关显隐) */
    fun hasSystemBars(): Boolean = true

    /** 是否支持锁定屏幕方向 (决定 MoreConfig 的屏幕方向选项显隐) */
    fun hasScreenOrientation(): Boolean = true

    /** 获取 app 版本名 (对照 app 端 AppConst.appInfo.versionName), 供 AboutRoute 初始化 state */
    fun getAppVersionName(): String? = null

    // 书架管理平台能力 (各端按需 override, 未实现端统一给出明确提示)
    // 对照 app 端 BookshelfManageActivity 同名方法/状态
    /** 导出全部选中书籍为书源 JSON (对照 exportAllUseBookSource) */
    fun exportAllUseBookSource() = unsupported("导出所用书源")

    /** 导出全部选中书籍为文件 (对照 exportAll) */
    fun exportAllBooks(books: List<Book>) = unsupported("导出书籍")

    /** 导出书架 JSON (对照 exportBookshelf) */
    fun exportBookshelf(books: List<Book>) = unsupported("导出书架")

    /** 切换"导出替换"开关 (对照 toggleEnableReplace) */
    fun toggleExportUseReplace() = unsupported("切换导出替换")

    /** 切换"自定义导出"开关 (对照 toggleCustomExport) */
    fun toggleCustomExport() = unsupported("切换自定义导出")

    /** 切换"导出到 WebDav"开关 (对照 toggleExportWebDav) */
    fun toggleExportWebDav() = unsupported("切换 WebDav 导出")

    /** 选择导出文件夹 (对照 selectExportFolderMenu / selectExportFolder) */
    fun selectExportFolder(books: List<Book>) = unsupported("选择导出目录")

    /** 弹出导出配置对话框 (对照 showExportConfig) */
    fun showExportConfig() = unsupported("导出配置")

    /** "导出替换"开关当前值 (对照 AppConfig.exportUseReplace) */
    fun exportUseReplace(): Boolean = false

    /** "自定义导出"开关当前值 (对照 AppConfig.enableCustomExport) */
    fun enableCustomExport(): Boolean = false

    /** "导出到 WebDav"开关当前值 (对照 AppConfig.exportToWebDav) */
    fun exportToWebDav(): Boolean = false

    /** 读取"删除源文件"偏好 (对照 LocalConfig.deleteBookOriginal) */
    fun getDeleteBookOriginal(): Boolean = false

    /** 持久化"删除源文件"偏好 (对照 LocalConfig.deleteBookOriginal = value) */
    fun setDeleteBookOriginal(value: Boolean) = unsupported("保存删除源文件设置")

    /** 缓存进度文案 (对照 cacheInfo, null=隐藏) */
    fun cacheInfo(book: Book): String? = null

    /** 已缓存章节数 (对照 viewModel.cacheChapters[bookUrl]?.size, null=未加载) */
    fun cacheChapterCount(book: Book): Int? = null

    // 导入本地书籍平台状态 (各端按需 override, 默认空状态避免破坏未实现端)
    // 对照 app 端 ImportBookActivity 同名状态字段
    /** 导入文件列表 (对照 items) */
    fun importBookItems(): StateFlow<List<ImportFileItem>> =
        PlatformCapabilitiesDefaults.emptyItemsFlow

    /** 当前路径 (对照 path) */
    fun importBookPath(): StateFlow<String?> = PlatformCapabilitiesDefaults.nullStringFlow

    /** 加载中 (对照 loading) */
    fun importBookLoading(): StateFlow<Boolean> = PlatformCapabilitiesDefaults.falseFlow

    /** 空消息可见 (对照 emptyMsgVisible) */
    fun importBookEmptyMsgVisible(): StateFlow<Boolean> = PlatformCapabilitiesDefaults.falseFlow

    /** 初始化导入数据 (对照 onActivityCreated 内 initData) */
    fun initImportBookData() = unsupported("初始化本地书籍导入")

    /** 处理文件关联直接传入的文件路径或 URI；未实现的平台必须明确报告不支持。 */
    fun openImportFile(filePath: String) {
        error("Importing associated files is not supported on this platform: $filePath")
    }

    // 阅读样式平台能力 (各端按需 override, 未实现端返回空列表)
    // 对照 app 端 FontSelectDialog.loadFontFiles: 字体目录 + 本地字体合并去重排序
    /** 扫描字体文件列表 (对照 app 端 FontSelectDialog, 未实现端空列表) */
    suspend fun scanFontItems(): List<FontItem> = emptyList()

    // 书源管理平台能力 (各端按需 override, 未实现端统一给出明确提示)
    // 对照 app 端 BookSourceActivity 同名方法
    /** 新建书源 (对照 addBookSource / startActivity<BookSourceEditActivity>()) */
    fun addBookSource() = unsupported("新建书源")

    /** 显示书源分组管理对话框 (对照 showGroupManage / showDialogFragment<GroupManageDialog>) */
    fun showBookSourceGroupManage() = unsupported("管理书源分组")

    /** 取消书源校验 (对照 cancelCheckSource / CheckSource.stop + Debug.finishChecking) */
    fun cancelCheckSource() = unsupported("取消书源校验")

    /** 批量加入分组 (对照 selectionAddToGroups, 含 alert 输入分组名) */
    fun selectionAddToGroups(selection: List<BookSourcePart>) = unsupported("书源加入分组")

    /** 批量移出分组 (对照 selectionRemoveFromGroups, 含 alert 输入分组名) */
    fun selectionRemoveFromGroups(selection: List<BookSourcePart>) = unsupported("书源移出分组")

    /** 导出选中书源到文件 (对照 exportSelection / saveToFile + exportDir.launch) */
    fun exportBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean
    ) = unsupported("导出书源")

    /** 分享选中书源 (对照 shareSelection / saveToFile + share) */
    fun shareBookSourceSelection(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean
    ) = unsupported("分享书源")

    /** 校验选中书源 (对照 checkSource, 含 alert 输入关键词 + CheckSource.start) */
    fun checkBookSource(selection: List<BookSourcePart>) = unsupported("校验书源")

    // 书源编辑平台能力 (各端按需 override, 未实现端统一给出明确提示)
    // 对照 app 端 BookSourceEditActivity 同名方法
    /** 书源登录 (对照 login, 先保存后弹 showLoginDialog) */
    fun showBookSourceLogin(source: BookSource) = unsupported("书源登录")

    /** 书源变量编辑 (对照 setSourceVariable, 先保存后弹 showSourceVariableDialog) */
    fun showBookSourceVariableDialog(source: BookSource) = unsupported("编辑书源变量")

    /** 危险 API 开关点击 (对照 onDangerousApiClick, 含 SharedJsScope.remove + alert 确认) */
    fun onEnableDangerousApiClick(
        isChecked: Boolean,
        bookSource: BookSource?,
        onRevert: () -> Unit
    ) {
        onRevert()
        unsupported("危险 API 设置")
    }

    // 主页管理平台能力 (各端按需 override, 未实现端统一给出明确提示)
    // 主题设置弹窗平台能力 (各端按需 override, 未实现端统一给出明确提示)
    // 对照 app 端 ThemeConfigHost 同名方法; onSearchLayout 已在 shared 用 Compose 重建, 不在此注入
    /** 显示书架布局配置对话框 (对照 ThemeConfigHost.configBookshelf) */
    fun showBookshelfLayoutDialog() = unsupported("书架布局配置")

    /** 显示底栏配置对话框 (对照 ThemeConfigHost.configBottomNav) */
    fun showBottomNavConfigDialog() = unsupported("底栏配置")

    /** 显示主题列表对话框 (对照 ThemeListDialog) */
    fun showThemeListDialog() = unsupported("主题列表")

    /**
     * 显示主题自定义编辑对话框 (对照 ThemeCustomizeDialog)。
     *
     * - configIndex != null: 编辑指定索引的自定义主题
     * - configIndex == null: 新建主题, isNight 指定日间/夜间
     */
    fun showThemeCustomizeDialog(configIndex: Int?, isNight: Boolean = false) =
        unsupported("自定义主题")

    /** 显示自定义日间主题对话框 (对照 ThemeCustomizeDialog.editPrefs(false)) */
    fun showCustomizeDayThemeDialog() = unsupported("自定义日间主题")

    /** 显示自定义夜间主题对话框 (对照 ThemeCustomizeDialog.editPrefs(true)) */
    fun showCustomizeNightThemeDialog() = unsupported("自定义夜间主题")

    // 其它设置平台能力 (各端按需 override, 未实现端统一给出明确提示)
    // 对照 app 端 OtherConfigHost 同名方法
    /** 写本地密码 (对照 app 端 LocalConfig.password = value, 写 "local" SharedPreferences) */
    fun setLocalPassword(password: String?) = unsupported("设置本地密码")

    /** SAF 选书籍目录 (对照 app 端 localBookTreeSelect.launch DIR_SYS), 选中后回调 onSelected(uri) */
    fun pickBookTreeUri(onSelected: (String?) -> Unit) = unsupported("选择书籍目录")

    /** 显示校验设置 Dialog (对照 app 端 showDialogFragment<CheckSourceConfig>), dismiss 后回调 onDismiss */
    fun showCheckSourceConfigDialog(onDismiss: () -> Unit = {}) = unsupported("书源校验设置")

    /** 显示直链上传规则 Dialog (对照 app 端 showDialogFragment<DirectLinkUploadConfig>) */
    fun showDirectLinkUploadConfigDialog() = unsupported("直链上传配置")

    /** 清理 WebView 数据 (对照 app 端 viewModel.clearWebViewData: 删 webview 目录 + toast + 重启) */
    fun clearWebViewData() = unsupported("清理 WebView 数据")
}

object PlatformCapabilityProviders {
    private var provider: PlatformCapabilities? = null

    fun register(value: PlatformCapabilities) {
        provider = value
    }

    fun get(): PlatformCapabilities = provider
        ?: error("PlatformCapabilities must be registered by the system entry")

    fun getOrNull(): PlatformCapabilities? = provider
}

// 平台能力默认 StateFlow 实例 (稳定引用, 避免每次调用返回新实例)
internal object PlatformCapabilitiesDefaults {
    val emptyItemsFlow: StateFlow<List<ImportFileItem>> = MutableStateFlow(emptyList())
    val nullStringFlow: StateFlow<String?> = MutableStateFlow(null)
    val falseFlow: StateFlow<Boolean> = MutableStateFlow(false)
}
