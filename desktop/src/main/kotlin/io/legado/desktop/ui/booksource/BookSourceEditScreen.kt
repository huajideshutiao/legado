package io.legado.desktop.ui.booksource

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.ui.compose.component.AppTextField
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.http.CookieStoreProviders
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.SharedJsScope
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.book.source.edit.BookSourceEditCallbacks

import io.legado.app.ui.book.source.edit.BookSourceEditState
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.browseUrl
import io.legado.app.utils.decodeStringMapOrNull
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * 桌面端书源编辑 Screen 入口 (包装 shared/sharedUiMain 的 [io.legado.app.ui.book.source.edit.BookSourceEditScreen])。
 *
 * # 职责
 *
 * 对照 desktop [BookSourceDebugScreen] / [BookSourceScreen] 模式, 仅做桌面平台适配,
 * 业务展示与表单逻辑全部下沉到 shared/sharedUiMain 的 [io.legado.app.ui.book.source.edit.BookSourceEditScreen]:
 *
 * - 注入 desktop 平台 Provider (ThemeStore / AppConfig / EventBus), 让 commonMain 的
 *   [AppTheme] / [io.legado.app.ui.book.source.edit.BookSourceEditScreen] 可跨平台运行
 * - 持有 [BookSourceEditState] (顶部表单状态) + 7 组 `ArrayList<EditEntity>` (各 tab 字段)
 * - [LaunchedEffect] 异步查 [AppDbProviders.get].bookSourceDao.getBookSource(sourceUrl)
 *   加载书源, 完成后调 [upSourceView] 同步表单 (对照 app 端 Activity.upSourceView)
 * - 实现 [BookSourceEditCallbacks] 22 个回调, 桥接 shared 端事件到桌面端业务逻辑:
 *   - 保存/复制/粘贴/分享: 用 [AppDbProviders] / [SourceHelp] / AWT 剪贴板实现
 *   - 调试: 调 [onDebugSource] 路由回调 (由 DesktopApp 注入, 切到 BOOK_SOURCE_DEBUG)
 *   - 登录/源变量: 调 shared 下沉的 SourceLoginDialog / VariableDialog
 *   - 搜索: 先保存 → 写全局 searchScope → onSearchSource 切搜索路由
 *   - Cookie: CookieStoreProviders.get()?.removeCookie (桌面端注册的是 SharedCookieStore, Room 持久化)
 *   - 自动缩进: CodeView (Android 专属), 桌面端无对应能力, no-op
 *   - 帮助: 用 [Desktop.browse] 打开浏览器 (对应 app 端 showHelp)
 * - **codeEditorSlot**: 用 [OutlinedTextField] 替代 app 端 `AndroidView { CodeView }`
 *   (CodeView 是 Android 专属语法高亮控件, 桌面端无对应实现)
 * - **bottomBar**: 桌面端无 KeyboardToolbar (依赖 CodeView + keyboardAssistsDao), 传空实现
 *
 * # saveSource 落库逻辑 (对照 app 端 [BookSourceEditViewModel.save])
 *
 * - copy oldSource 备份 → 修改字段 → 探索 URL 变更则 clearExploreKindsCache →
 *   jsLib 变更则 SharedJsScope.remove → bookSourceUrl 变更则 SourceHelp.deleteBookSource
 *   (主键变更) → bookSourceDao.insert → onSaved()
 *
 * # 简化项 (与 app 端 [io.legado.app.ui.book.source.edit.BookSourceEditActivity] 对比)
 *
 * - **不接入 IntentData.source**: 直接用 sourceUrl 查 DAO (app 端优先 IntentData.source)
 * - **不接入 Activity 结果回传**: app 端 setResult(RESULT_OK, origin) 给 ChangeBookSourceDialog,
 *   desktop 端无对应路由, 仅调 onSaved
 * - **不接入 ruleHelpVersionIsLast 引导**: 依赖 [io.legado.app.help.config.LocalConfig],
 *   桌面端未下沉 LocalConfig, 首次打开不弹帮助页
 * - **不接入 BookSourceDebugActivity 路由内的 IntentData.source**: 通过 onDebugSource
 *   回调由宿主切路由, 携带 sourceUrl (调试页自行查 DAO)
 *
 * @param sourceUrl 书源 URL (对应 app 端 intent extra "sourceUrl"); 空串视为新建
 * @param onBack 返回回调 (由 DesktopApp 注入, 切回调用方路由)
 * @param onSaved 保存成功回调 (由 DesktopApp 注入, 切回书源管理页刷新)
 * @param onDebugSource 调试回调 (由 DesktopApp 注入, 切到 BOOK_SOURCE_DEBUG 路由);
 *        默认空实现, 未接入时调试按钮仅记日志
 * @param onSearchSource 搜索回调 (由 DesktopApp 注入, 切到 SEARCH 路由);
 *        默认空实现, 未接入时搜索按钮仅保存源不跳转
 */
@Composable
fun BookSourceEditScreen(
    sourceUrl: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDebugSource: (String) -> Unit = {},
    onSearchSource: () -> Unit = {},
) {
    // 注入 desktop 平台 Provider (commonMain AppTheme 依赖)
    val themeStore = remember { DesktopThemeStoreProvider() }
    val appConfig = remember { DesktopAppConfigProvider() }
    val eventBus = remember { DesktopEventBusProvider() }
    CompositionLocalProvider(
        LocalThemeStoreProvider provides themeStore,
        LocalAppConfigProvider provides appConfig,
        LocalEventBusProvider provides eventBus,
    ) {
        AppTheme {
            BookSourceEditContent(sourceUrl, onBack, onSaved, onDebugSource, onSearchSource)
        }
    }
}

