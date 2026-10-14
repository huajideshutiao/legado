package io.legado.app.ui.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.Server
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupViewModelShared
import io.legado.app.ui.book.import.remote.ServerConfigDialog
import io.legado.app.ui.book.import.remote.ServersDialog
import io.legado.app.ui.book.manage.SourcePickerDialog
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.read.CharsetDialog
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.DownloadDialog
import io.legado.app.ui.book.read.EffectiveReplacesDialog
import io.legado.app.ui.book.read.ImageStyleDialog
import io.legado.app.ui.book.read.SimulatedReadingDialog
import io.legado.app.ui.book.read.UnderlineBubbleState
import io.legado.app.ui.book.read.UnderlineNoteBubbleHost
import io.legado.app.ui.book.read.config.AutoReadActions
import io.legado.app.ui.book.read.config.AutoReadController
import io.legado.app.ui.book.read.config.AutoReadPanelDialogHost
import io.legado.app.ui.book.read.config.ClickActionDialog
import io.legado.app.ui.book.read.config.FontSelectDialog
import io.legado.app.ui.book.read.config.PageKeyDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.ui.book.read.page.readClickActionConfig
import io.legado.app.ui.book.search.SearchScopeDialog
import io.legado.app.ui.book.source.VerificationCodeDialog
import io.legado.app.ui.book.source.manage.BookSourceGroupManageDialog
import io.legado.app.ui.dict.DictDialogHost
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.route.ChangeChapterSourceDialogHost
import io.legado.app.ui.route.ChangeSourceDialogHost
import io.legado.app.ui.route.ReadLayoutConfigDialogHost
import io.legado.app.ui.route.TocDialogHost
import io.legado.app.ui.main.home.HomeSectionEditDialog
import io.legado.app.ui.main.home.HomeSectionManageDialog
import io.legado.app.ui.main.home.HomeTabEditDialog
import io.legado.app.ui.main.home.HomeTabManageDialog
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.custom_page_key
import legado.ui.generated.resources.effective_replaces
import legado.ui.generated.resources.group_manage
import legado.ui.generated.resources.home_add_section
import legado.ui.generated.resources.home_manage_for_tab
import legado.ui.generated.resources.home_tab_add
import legado.ui.generated.resources.home_tab_manage
import legado.ui.generated.resources.lookup_word
import legado.ui.generated.resources.manga_color_filter
import legado.ui.generated.resources.manga_footer_config
import legado.ui.generated.resources.search_scope
import legado.ui.generated.resources.select_book_source
import legado.ui.generated.resources.select_font
import legado.ui.generated.resources.server_config
import legado.ui.generated.resources.speak_engine
import legado.ui.generated.resources.verification_code

/**
 * 对话框形制预览清单 (居中/全屏对话框, 自带顶栏)。
 *
 * 依赖阅读会话的弹窗 (批注气泡/查词) 传最小可用状态, 不依赖真实阅读页;
 * 依赖库内数据的弹窗传库中首条真实记录, 取不到时显示"无数据"提示而不伪造数据。
 *
 * 同一条弹窗只列一条: 产品里既可从覆盖物键打开、又由调用方直接组合的, 由覆盖物清单那条承载。
 */
internal suspend fun buildDialogEntries(
    navigator: AppNavigator,
): List<DialogPreviewEntry.Inline> {
    val book = previewRead("书籍样本") { AppDbProviders.get().bookDao.all().firstOrNull() }
    val source = previewRead("书源样本") { AppDbProviders.get().bookSourceDao.all().firstOrNull() }
    val groups = previewRead("分组样本") { AppDbProviders.get().bookGroupDao.all() }.orEmpty()

    return listOf(
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_group_manage", titleOf(Res.string.group_manage), "GroupManageDialog") { onDismiss ->
            GroupManagePreview(groups, onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_group_edit", nameText("分组编辑"), "GroupEditDialog") { onDismiss ->
            GroupEditDialog(
                group = groups.firstOrNull(),
                onConfirm = { onDismiss() },
                onDismiss = onDismiss,
                onDelete = { onDismiss() },
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_source_picker", titleOf(Res.string.select_book_source), "SourcePickerDialog") { onDismiss ->
            SourcePickerPreview(onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_page_key", titleOf(Res.string.custom_page_key), "PageKeyDialog") { onDismiss ->
            PageKeyDialog(
                keyMappings = emptyMap(),
                onConfirm = { onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_source_group_manage", titleOf(Res.string.group_manage), "BookSourceGroupManageDialog") { onDismiss ->
            BookSourceGroupManageDialog(onDismiss = onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_content_edit", nameText("正文编辑"), "ContentEditDialog") { onDismiss ->
            ContentEditDialog(
                chapterName = "预览章节",
                content = "正文内容预览。\n第二段。",
                onSubmit = { onDismiss() },
                onDismiss = onDismiss,
                onReset = {},
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_effective_replaces", titleOf(Res.string.effective_replaces), "EffectiveReplacesDialog") { onDismiss ->
            EffectiveReplacesDialog(
                book = book ?: Book(),
                items = emptyList(),
                onAddRule = {},
                onItemClick = {},
                onManageAll = {},
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_font_select", titleOf(Res.string.select_font), "FontSelectDialog") { onDismiss ->
            FontSelectDialog(
                fontItems = emptyList(),
                curFontPath = "",
                curFontName = "",
                onSelectFont = { onDismiss() },
                onSelectDefault = { onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_click_action", nameText("点击区域动作配置"), "ClickActionDialog") { onDismiss ->
            ClickActionDialog(
                clickActionConfig = readClickActionConfig(),
                onConfirm = { onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_read_aloud", nameText("朗读控制"), "ReadAloudDialog") { onDismiss ->
            ReadAloudDialog(
                isPlaying = false,
                onPlayPause = {},
                onStop = {},
                onPrev = {},
                onNext = {},
                onPrevParagraph = {},
                onNextParagraph = {},
                onSetTimer = {},
                onAdjustSpeed = {},
                onFollowSysChange = {},
                onOpenChapterList = {},
                onShowMenuBar = {},
                onBackstage = {},
                onOpenSettings = {},
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_speak_engine", titleOf(Res.string.speak_engine), "SpeakEngineDialog") { onDismiss ->
            SpeakEnginePreview(onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_simulated_reading", nameText("模拟追读"), "SimulatedReadingDialog") { onDismiss ->
            SimulatedReadingDialog(
                book = book ?: Book(),
                onApply = { onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_image_style", nameText("图片样式"), "ImageStyleDialog") { onDismiss ->
            ImageStyleDialog(
                book = book ?: Book(),
                onApply = { onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_download_range", nameText("离线缓存"), "DownloadDialog") { onDismiss ->
            DownloadDialog(
                book = book ?: Book(),
                onApply = { _, _ -> onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_charset", nameText("设置编码"), "CharsetDialog") { onDismiss ->
            CharsetDialog(
                book = book ?: Book(),
                onApply = { onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_manga_color_filter", titleOf(Res.string.manga_color_filter), "MangaColorFilterDialog") { onDismiss ->
            MangaColorFilterDialog(
                config = MangaColorFilterConfig(),
                grayEnabled = false,
                onColorFilterChange = {},
                onGrayChange = {},
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_manga_footer", titleOf(Res.string.manga_footer_config), "MangaFooterSettingDialog") { onDismiss ->
            MangaFooterSettingDialog(
                config = MangaFooterConfig(),
                onConfigChange = {},
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_search_scope", titleOf(Res.string.search_scope), "SearchScopeDialog") { onDismiss ->
            SearchScopeDialog(
                onConfirm = { onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_servers", titleOf(Res.string.server_config), "ServersDialog") { onDismiss ->
            ServersDialog(
                servers = emptyList(),
                initialServerId = -1L,
                onAddServer = {},
                onEditServer = {},
                onDeleteServer = {},
                onSelectDefault = {},
                onConfirm = { onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_server_config", nameText("WebDav 服务器配置"), "ServerConfigDialog") { onDismiss ->
            ServerConfigDialog(
                server = Server(),
                onSave = { onDismiss() },
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_home_section_edit", titleOf(Res.string.home_add_section), "HomeSectionEditDialog") { onDismiss ->
            HomeSectionEditDialog(tabTitle = "预览分组", onDismiss = onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_home_section_manage", titleOf(Res.string.home_manage_for_tab, "预览分组"), "HomeSectionManageDialog") { onDismiss ->
            HomeSectionManageDialog(tabTitle = "预览分组", onDismiss = onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_home_tab_edit", titleOf(Res.string.home_tab_add), "HomeTabEditDialog") { onDismiss ->
            HomeTabEditDialog(onDismiss = onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_home_tab_manage", titleOf(Res.string.home_tab_manage), "HomeTabManageDialog") { onDismiss ->
            HomeTabManageDialog(onDismiss = onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_dict", titleOf(Res.string.lookup_word), "DictDialogHost") { onDismiss ->
            DictDialogHost(word = "预览", onDismiss = onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_underline_note", nameText("批注气泡"), "UnderlineNoteBubbleHost") { onDismiss ->
            UnderlineNoteBubbleHost(
                state = UnderlineBubbleState(
                    bookmark = Bookmark(bookText = "预览正文", content = "预览批注"),
                    anchor = Rect(80f, 120f, 200f, 180f),
                ),
                onDismiss = onDismiss,
                onNoteConfirm = {},
                onColorChange = {},
                onLineStyleChange = {},
                onDelete = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_verification_code", titleOf(Res.string.verification_code), "VerificationCodeDialog") { onDismiss ->
            if (source != null) {
                VerificationCodeDialog(
                    url = "",
                    source = source,
                    onConfirm = { onDismiss() },
                    onDismiss = onDismiss,
                )
            } else {
                NoDataHint(onDismiss, "库里没有书源, 该弹窗依赖真实书源数据")
            }
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.DIALOG, "dialog_read_layout_config", nameText("界面设置 (阅读布局)"), "ReadLayoutConfigDialogHost") { onDismiss ->
            ReadLayoutConfigDialogHost(onDismiss = onDismiss)
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.SHEET, "sheet_toc", nameText("目录"), "TocDialogHost") { onDismiss ->
            val tocBook = book
            if (tocBook == null) {
                NoDataHint(onDismiss, "库里没有书, 目录依赖真实书籍")
            } else {
                TocDialogHost(
                    book = tocBook,
                    navigator = navigator,
                    onOpenChapter = { _, _ -> onDismiss() },
                    onDismiss = onDismiss,
                )
            }
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.SHEET, "sheet_auto_read", nameText("自动翻页面板"), "AutoReadPanelDialogHost") { onDismiss ->
            // 速度滑条读写真实配置 (与阅读页同一份), 其余动作按钮在预览里不接后端
            AutoReadPanelDialogHost(
                controller = PreviewAutoReadController,
                actions = PreviewAutoReadActions,
                onDismiss = onDismiss,
            )
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.SHEET, "sheet_change_source", nameText("换源"), "ChangeSourceDialogHost") { onDismiss ->
            val changeSourceBook = book
            if (changeSourceBook == null) {
                NoDataHint(onDismiss, "库里没有书, 换源依赖真实书籍")
            } else {
                ChangeSourceDialogHost(
                    book = changeSourceBook,
                    onSourceChanged = { _, _, _ -> onDismiss() },
                    onEditSource = {},
                    onBookSourceManage = {},
                    onDismiss = onDismiss,
                )
            }
        },
        DialogPreviewEntry.Inline(DialogPreviewGroup.SHEET, "sheet_change_chapter_source", nameText("本章换源"), "ChangeChapterSourceDialogHost") { onDismiss ->
            val changeChapterBook = book
            if (changeChapterBook == null) {
                NoDataHint(onDismiss, "库里没有书, 本章换源依赖真实书籍")
            } else {
                ChangeChapterSourceDialogHost(
                    book = changeChapterBook,
                    chapterIndex = 0,
                    chapterTitle = changeChapterBook.durChapterTitle.orEmpty(),
                    onChapterChanged = { onDismiss() },
                    onSourceChanged = { _, _, _ -> onDismiss() },
                    onEditSource = {},
                    onBookSourceManage = {},
                    onDismiss = onDismiss,
                )
            }
        },
    )
}

/** 自动翻页控制器的预览实现: 速度读写真实配置, 与阅读页同一份。 */
private object PreviewAutoReadController : AutoReadController {
    override var autoReadSpeed: Int
        get() = ReadBookConfigProviders.get().autoReadSpeed
        set(value) {
            ReadBookConfigProviders.get().autoReadSpeed = value
        }
}

/** 自动翻页面板的动作槽: 预览里只展示形制, 按钮不接阅读页后端。 */
private object PreviewAutoReadActions : AutoReadActions {
    override fun openChapterList() = Unit

    override fun showMenuBar() = Unit

    override fun autoPageStop() = Unit

    override fun showReadStyle() = Unit

    override fun upTtsSpeechRate() = Unit
}

@Composable
private fun GroupManagePreview(
    groups: List<io.legado.app.data.entities.BookGroup>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { GroupViewModelShared(scope) }
    GroupManageDialog(
        groups = groups,
        onAddGroup = {},
        onEditGroup = {},
        onUpdateGroup = { viewModel.upGroup(it) },
        onPersistOrder = { ordered -> viewModel.upGroup(*ordered.toTypedArray()) },
        onDismiss = onDismiss,
        canAddGroup = { true },
    )
}

@Composable
private fun SourcePickerPreview(onDismiss: () -> Unit) {
    var sources by remember { mutableStateOf<List<BookSource>>(emptyList()) }
    LaunchedEffect(Unit) {
        sources = previewRead("启用书源样本") { AppDbProviders.get().bookSourceDao.enabled() }
            .orEmpty()
    }
    SourcePickerDialog(
        sources = sources,
        initialDelay = AppConfigProviders.get().batchChangeSourceDelay,
        onSourceSelected = { onDismiss() },
        onDelayChange = { PreferenceProviders.get().putInt(PreferKey.batchChangeSourceDelay, it) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun SpeakEnginePreview(onDismiss: () -> Unit) {
    var engines by remember { mutableStateOf<List<HttpTTS>>(emptyList()) }
    LaunchedEffect(Unit) {
        engines = previewRead("朗读引擎样本") { AppDbProviders.get().httpTTSDao.all() }.orEmpty()
    }
    SpeakEngineDialog(
        engines = engines,
        selectedEngineUrl = null,
        onSelectEngine = {},
        onEditEngines = {},
        onDeleteEngine = {},
        onLogin = {},
        onTestSpeak = {},
        onImportLocal = {},
        onImportOnline = {},
        onImportDefault = {},
        onExport = {},
        onDismiss = onDismiss,
    )
}

/** 数据缺失时的提示 (不伪造数据, 如实说明缺什么)。 */
@Composable
private fun NoDataHint(onDismiss: () -> Unit, message: String) {
    io.legado.app.ui.compose.component.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = "无数据",
        message = message,
        okButton = io.legado.app.ui.compose.component.AlertButton("知道了") { onDismiss() },
    )
}