@Composable
private fun BookSourceEditContent(
    sourceUrl: String,
    onBackCallback: () -> Unit,
    onSavedCallback: () -> Unit,
    onDebugSource: (String) -> Unit,
    onSearchCallback: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 顶部表单状态 (下沉到 shared 的 BookSourceEditState, 由其持有 mutableState 字段)
    val editState = remember { BookSourceEditState() }

    // 各 tab 的字段实体列表 (对照 app 端 Activity 同名 ArrayList 字段)
    // 用 remember 持有稳定引用, upSourceView/getSource 直接读写
    val sourceEntities = remember { ArrayList<EditEntity>() }
    val searchEntities = remember { ArrayList<EditEntity>() }
    val exploreEntities = remember { ArrayList<EditEntity>() }
    val infoEntities = remember { ArrayList<EditEntity>() }
    val tocEntities = remember { ArrayList<EditEntity>() }
    val contentEntities = remember { ArrayList<EditEntity>() }
    val reviewEntities = remember { ArrayList<EditEntity>() }

    // 图片样式下拉选项 label (rememberString 是 @Composable, 顶层 remember 一次;
    // imageStyleSelections 的 remember lambda 非 @Composable, 需预先缓存 label 后捕获)
    // key 对齐 ResourceProvider.jvm.kt 字面量 Map (与 app 端 values-zh/strings.xml 一致)
    val imageStyleDefaultLabel = rememberString("image_style_default")
    val imageStyleFullLabel = rememberString("image_style_full")
    val imageStyleTextLabel = rememberString("image_style_text")
    val imageStyleSingleLabel = rememberString("image_style_single")
    // 图片样式下拉选项 (对照 app 端 imageStyleSelections)
    val imageStyleSelections = remember(
        imageStyleDefaultLabel, imageStyleFullLabel, imageStyleTextLabel, imageStyleSingleLabel,
    ) {
        listOf(
            imageStyleDefaultLabel to null,
            imageStyleFullLabel to Book.imgStyleFull,
            imageStyleTextLabel to Book.imgStyleText,
            imageStyleSingleLabel to Book.imgStyleSingle,
        )
    }

    // 当前编辑的书源 (null 表示尚未加载/新建)
    // 用 MutableState 持有, 回调内读写 .value 即时生效
    val bookSourceState = remember { mutableStateOf<BookSource?>(null) }

    // 书源登录对话框状态 (null=隐藏, 非空=显示; onLogin 触发后填入当前编辑的 bookSource,
    // 末尾 SourceLoginDialog 渲染分支读取, 确认按钮调 dao.update 写回 header 字段)
    var showLoginDialog by remember { mutableStateOf<BookSource?>(null) }
    // 变量编辑对话框状态 (false=隐藏, true=显示; onSetSourceVariable 触发)
    // 书源编辑场景无具体书籍, bookVariables 传空 Map
    var showVariableDialog by remember { mutableStateOf(false) }

    // ---- AlertDialog 文案 (rememberString 是 @Composable, 顶层 remember 一次;
    //   后续辅助函数 / callbacks lambda 非 @Composable, 需预先缓存) ----
    // key 对齐 ResourceProvider.jvm.kt 字面量 Map (与 app 端 values-zh/strings.xml 一致)
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val nonNullNameUrlLabel = rememberString("non_null_name_url")
    val saveFailedLabel = rememberString("save_failed")
    val saveSourceErrorLabel = rememberString("save_source_error")
    val pasteSourceLabel = rememberString("paste_source")
    val clipboardEmptyLabel = rememberString("clipboard_empty")
    val pasteSourceErrorLabel = rememberString("paste_source_error")
    val wrongFormatLabel = rememberString("wrong_format")
    val enableDangerousApiLabel = rememberString("enable_dangerous_api")
    val enableDangerousApiWarnLabel = rememberString("enable_dangerous_api_warn")
    // 退出未保存确认对话框 (对照 app 端 Activity.finish 弹窗)
    val exitLabel = rememberString("exit")
    val exitNoSaveLabel = rememberString("exit_no_save")
    val yesLabel = rememberString("yes")
    val noLabel = rememberString("no")

    // ---- upSourceView EditEntity label (rememberString 是 @Composable, 顶层 remember 一次;
    //   upSourceView 局部函数非 @Composable, 需预先缓存 label 后捕获) ----
    // key 对齐 ResourceProvider.jvm.kt 字面量 Map (与 app 端 values-zh/strings.xml 一致)
    // 基本信息 (sourceEntities)
    val sourceUrlLabel = rememberString("source_url")
    val sourceNameLabel = rememberString("source_name")
    val sourceGroupLabel = rememberString("source_group")
    val commentLabel = rememberString("comment")
    // 注意: 已有 "login_url" 值为 "登录 URL(loginUrl)", 与原文本 "登录 URL" 不同, 用新 key source_login_url
    val sourceLoginUrlLabel = rememberString("source_login_url")
    // 注意: "login_ui" 值为 "登录界面(loginUi)", 与原文本 "登录界面" 不同, 用新 key source_login_ui
    val sourceLoginUiLabel = rememberString("source_login_ui")
    val loginCheckJsLabel = rememberString("login_check_js")
    val coverDecodeJsLabel = rememberString("cover_decode_js")
    val bookUrlPatternLabel = rememberString("book_url_pattern")
    val sourceHttpHeaderLabel = rememberString("source_http_header")
    val variableCommentLabel = rememberString("variable_comment")
    val concurrentRateLabel = rememberString("concurrent_rate")
    val jsLibLabel = rememberString("js_lib")
    // 搜索 (searchEntities)
    val rSearchUrlLabel = rememberString("r_search_url")
    val checkKeyWordLabel = rememberString("check_key_word")
    val rBookListLabel = rememberString("r_book_list")
    val rBookNameLabel = rememberString("r_book_name")
    val rAuthorLabel = rememberString("r_author")
    val ruleBookKindLabel = rememberString("rule_book_kind")
    val ruleWordCountLabel = rememberString("rule_word_count")
    val ruleLastChapterLabel = rememberString("rule_last_chapter")
    val ruleBookIntroLabel = rememberString("rule_book_intro")
    val ruleCoverUrlLabel = rememberString("rule_cover_url")
    val rBookUrlLabel = rememberString("r_book_url")
    val ruleHasMoreLabel = rememberString("rule_has_more")
    // 发现 (exploreEntities) - 复用 searchEntities 同名字段
    val rFindUrlLabel = rememberString("r_find_url")
    // 详情页 (infoEntities)
    val ruleBookInfoInitLabel = rememberString("rule_book_info_init")
    val ruleTocUrlLabel = rememberString("rule_toc_url")
    val ruleCanReNameLabel = rememberString("rule_can_re_name")
    val downloadUrlRuleLabel = rememberString("download_url_rule")
    // 目录页 (tocEntities)
    val preUpdateJsLabel = rememberString("pre_update_js")
    val ruleChapterNameLabel = rememberString("rule_chapter_name")
    val ruleChapterUrlLabel = rememberString("rule_chapter_url")
    val ruleIsVolumeLabel = rememberString("rule_is_volume")
    val ruleUpdateTimeLabel = rememberString("rule_update_time")
    val ruleIsVipLabel = rememberString("rule_is_vip")
    val ruleIsPayLabel = rememberString("rule_is_pay")
    // 正文页 (contentEntities)
    val ruleBookContentLabel = rememberString("rule_book_content")
    val subContentRuleLabel = rememberString("sub_content_rule")
    val ruleShouldOverrideUrlLoadingLabel = rememberString("rule_should_override_url_loading")
    val ruleWebJsLabel = rememberString("rule_web_js")
    val ruleSourceRegexLabel = rememberString("rule_source_regex")
    val ruleReplaceRegexLabel = rememberString("rule_replace_regex")
    val ruleImageStyleLabel = rememberString("rule_image_style")
    val imageDecodeLabel = rememberString("image_decode")
    val rulePayActionLabel = rememberString("rule_pay_action")
    val musicCoverRuleLabel = rememberString("music_cover_rule")
    // 段评 (reviewEntities)
    val reviewCountRuleLabel = rememberString("review_count_rule")
    val totalReviewCountRuleLabel = rememberString("total_review_count_rule")
    val reviewUrlRuleLabel = rememberString("review_url_rule")
    val reviewListRuleLabel = rememberString("review_list_rule")
    val reviewIdRuleLabel = rememberString("review_id_rule")
    val avatarRuleLabel = rememberString("avatar_rule")
    val nicknameRuleLabel = rememberString("nickname_rule")
    val contentRuleLabel = rememberString("content_rule")
    val postTimeRuleLabel = rememberString("post_time_rule")
    val extraRuleLabel = rememberString("extra_rule")
    val imagesRuleLabel = rememberString("images_rule")
    val voteUpCountRuleLabel = rememberString("vote_up_count_rule")
    val voteUpSelectedRuleLabel = rememberString("vote_up_selected_rule")
    val voteDownSelectedRuleLabel = rememberString("vote_down_selected_rule")
    val replyCountRuleLabel = rememberString("reply_count_rule")
    val replyListUrlLabel = rememberString("reply_list_url")
    val voteUpRuleLabel = rememberString("vote_up_rule")
    val voteDownRuleLabel = rememberString("vote_down_rule")
    val replyRuleLabel = rememberString("reply_rule")
    val deleteRuleLabel = rememberString("delete_rule")

    // ---- AlertDialog 显示状态 (替换原 javax.swing.JOptionPane 同步阻塞;
    //   null/false = 隐藏, 非空/true = 显示; 与 BookSourceScreen deleteSelectionTarget 模式一致) ----
    // saveSource 校验失败: URL/Name 为空 (boolean, 无 payload)
    var emptyUrlNameDialog by remember { mutableStateOf(false) }
    // saveSource catch: 异常错误信息 (String?, 非空即显示, 持有 e.localizedMessage ?: saveFailedLabel)
    var saveErrorDialog by remember { mutableStateOf<String?>(null) }
    // pasteSource 剪贴板空 (boolean, 无 payload)
    var clipboardEmptyDialog by remember { mutableStateOf(false) }
    // pasteSource catch: 异常错误信息 (String?, 非空即显示, 持有 e.localizedMessage ?: wrongFormatLabel)
    var pasteErrorDialog by remember { mutableStateOf<String?>(null) }
    // onDangerousApiClick 启用确认 (boolean; 用户取消则回退 editState.enableDangerousApi = false)
    var enableDangerousApiConfirm by remember { mutableStateOf(false) }
    // 退出未保存确认 (对照 app 端 Activity.finish: getSource 与原源不等则弹确认)
    var showExitConfirmDialog by remember { mutableStateOf(false) }

    // ---- 业务辅助函数 (对齐 app 端 Activity/ViewModel 私有方法) ----
    // 注意: Kotlin 局部函数不能前向引用, 所有辅助函数定义在 LaunchedEffect/callbacks 之前

    /** bookSourceType → 下拉索引 (对照 app 端 Activity.bookSourceTypeToIndex) */
    fun bookSourceTypeToIndex(type: Int): Int = when (type) {
        BookSourceType.rss -> 5
        BookSourceType.video -> 4
        BookSourceType.file -> 3
        BookSourceType.image -> 2
        BookSourceType.audio -> 1
        else -> 0
    }

    /** 下拉索引 → bookSourceType (对照 app 端 Activity.indexToBookSourceType) */
    fun indexToBookSourceType(index: Int): Int = when (index) {
        5 -> BookSourceType.rss
        4 -> BookSourceType.video
        3 -> BookSourceType.file
        2 -> BookSourceType.image
        1 -> BookSourceType.audio
        else -> BookSourceType.default
    }

    /**
     * 同步表单状态与字段实体 (对照 app 端 Activity.upSourceView)。
     *
     * 把 [bookSource] 的字段写入 [editState] (顶部表单) 与 7 组 entities (各 tab 字段),
     * 最后递增 [BookSourceEditState.sourceVersion] 驱动表单区整体重建。
     */
    fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        // Header
        editState.bookSourceTypeIndex = bookSourceTypeToIndex(bs.bookSourceType)
        editState.enabled = bs.enabled
        editState.enabledCookieJar = bs.enabledCookieJar == true
        editState.enableDangerousApi = bs.enableDangerousApi == true
        editState.enabledExplore = bs.enabledExplore
        editState.enabledReview = bs.enabledReview
        editState.exploreStyleIndex = if (BookSource.exploreStyleIsVideo(bs.exploreStyle)) 1 else 0
        editState.exploreColsIndex = BookSource.exploreStyleCols(bs.exploreStyle).coerceIn(0, 6)
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, sourceUrlLabel))
            add(EditEntity("bookSourceName", bs.bookSourceName, sourceNameLabel))
            add(EditEntity("bookSourceGroup", bs.bookSourceGroup, sourceGroupLabel))
            add(EditEntity("bookSourceComment", bs.bookSourceComment, commentLabel))
            add(EditEntity("loginUrl", bs.loginUrl, sourceLoginUrlLabel))
            add(EditEntity("loginUi", bs.loginUi, sourceLoginUiLabel))
            add(EditEntity("loginCheckJs", bs.loginCheckJs, loginCheckJsLabel))
            add(EditEntity("coverDecodeJs", bs.coverDecodeJs, coverDecodeJsLabel))
            add(EditEntity("bookUrlPattern", bs.bookUrlPattern, bookUrlPatternLabel))
            add(EditEntity("header", bs.header, sourceHttpHeaderLabel))
            add(EditEntity("variableComment", bs.variableComment, variableCommentLabel))
            add(EditEntity("concurrentRate", bs.concurrentRate, concurrentRateLabel))
            add(EditEntity("jsLib", bs.jsLib, jsLibLabel))
        }
        // 搜索
        val sr = bs.searchRule
        searchEntities.clear()
        searchEntities.apply {
            add(EditEntity("searchUrl", bs.searchUrl, rSearchUrlLabel))
            add(EditEntity("checkKeyWord", sr.checkKeyWord, checkKeyWordLabel))
            add(EditEntity("bookList", sr.bookList, rBookListLabel))
            add(EditEntity("name", sr.name, rBookNameLabel))
            add(EditEntity("author", sr.author, rAuthorLabel))
            add(EditEntity("kind", sr.kind, ruleBookKindLabel))
            add(EditEntity("wordCount", sr.wordCount, ruleWordCountLabel))
            add(EditEntity("lastChapter", sr.lastChapter, ruleLastChapterLabel))
            add(EditEntity("intro", sr.intro, ruleBookIntroLabel))
            add(EditEntity("coverUrl", sr.coverUrl, ruleCoverUrlLabel))
            add(EditEntity("bookUrl", sr.bookUrl, rBookUrlLabel))
            add(EditEntity("hasMoreRule", sr.hasMoreRule, ruleHasMoreLabel))
        }
        // 发现
        val er = bs.exploreRule
        exploreEntities.clear()
        exploreEntities.apply {
            add(EditEntity("exploreUrl", bs.exploreUrl, rFindUrlLabel))
            add(EditEntity("bookList", er.bookList, rBookListLabel))
            add(EditEntity("name", er.name, rBookNameLabel))
            add(EditEntity("author", er.author, rAuthorLabel))
            add(EditEntity("kind", er.kind, ruleBookKindLabel))
            add(EditEntity("wordCount", er.wordCount, ruleWordCountLabel))
            add(EditEntity("lastChapter", er.lastChapter, ruleLastChapterLabel))
            add(EditEntity("intro", er.intro, ruleBookIntroLabel))
            add(EditEntity("coverUrl", er.coverUrl, ruleCoverUrlLabel))
            add(EditEntity("bookUrl", er.bookUrl, rBookUrlLabel))
            add(EditEntity("hasMoreRule", er.hasMoreRule, ruleHasMoreLabel))
        }
        // 详情页
        val ir = bs.bookInfoRule
        infoEntities.clear()
        infoEntities.apply {
            add(EditEntity("init", ir.init, ruleBookInfoInitLabel))
            add(EditEntity("name", ir.name, rBookNameLabel))
            add(EditEntity("author", ir.author, rAuthorLabel))
            add(EditEntity("kind", ir.kind, ruleBookKindLabel))
            add(EditEntity("wordCount", ir.wordCount, ruleWordCountLabel))
            add(EditEntity("lastChapter", ir.lastChapter, ruleLastChapterLabel))
            add(EditEntity("intro", ir.intro, ruleBookIntroLabel))
            add(EditEntity("coverUrl", ir.coverUrl, ruleCoverUrlLabel))
            add(EditEntity("tocUrl", ir.tocUrl, ruleTocUrlLabel))
            add(EditEntity("canReName", ir.canReName, ruleCanReNameLabel))
            add(EditEntity("downloadUrls", ir.downloadUrls, downloadUrlRuleLabel))
        }
        // 目录页
        val tr = bs.tocRule
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, preUpdateJsLabel))
            add(EditEntity("chapterList", tr.chapterList, rBookListLabel))
            add(EditEntity("chapterName", tr.chapterName, ruleChapterNameLabel))
            add(EditEntity("chapterUrl", tr.chapterUrl, ruleChapterUrlLabel))
            add(EditEntity("isVolume", tr.isVolume, ruleIsVolumeLabel))
            add(EditEntity("updateTime", tr.updateTime, ruleUpdateTimeLabel))
            add(EditEntity("isVip", tr.isVip, ruleIsVipLabel))
            add(EditEntity("isPay", tr.isPay, ruleIsPayLabel))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, ruleHasMoreLabel))
        }
        // 正文页
        val cr = bs.contentRule
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, ruleBookContentLabel))
            add(EditEntity("subContent", cr.subContent, subContentRuleLabel))
            add(EditEntity("title", cr.title, ruleChapterNameLabel))
            add(EditEntity("nextContentUrl", cr.nextContentUrl, ruleHasMoreLabel))
            add(EditEntity("shouldOverrideUrlLoading", cr.shouldOverrideUrlLoading, ruleShouldOverrideUrlLoadingLabel))
            add(EditEntity("webJs", cr.webJs, ruleWebJsLabel))
            add(EditEntity("sourceRegex", cr.sourceRegex, ruleSourceRegexLabel))
            add(EditEntity("replaceRegex", cr.replaceRegex, ruleReplaceRegexLabel))
            add(
                EditEntity(
                    "imageStyle",
                    cr.imageStyle,
                    ruleImageStyleLabel,
                    EditEntity.ViewType.spinner,
                    imageStyleSelections,
                ),
            )
            add(EditEntity("imageDecode", cr.imageDecode, imageDecodeLabel))
            add(EditEntity("payAction", cr.payAction, rulePayActionLabel))
            add(EditEntity("musicCover", cr.musicCover, musicCoverRuleLabel))
        }
        // 段评
        val rr = bs.reviewRule
        reviewEntities.clear()
        reviewEntities.apply {
            add(EditEntity("reviewCountRule", rr.reviewCountRule, reviewCountRuleLabel))
            add(EditEntity("totalCountRule", rr.totalCountRule, totalReviewCountRuleLabel))
            add(EditEntity("reviewUrl", rr.reviewUrl, reviewUrlRuleLabel))
            add(EditEntity("reviewList", rr.reviewList, reviewListRuleLabel))
            add(EditEntity("hasMoreRule", rr.hasMoreRule, ruleHasMoreLabel))
            add(EditEntity("reviewIdRule", rr.reviewIdRule, reviewIdRuleLabel))
            add(EditEntity("avatarRule", rr.avatarRule, avatarRuleLabel))
            add(EditEntity("nameRule", rr.nameRule, nicknameRuleLabel))
            add(EditEntity("contentRule", rr.contentRule, contentRuleLabel))
            add(EditEntity("postTimeRule", rr.postTimeRule, postTimeRuleLabel))
            add(EditEntity("extraRule", rr.extraRule, extraRuleLabel))
            add(EditEntity("imagesRule", rr.imagesRule, imagesRuleLabel))
            add(EditEntity("voteUpCountRule", rr.voteUpCountRule, voteUpCountRuleLabel))
            add(EditEntity("voteUpSelectedRule", rr.voteUpSelectedRule, voteUpSelectedRuleLabel))
            add(EditEntity("voteDownSelectedRule", rr.voteDownSelectedRule, voteDownSelectedRuleLabel))
            add(EditEntity("replyCountRule", rr.replyCountRule, replyCountRuleLabel))
            add(EditEntity("replyListUrl", rr.replyListUrl, replyListUrlLabel))
            add(EditEntity("voteUpRule", rr.voteUpRule, voteUpRuleLabel))
            add(EditEntity("voteDownRule", rr.voteDownRule, voteDownRuleLabel))
            add(EditEntity("replyRule", rr.replyRule, replyRuleLabel))
            add(EditEntity("deleteRule", rr.deleteRule, deleteRuleLabel))
        }
        editState.currentTab = 0
        editState.sourceVersion++
    }

    /** 按 tab 返回字段列表 (对照 app 端 Activity.editEntities) */
    fun editEntities(tab: Int): List<EditEntity> = when (tab) {
        1 -> searchEntities
        2 -> exploreEntities
        3 -> infoEntities
        4 -> tocEntities
        5 -> contentEntities
        6 -> reviewEntities
        else -> sourceEntities
    }

    /**
     * 从表单收集字段构造 [BookSource] (对照 app 端 Activity.getSource)。
     *
     * 读取 [editState] (顶部表单) 与 7 组 entities (各 tab 字段) 写回新的 BookSource 实例。
     */
    fun getSource(): BookSource {
        val source = bookSourceState.value?.copy() ?: BookSource()
        val searchRule = SearchRule()
        val exploreRule = ExploreRule()
        val bookInfoRule = BookInfoRule()
        val tocRule = TocRule()
        val contentRule = ContentRule()
        val reviewRule = ReviewRule()
        source.bookSourceType = indexToBookSourceType(editState.bookSourceTypeIndex)
        source.enabled = editState.enabled
        source.enabledCookieJar = editState.enabledCookieJar
        source.enableDangerousApi = editState.enableDangerousApi
        source.enabledExplore = editState.enabledExplore
        source.enabledReview = editState.enabledReview
        val exploreVideo = editState.exploreStyleIndex == 1
        source.exploreStyle = (if (exploreVideo) BookSource.EXPLORE_STYLE_VIDEO_FLAG else 0) or
            (editState.exploreColsIndex and BookSource.EXPLORE_STYLE_COLS_MASK)
        sourceEntities.forEach {
            when (it.key) {
                "bookSourceUrl" -> source.bookSourceUrl = it.text.orEmpty()
                "bookSourceName" -> source.bookSourceName = it.text.orEmpty()
                "bookSourceGroup" -> source.bookSourceGroup = it.text
                "loginUrl" -> source.loginUrl = it.text
                "loginUi" -> source.loginUi = it.text
                "loginCheckJs" -> source.loginCheckJs = it.text
                "coverDecodeJs" -> source.coverDecodeJs = it.text
                "bookUrlPattern" -> source.bookUrlPattern = it.text
                "header" -> source.header = it.text
                "bookSourceComment" -> source.bookSourceComment = it.text
                "concurrentRate" -> source.concurrentRate = it.text
                "variableComment" -> source.variableComment = it.text
                "jsLib" -> source.jsLib = it.text
            }
        }
        searchEntities.forEach {
            when (it.key) {
                "searchUrl" -> source.searchUrl = it.text
                "checkKeyWord" -> searchRule.checkKeyWord = it.text
                "bookList" -> searchRule.bookList = it.text
                "name" -> searchRule.name = it.text
                "author" -> searchRule.author = it.text
                "kind" -> searchRule.kind = it.text
                "intro" -> searchRule.intro = it.text
                "wordCount" -> searchRule.wordCount = it.text
                "lastChapter" -> searchRule.lastChapter = it.text
                "coverUrl" -> searchRule.coverUrl = it.text
                "bookUrl" -> searchRule.bookUrl = it.text
                "hasMoreRule" -> searchRule.hasMoreRule = it.text
            }
        }
        exploreEntities.forEach {
            when (it.key) {
                "exploreUrl" -> source.exploreUrl = it.text
                "bookList" -> exploreRule.bookList = it.text
                "name" -> exploreRule.name = it.text
                "author" -> exploreRule.author = it.text
                "kind" -> exploreRule.kind = it.text
                "intro" -> exploreRule.intro = it.text
                "wordCount" -> exploreRule.wordCount = it.text
                "lastChapter" -> exploreRule.lastChapter = it.text
                "coverUrl" -> exploreRule.coverUrl = it.text
                "bookUrl" -> exploreRule.bookUrl = it.text
                "hasMoreRule" -> exploreRule.hasMoreRule = it.text
            }
        }
        infoEntities.forEach {
            when (it.key) {
                "init" -> bookInfoRule.init = it.text
                "name" -> bookInfoRule.name = it.text
                "author" -> bookInfoRule.author = it.text
                "kind" -> bookInfoRule.kind = it.text
                "intro" -> bookInfoRule.intro = it.text
                "wordCount" -> bookInfoRule.wordCount = it.text
                "lastChapter" -> bookInfoRule.lastChapter = it.text
                "coverUrl" -> bookInfoRule.coverUrl = it.text
                "tocUrl" -> bookInfoRule.tocUrl = it.text
                "canReName" -> bookInfoRule.canReName = it.text
                "downloadUrls" -> bookInfoRule.downloadUrls = it.text
            }
        }
        tocEntities.forEach {
            when (it.key) {
                "preUpdateJs" -> tocRule.preUpdateJs = it.text
                "chapterList" -> tocRule.chapterList = it.text
                "chapterName" -> tocRule.chapterName = it.text
                "chapterUrl" -> tocRule.chapterUrl = it.text
                "isVolume" -> tocRule.isVolume = it.text
                "updateTime" -> tocRule.updateTime = it.text
                "isVip" -> tocRule.isVip = it.text
                "isPay" -> tocRule.isPay = it.text
                "nextTocUrl" -> tocRule.nextTocUrl = it.text
            }
        }
        contentEntities.forEach {
            when (it.key) {
                "content" -> contentRule.content = it.text
                "title" -> contentRule.title = it.text
                "nextContentUrl" -> contentRule.nextContentUrl = it.text
                "shouldOverrideUrlLoading" -> contentRule.shouldOverrideUrlLoading = it.text
                "webJs" -> contentRule.webJs = it.text
                "sourceRegex" -> contentRule.sourceRegex = it.text
                "replaceRegex" -> contentRule.replaceRegex = it.text
                "imageStyle" -> contentRule.imageStyle = it.text
                "imageDecode" -> contentRule.imageDecode = it.text
                "payAction" -> contentRule.payAction = it.text
                "subContent" -> contentRule.subContent = it.text
                "musicCover" -> contentRule.musicCover = it.text
            }
        }
        reviewEntities.forEach {
            when (it.key) {
                "reviewUrl" -> reviewRule.reviewUrl = it.text
                "reviewList" -> reviewRule.reviewList = it.text
                "reviewCountRule" -> reviewRule.reviewCountRule = it.text
                "totalCountRule" -> reviewRule.totalCountRule = it.text
                "hasMoreRule" -> reviewRule.hasMoreRule = it.text
                "reviewIdRule" -> reviewRule.reviewIdRule = it.text
                "avatarRule" -> reviewRule.avatarRule = it.text
                "nameRule" -> reviewRule.nameRule = it.text
                "contentRule" -> reviewRule.contentRule = it.text
                "postTimeRule" -> reviewRule.postTimeRule = it.text
                "extraRule" -> reviewRule.extraRule = it.text
                "imagesRule" -> reviewRule.imagesRule = it.text
                "voteUpCountRule" -> reviewRule.voteUpCountRule = it.text
                "voteUpSelectedRule" -> reviewRule.voteUpSelectedRule = it.text
                "voteDownSelectedRule" -> reviewRule.voteDownSelectedRule = it.text
                "replyCountRule" -> reviewRule.replyCountRule = it.text
                "replyListUrl" -> reviewRule.replyListUrl = it.text
                "voteUpRule" -> reviewRule.voteUpRule = it.text
                "voteDownRule" -> reviewRule.voteDownRule = it.text
                "replyRule" -> reviewRule.replyRule = it.text
                "deleteRule" -> reviewRule.deleteRule = it.text
            }
        }
        source.searchRule = searchRule
        source.exploreRule = exploreRule
        source.bookInfoRule = bookInfoRule
        source.tocRule = tocRule
        source.contentRule = contentRule
        source.reviewRule = reviewRule
        return source
    }

    /**
     * 保存书源 (对照 app 端 ViewModel.save)。
     *
     * 流程: 校验 URL/Name 非空 → 比较 oldSource → 探索 URL 变更 clearExploreKindsCache →
     * jsLib 变更 SharedJsScope.remove → bookSourceUrl 变更 SourceHelp.deleteBookSource →
     * bookSourceDao.insert → 更新 bookSourceState → onSaved()
     */
    fun saveSource() {
        val source = getSource()
        if (source.bookSourceUrl.isBlank() || source.bookSourceName.isBlank()) {
            // 弹 AlertDialog (替换原 JOptionPane.showMessageDialog 同步阻塞,
            // 与 BookSourceScreen deleteSelectionTarget 模式一致; 末尾 AlertDialog 渲染分支读取 emptyUrlNameDialog)
            emptyUrlNameDialog = true
            return
        }
        scope.launch {
            try {
                val oldSource = bookSourceState.value ?: BookSource()
                if (!source.equal(oldSource)) {
                    source.lastUpdateTime = System.currentTimeMillis()
                    if (oldSource.exploreUrl != source.exploreUrl) {
                        oldSource.clearExploreKindsCache()
                    }
                    if (oldSource.jsLib != source.jsLib) {
                        SharedJsScope.remove(oldSource.jsLib)
                    }
                }
                // bookSourceUrl 变更: 删除旧源 (主键变更, 否则 insert 会覆盖)
                val oldKey = oldSource.bookSourceUrl.takeIf { it.isNotBlank() }
                if (oldKey != null && oldKey != source.bookSourceUrl) {
                    SourceHelp.deleteBookSource(oldKey)
                }
                AppDbProviders.get().bookSourceDao.insert(source)
                bookSourceState.value = source
                withContext(Dispatchers.Main) { onSavedCallback() }
            } catch (e: Throwable) {
                AppLog.put(jvmGetString("save_book_source_failed"), e)
                // 弹 AlertDialog (替换原 JOptionPane.showMessageDialog;
                // Compose state setter 线程安全 (snapshot), 无需 withContext(Dispatchers.Main) 切主线程;
                // 末尾 AlertDialog 渲染分支读取 saveErrorDialog, payload = e.localizedMessage ?: saveFailedLabel)
                saveErrorDialog = e.localizedMessage ?: saveFailedLabel
            }
        }
    }

    /** 调试书源 (对照 app 端 Activity.debugSource: 先保存再跳转) */
    fun debugSource() {
        val source = getSource()
        scope.launch {
            try {
                // 调试前先保存, 确保调试的是最新版本 (对照 app 端 viewModel.save { startActivity<Debug> })
                AppDbProviders.get().bookSourceDao.insert(source)
                bookSourceState.value = source
                withContext(Dispatchers.Main) {
                    if (source.bookSourceUrl.isNotBlank()) {
                        onDebugSource(source.bookSourceUrl)
                    }
                }
            } catch (e: Throwable) {
                AppLog.put(jvmGetString("debug_book_source_save_failed"), e)
            }
        }
    }

    /**
     * 用当前源搜索 (对照 app 端 Activity.searchSource: 先保存再设置 searchScope 再跳转)。
     *
     * 流程: getSource → insert 保存 → 设置全局 searchScope = SearchScope(source) → onSearchCallback()。
     * app 端 startActivity<SearchActivity> + putExtra searchScope; 桌面端 SearchScreen 的
     * SearchViewModel 初始化读 AppConfigProviders.get().searchScope, 故先写入全局。
     */
    fun searchSource() {
        val source = getSource()
        scope.launch {
            try {
                AppDbProviders.get().bookSourceDao.insert(source)
                bookSourceState.value = source
                // 设置搜索范围为当前源 (对照 app 端 putExtra("searchScope", SearchScope(source).toString()))
                AppConfigProviders.get().searchScope = SearchScope(source).toString()
                withContext(Dispatchers.Main) { onSearchCallback() }
            } catch (e: Throwable) {
                AppLog.put(jvmGetString("search_book_source_save_failed"), e)
            }
        }
    }

    /** 当前源是否有登录入口 (对照 app 端 Activity.hasLogin) */
    fun hasLogin(): Boolean = getSource().hasLogin()

    /** 复制源为 JSON 到剪贴板 (对照 app 端 Activity.copySource) */
    fun copySource() {
        runCatching {
            val json = GSON.toJson(getSource())
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(json), null)
            AppLog.put(jvmGetString("book_source_copied"))
        }.onFailure { AppLog.put(jvmGetString("copy_book_source_failed"), it) }
    }

    /** 分享源字符串到剪贴板 (对照 app 端 Activity.shareSourceStr, 桌面端无 Intent 分享) */
    fun shareSourceStr() {
        runCatching {
            val json = GSON.toJson(getSource())
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(json), null)
            AppLog.put(jvmGetString("book_source_str_copied_desktop"))
        }.onFailure { AppLog.put(jvmGetString("share_book_source_failed"), it) }
    }

    /**
     * 解析书源文本 (对照 app 端 ViewModel.importSource)。
     *
     * 支持: URL (OkHttp 下载) / JSON 数组 (取第一项) / JSON 对象。
     */
    suspend fun importSource(text: String): BookSource = withContext(Dispatchers.IO) {
        when {
            text.isAbsUrl() -> {
                val client = OkHttpClientProviders.get().okHttpClient
                val request = Request.Builder().url(text).build()
                val body = client.newCall(request).execute().use { it.body.string() }
                importSource(body)
            }
            text.isJsonArray() -> {
                GSON.fromJsonArray<BookSource>(text).getOrThrow()[0]
            }
            text.isJsonObject() -> {
                GSON.fromJsonObject<BookSource>(text).getOrThrow()
            }
            else -> throw IllegalArgumentException(jvmGetString("wrong_format"))
        }
    }

    /**
     * 粘贴源 (对照 app 端 ViewModel.pasteSource)。
     *
     * 读剪贴板文本 → 支持 URL/JSON 数组/JSON 对象三种格式 → 解析为 BookSource → upSourceView。
     */
    fun pasteSource() {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.getData(DataFlavor.stringFlavor) as? String
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                // 弹 AlertDialog (替换原 JOptionPane.showMessageDialog;
                // Compose state setter 线程安全 (snapshot), 无需 withContext(Dispatchers.Main) 切主线程;
                // 末尾 AlertDialog 渲染分支读取 clipboardEmptyDialog)
                clipboardEmptyDialog = true
                return@launch
            }
            try {
                val source = importSource(text)
                bookSourceState.value = source
                withContext(Dispatchers.Main) { upSourceView(source) }
            } catch (e: Throwable) {
                AppLog.put(jvmGetString("paste_book_source_failed"), e)
                // 弹 AlertDialog (替换原 JOptionPane.showMessageDialog;
                // 末尾 AlertDialog 渲染分支读取 pasteErrorDialog, payload = e.localizedMessage ?: wrongFormatLabel)
                pasteErrorDialog = e.localizedMessage ?: wrongFormatLabel
            }
        }
    }

    /** 危险 API 开关 (对照 app 端 Activity.onDangerousApiClick) */
    fun onDangerousApiClick(isChecked: Boolean) {
        editState.enableDangerousApi = isChecked
        val originalEnabled = bookSourceState.value?.enableDangerousApi == true
        if (isChecked != originalEnabled) {
            SharedJsScope.remove(bookSourceState.value?.jsLib)
        }
        if (isChecked) {
            // 弹 AlertDialog 二次确认 (替换原 JOptionPane.showConfirmDialog 同步阻塞,
            // 与 BookSourceScreen deleteSelectionTarget 模式一致; 末尾 AlertDialog 渲染分支读取 enableDangerousApiConfirm,
            // 用户确认 (YES) 则保留 isChecked=true (啥都不做), 用户取消 (NO) 则回退 editState.enableDangerousApi = false,
            // 复刻原 `if (result != YES_OPTION) editState.enableDangerousApi = false` 语义)
            enableDangerousApiConfirm = true
        }
    }

    /** 帮助页 (对照 app 端 Activity.help: showHelp(fileName)) */
    fun help(fileName: String) {
        val url = when (fileName) {
            "ruleHelp" -> "https://github.com/gedoor/legado/wiki/书源制作"
            "jsHelp" -> "https://github.com/gedoor/legado/wiki/Js规则"
            "regexHelp" -> "https://www.runoob.com/regexp/regexp-tutorial.html"
            else -> return
        }
        browseUrl(url)
    }

    // ---- 数据加载 (对照 app 端 onActivityCreated: viewModel.initData { upSourceView }) ----
    LaunchedEffect(sourceUrl) {
        val source = if (sourceUrl.isNotBlank()) {
            AppDbProviders.get().bookSourceDao.getBookSource(sourceUrl)
        } else {
            null
        }
        bookSourceState.value = source
        upSourceView(source)
    }

    // ---- BookSourceEditCallbacks 实现 (对照 app 端 Activity.Content 中 callbacks 构造) ----
    val callbacks = remember(onBackCallback, onSavedCallback, onDebugSource, onSearchCallback) {
        BookSourceEditCallbacks(
            onBack = {
                // 退出前检查未保存变更 (对照 app 端 Activity.finish: getSource 与原源不等则弹确认)
                val source = getSource()
                if (!source.equal(bookSourceState.value ?: BookSource())) {
                    showExitConfirmDialog = true
                } else {
                    onBackCallback()
                }
            },
            onSave = { saveSource() },
            onDebug = { debugSource() },
            onLogin = {
                // 触发 SourceLoginDialog 显示, 传入当前编辑的 bookSource
                // (header 字段会被 Dialog 内部修改后通过 onConfirm 回传, 调 dao.update 写回)
                showLoginDialog = bookSourceState.value
            },
            onSearch = { searchSource() },
            onClearCookie = {
                // 清除当前源 URL 的 Cookie (对照 app 端 viewModel.clearCookie(url) → CookieStore.removeCookie)
                // 桌面端注册的是 commonMain SharedCookieStore (Room 持久化), 走 CookieStoreProviders 统一访问
                CookieStoreProviders.get()?.removeCookie(getSource().bookSourceUrl)
            },
            onCopySource = { copySource() },
            onPasteSource = { pasteSource() },
            onAutoIndent = {
                // TODO: 依赖 CodeView.reFormat (Android 专属控件), 桌面端 OutlinedTextField 无对应能力
            },
            onSetSourceVariable = {
                // 触发 VariableDialog 显示 (末尾渲染分支读取 showVariableDialog)
                showVariableDialog = true
            },
            onShareSourceStr = { shareSourceStr() },
            onHelp = { help(it) },
            hasLogin = { hasLogin() },
            onBookSourceTypeChange = { editState.bookSourceTypeIndex = it },
            onEnabledChange = { editState.enabled = it },
            onEnabledCookieJarChange = { editState.enabledCookieJar = it },
            onEnableDangerousApiClick = { onDangerousApiClick(it) },
            onEnabledReviewChange = { editState.enabledReview = it },
            onEnabledExploreChange = { editState.enabledExplore = it },
            onExploreStyleChange = { editState.exploreStyleIndex = it },
            onExploreColsChange = { editState.exploreColsIndex = it },
            onTabChange = { editState.currentTab = it },
        )
    }

    // 位置参数 + 命名参数调用 shared 端 Screen (函数类型参数用命名参数更清晰)
    io.legado.app.ui.book.source.edit.BookSourceEditScreen(
        state = editState,
        callbacks = callbacks,
        editEntities = { tab -> editEntities(tab) },
        codeEditorSlot = { entity, modifier ->
            // 桌面端用 OutlinedTextField 替代 CodeView (Android 专属语法高亮控件)
            // entity.value 双向同步: 初始化时读, 输入时写回 (对照 app 端 createEditField)
            AppTextField(
                value = entity.value.orEmpty(),
                onValueChange = { entity.value = it },
                modifier = modifier.fillMaxWidth(),
                label = entity.hint,
                textStyle = TextStyle(fontSize = 13.sp),
                // 多行输入 (CodeView 默认多行), 不限行数 (app 端用 sourceEditMaxLine, 默认 MAX_VALUE)
                minLines = 2,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        },
        bottomBar = {
            // 桌面端无 KeyboardToolbar (依赖 CodeView + keyboardAssistsDao), 留空
        },
    )

    // ---- 对话框渲染 (替换原 javax.swing.JOptionPane) ----

    // 1. saveSource 校验失败: URL/Name 为空 (替换原 JOptionPane.showMessageDialog WARNING_MESSAGE)
    if (emptyUrlNameDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { emptyUrlNameDialog = false },
            title = saveFailedLabel,
            message = nonNullNameUrlLabel,
            okButton = AlertButton(okLabel),
        )
    }

    // 2. saveSource catch: 保存异常 (替换原 JOptionPane.showMessageDialog ERROR_MESSAGE;
    //    payload = e.localizedMessage ?: saveFailedLabel, 在 catch 内写入 saveErrorDialog)
    saveErrorDialog?.let { errorMsg ->
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { saveErrorDialog = null },
            title = saveSourceErrorLabel,
            message = errorMsg,
            okButton = AlertButton(okLabel),
        )
    }

    // 3. pasteSource 剪贴板空 (替换原 JOptionPane.showMessageDialog WARNING_MESSAGE)
    if (clipboardEmptyDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { clipboardEmptyDialog = false },
            title = pasteSourceLabel,
            message = clipboardEmptyLabel,
            okButton = AlertButton(okLabel),
        )
    }

    // 4. pasteSource catch: 粘贴异常 (替换原 JOptionPane.showMessageDialog ERROR_MESSAGE;
    //    payload = e.localizedMessage ?: wrongFormatLabel, 在 catch 内写入 pasteErrorDialog)
    pasteErrorDialog?.let { errorMsg ->
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { pasteErrorDialog = null },
            title = pasteSourceErrorLabel,
            message = errorMsg,
            okButton = AlertButton(okLabel),
        )
    }

    // 5. onDangerousApiClick 启用确认 (替换原 JOptionPane.showConfirmDialog YES_NO_OPTION;
    //    确认 (YES) → 关闭对话框, 保留 isChecked=true (啥都不做);
    //    取消 (NO) → 关闭对话框 + 回退 editState.enableDangerousApi = false,
    //    复刻原 `if (result != YES_OPTION) editState.enableDangerousApi = false` 语义)
    if (enableDangerousApiConfirm) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = {
                // 点外部 / 返回键取消: 视为 NO, 回退开关
                enableDangerousApiConfirm = false
                editState.enableDangerousApi = false
            },
            title = enableDangerousApiLabel,
            message = enableDangerousApiWarnLabel,
            // YES: 保留 isChecked=true (啥都不做), 仅关闭
            okButton = AlertButton(okLabel, dismissOnClick = false) {
                enableDangerousApiConfirm = false
            },
            // NO: 回退 editState.enableDangerousApi = false
            cancelButton = AlertButton(cancelLabel, dismissOnClick = false) {
                enableDangerousApiConfirm = false
                editState.enableDangerousApi = false
            },
        )
    }

    // 6. 退出未保存确认 (对照 app 端 Activity.finish 弹窗; 是=继续编辑, 否=放弃保存退出)
    if (showExitConfirmDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { showExitConfirmDialog = false },
            title = exitLabel,
            message = exitNoSaveLabel,
            // 是: 继续编辑 (仅关闭对话框, 对照 app 端 positiveButton 默认 dismiss)
            okButton = AlertButton(yesLabel, dismissOnClick = false) {
                showExitConfirmDialog = false
            },
            // 否: 放弃保存退出 (对照 app 端 negativeButton { super.finish() })
            cancelButton = AlertButton(noLabel, dismissOnClick = false) {
                showExitConfirmDialog = false
                onBackCallback()
            },
        )
    }

    // ---- 书源登录对话框 (onLogin 触发, 调用 shared/sharedUiMain 下沉的 SourceLoginDialog) ----
    // 登录逻辑由 shared SourceLoginDialog 内部处理 (putLoginInfo + login JS),
    // 与 app 端一致; 桌面端仅需注入 onOpenUrl 回调
    showLoginDialog?.let { src ->
        SourceLoginDialog(
            source = src,
            onDismiss = { showLoginDialog = null },
            onOpenUrl = { url -> browseUrl(url) },
        )
    }

    // ---- 变量编辑对话框 (onSetSourceVariable 触发) ----
    // KMP 共享 VariableDialog: 书源编辑场景仅编辑源变量, bookVariables 传空 Map (无具体书籍)
    // sourceVariables 从 bookSourceState.value.getVariable() 解析 (decodeStringMapOrNull 容错)
    // onConfirm 写回: source.setVariable(encodeStringMap) (SourceCacheProviders 持久化, 无需 dao.update)
    if (showVariableDialog) {
        val src = bookSourceState.value
        VariableDialog(
            sourceVariables = decodeStringMapOrNull(src?.getVariable()) ?: emptyMap(),
            bookVariables = emptyMap(),
            onConfirm = { newSourceVars, _ ->
                scope.launch {
                    src?.setVariable(encodeStringMap(newSourceVars))
                }
                showVariableDialog = false
            },
            onDismiss = { showVariableDialog = false },
        )
    }
}
