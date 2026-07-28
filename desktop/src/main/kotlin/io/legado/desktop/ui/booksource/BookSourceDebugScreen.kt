package io.legado.desktop.ui.booksource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.compose.component.AlertButton
import io.legado.app.ui.compose.component.AppAlertDialog
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.Debug

import io.legado.app.ui.book.source.debug.BookSourceDebugUiActions
import io.legado.app.ui.book.source.debug.BookSourceDebugUiState
import io.legado.app.ui.compose.platform.DesktopAppConfigProvider
import io.legado.app.ui.compose.platform.DesktopEventBusProvider
import io.legado.app.ui.compose.platform.DesktopThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.jvmGetString
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.browseUrl
import kotlinx.coroutines.launch

/**
 * 桌面端书源调试 Screen 入口 (包装 shared/sharedUiMain 的 [io.legado.app.ui.book.source.debug.BookSourceDebugScreen])。
 *
 * 对照 desktop [BookSourceScreen] 模式, 仅做桌面平台适配, 展示与交互逻辑全部下沉到
 * shared/sharedUiMain 的 [io.legado.app.ui.book.source.debug.BookSourceDebugScreen]:
 *
 * - 注入 desktop 平台 Provider (ThemeStore / AppConfig / EventBus), 让 commonMain 的
 *   [AppTheme] / [io.legado.app.ui.book.source.debug.BookSourceDebugScreen] 可跨平台运行
 * - 持有调试状态 (logs / query / helpVisible / loading / textMy / textFx / clearFocusTick)
 *   并打包为 [BookSourceDebugUiState] 传入 shared 端
 * - 实现 [BookSourceDebugUiActions] 接口, 桥接 shared 端回调到桌面端调试逻辑
 * - 调试逻辑直接用 shared commonMain 的 [Debug] 单例, 实现 [Debug.Callback] 接收日志
 * - linkifyText 用正则匹配 URL 转 AnnotatedString (替代 app 端 autoLinkText, 后者依赖
 *   android.util.Patterns.WEB_URL)
 * - 帮助页用 [Desktop.browse] 打开浏览器 (app 端用 showHelp 读 assets md)
 *
 * 简化项 (与 app 端 [io.legado.app.ui.book.source.debug.BookSourceDebugActivity] 对比):
 * - 书源加载: 直接查 [AppDbProviders.get].bookSourceDao (app 端优先用 IntentData)
 * - 发现分类: commonMain 完整版 exploreKinds() (含 JS 求值 + 磁盘缓存, 与 app 端同一路径)
 * - selector / 源码查看 / 错误提示: 用 sharedUiMain AppAlertDialog (替换原 javax.swing.JOptionPane,
 *   与 BookSourceScreen deleteSelectionTarget 模式一致; 在 Composable 顶层 remember 持有 dialog state,
 *   末尾渲染对话框分支, 用户确认后通过回调继续业务流程)
 * - 帮助页: 打开 GitHub wiki (app 端读 assets/web/help/md/debugHelp.md)
 *
 * @param sourceUrl 书源 URL (对应 app 端 intent extra "key")
 * @param onBack 返回回调 (由 DesktopApp 注入, 切回调用方路由)
 */
@Composable
fun BookSourceDebugScreen(sourceUrl: String, onBack: () -> Unit) {
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
            BookSourceDebugContent(sourceUrl, onBack)
        }
    }
}

@Composable
private fun BookSourceDebugContent(sourceUrl: String, onBackCallback: () -> Unit) {
    val scope = rememberCoroutineScope()

    // 调试状态 (对齐 app 端 BookSourceDebugActivity 字段)
    val logs = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var helpVisible by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    val myLabel = rememberString("my")
    var textMy by remember { mutableStateOf(myLabel) }
    var textFx by remember { mutableStateOf(jvmGetString("debug_fx_default")) }
    var clearFocusTick by remember { mutableStateOf(0) }

    // 书源 + 源码缓存 (对齐 app 端 BookSourceDebugModel 字段)
    var bookSource by remember { mutableStateOf<BookSource?>(null) }
    var exploreKinds by remember { mutableStateOf<List<ExploreKind>>(emptyList()) }
    var searchSrc by remember { mutableStateOf<String?>(null) }
    var bookSrc by remember { mutableStateOf<String?>(null) }
    var tocSrc by remember { mutableStateOf<String?>(null) }
    var contentSrc by remember { mutableStateOf<String?>(null) }
    var reviewSrc by remember { mutableStateOf<String?>(null) }

    // 注册 Debug.Callback 接收日志 (对齐 app 端 BookSourceDebugModel.observe + printLog)
    // 退出时清理 (对齐 app 端 BookSourceDebugModel.onCleared)
    DisposableEffect(Unit) {
        Debug.callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
                // Debug.log 在后台线程调用, 切回主线程更新 Compose state
                scope.launch {
                    when (state) {
                        10 -> searchSrc = msg
                        20 -> bookSrc = msg
                        30 -> tocSrc = msg
                        40 -> contentSrc = msg
                        50 -> reviewSrc = msg
                        else -> {
                            logs.add(msg)
                            if (state == -1 || state == 1000) {
                                loading = false
                            }
                        }
                    }
                }
            }
        }
        onDispose {
            Debug.cancelDebug(true)
        }
    }

    // ---- AlertDialog 文案 (rememberString 是 @Composable, 顶层 remember 一次;
    //   后续辅助函数 / actions lambda 非 @Composable, 需预先缓存) ----
    // key 对齐 ResourceProvider.jvm.kt 字面量 Map (与 app 端 values-zh/strings.xml 一致)
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val debugErrorLabel = rememberString("debug_error")
    val noSourceFoundLabel = rememberString("no_source_found")
    val selectExploreLabel = rememberString("select_explore")
    val selectExploreKindLabel = rememberString("select_explore_kind")
    val noSourceCodeLabel = rememberString("no_source_code")
    // 源码查看弹窗 title / chip 默认值 label (rememberString 是 @Composable, 顶层 remember 一次;
    //   actions lambda 非 @Composable, 需预先缓存 label 后捕获)
    // key 对齐 ResourceProvider.jvm.kt 字面量 Map (与 app 端 values-zh/strings.xml 一致)
    val searchSrcLabel = rememberString("search_src")
    val bookSrcLabel = rememberString("book_src")
    val tocSrcLabel = rememberString("toc_src")
    val contentSrcLabel = rememberString("content_src")
    val reviewSrcLabel = rememberString("review_src")
    val systemLabel = rememberString("system")

    // ---- AlertDialog 显示状态 (替换原 javax.swing.JOptionPane 同步阻塞;
    //   null/false = 隐藏, 非空/true = 显示; 与 BookSourceScreen deleteSelectionTarget 模式一致) ----
    // startSearch 触发: 书源未获取到错误提示
    var noSourceDialog by remember { mutableStateOf(false) }
    // onChipFxLongClick 触发: 选择发现分类 (列表选择)
    var exploreKindPicker by remember { mutableStateOf(false) }
    // onShowXxxSrc → showSrcDialog 触发: 源码查看 (Pair<title, content>)
    var srcDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    // ---- 调试辅助函数 (对齐 app 端 BookSourceDebugActivity 私有方法) ----
    // 注意: Kotlin 局部函数不能前向引用, 故所有辅助函数定义在调用方 (LaunchedEffect / actions) 之前

    /**
     * 解析发现分类 (对齐 app 端 initExploreKinds): commonMain 完整版 [exploreKinds]
     * (含 JS 求值 + 磁盘缓存, desktop 已注册 QuickJsJsEngine 与 ExploreKindsCacheProvider)。
     */
    suspend fun initExploreKinds(source: BookSource) {
        try {
            val kinds = source.exploreKinds().filter { !it.url.isNullOrBlank() }
            exploreKinds = kinds
            kinds.firstOrNull()?.let {
                textFx = "${it.title}::${it.url}"
                if (it.title.startsWith("ERROR:")) {
                    logs.add(jvmGetString("debug_explore_error", it.url))
                    helpVisible = false
                }
            }
        } catch (e: NullPointerException) {
            logs.add(jvmGetString("debug_explore_json_error", e))
            helpVisible = false
        }
    }

    /** 启动调试 (对齐 app 端 startSearch: logs.clear + Debug.startDebug) */
    fun startSearch(key: String) {
        val source = bookSource
        if (source == null) {
            // 弹 AlertDialog (替换原 JOptionPane.showMessageDialog 同步阻塞,
            // 与 BookSourceScreen deleteSelectionTarget 模式一致; 末尾 AlertDialog 渲染分支读取 noSourceDialog)
            noSourceDialog = true
            return
        }
        logs.clear()
        loading = true
        Debug.startDebug(scope, source, key)
    }

    /** 对齐 SearchView.setQuery(text, submit) 语义 */
    fun setQuery(text: String, submit: Boolean) {
        query = text
        if (submit) {
            helpVisible = false
            clearFocusTick++
            startSearch(text.ifBlank { myLabel })
        }
    }

    /** 对齐 app 端 prefixAutoComplete: ++/-- 前缀自动补全 */
    fun prefixAutoComplete(prefix: String) {
        if (query.isBlank() || query.length <= 2) {
            setQuery(prefix, false)
        } else {
            if (!query.startsWith(prefix)) {
                setQuery("$prefix$query", true)
            } else {
                setQuery(query, true)
            }
        }
    }

    /**
     * 显示源码查看对话框 (替代 app 端 showDialogFragment(TextDialog("html", src)))。
     *
     * 把 title + content 写入 [srcDialog] state, 由末尾 AlertDialog 渲染分支显示
     * (替换原 javax.swing.JScrollPane + JTextArea; AlertDialog 内用 verticalScroll 处理长源码);
     * 源码为空时用 [noSourceCodeLabel] 占位 (复刻原 `src ?: "暂无源码"` 语义)。
     */
    fun showSrcDialog(title: String, src: String?) {
        srcDialog = title to (src ?: noSourceCodeLabel)
    }

    // 初始化: 加载书源 + 填充帮助面板 (对齐 app 端 onActivityCreated + initHelpView)
    LaunchedEffect(sourceUrl) {
        val source = AppDbProviders.get().bookSourceDao.getBookSource(sourceUrl)
        bookSource = source
        if (source == null) {
            logs.add(jvmGetString("debug_source_not_found", sourceUrl))
            helpVisible = false
            return@LaunchedEffect
        }
        // 取 searchRule.checkKeyWord 作为调试搜索示例 (对齐 app 端 initHelpView)
        source.searchRule.checkKeyWord?.let {
            if (it.isNotBlank()) {
                textMy = it
            }
        }
        initExploreKinds(source)
    }

    // ---- BookSourceDebugUiActions 实现 (对齐 app 端 BookSourceDebugActivity override) ----

    val actions = remember(onBackCallback) {
        object : BookSourceDebugUiActions {
            override fun onBack() = onBackCallback()

            override fun onQueryChange(text: String) {
                query = text
            }

            override fun onSubmitQuery() = setQuery(query, true)

            override fun onSearchFocusChanged(focused: Boolean) {
                helpVisible = focused
            }

            override fun onChipMyClick() = setQuery(textMy, true)

            override fun onChipSystemClick() = setQuery(systemLabel, true)

            override fun onChipFxClick() = setQuery(textFx, true)

            override fun onChipFxLongClick() {
                val kinds = exploreKinds
                if (kinds.isNotEmpty()) {
                    // 弹 AlertDialog 选择发现分类 (替换原 JOptionPane.showInputDialog 同步阻塞,
                    // 与 BookSourceScreen deleteSelectionTarget 模式一致;
                    // 末尾 AlertDialog 渲染分支读取 exploreKindPicker, 用户点击列表项后回调继续业务流程:
                    // 设 textFx + setQuery, 复刻原 selected = titles[index] 语义)
                    exploreKindPicker = true
                }
            }

            override fun onChipDetailClick() = setQuery(query, true)

            override fun onChipTocClick() = prefixAutoComplete("++")

            override fun onChipContentClick() = prefixAutoComplete("--")

            override fun onShowSearchSrc() {
                showSrcDialog(searchSrcLabel, searchSrc)
            }

            override fun onShowBookSrc() {
                showSrcDialog(bookSrcLabel, bookSrc)
            }

            override fun onShowTocSrc() {
                showSrcDialog(tocSrcLabel, tocSrc)
            }

            override fun onShowContentSrc() {
                showSrcDialog(contentSrcLabel, contentSrc)
            }

            override fun onShowReviewSrc() {
                showSrcDialog(reviewSrcLabel, reviewSrc)
            }

            override fun onRefreshExplore() {
                val source = bookSource ?: return
                scope.launch {
                    source.clearExploreKindsCache()
                    logs.clear()
                    helpVisible = true
                    initExploreKinds(source)
                }
            }

            override fun onShowHelp() {
                // 桌面端: 打开浏览器跳转书源调试帮助页 (app 端读 assets md)
                browseUrl("https://github.com/gedoor/legado/wiki/书源制作")
            }
        }
    }

    val state = BookSourceDebugUiState(
        logs = logs,
        query = query,
        helpVisible = helpVisible,
        loading = loading,
        textMy = textMy,
        textFx = textFx,
        clearFocusTick = clearFocusTick,
    )

    // 调用 shared 端 Screen
    io.legado.app.ui.book.source.debug.BookSourceDebugScreen(state, actions)

    // ---- 对话框渲染 (替换原 javax.swing.JOptionPane) ----

    // 1. 书源未获取到错误提示 (startSearch 触发; 替换原 JOptionPane.showMessageDialog WARNING_MESSAGE)
    if (noSourceDialog) {
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { noSourceDialog = false },
            title = debugErrorLabel,
            message = noSourceFoundLabel,
            okButton = AlertButton(okLabel),
        )
    }

    // 2. 选择发现分类 (onChipFxLongClick 触发; 替换原 JOptionPane.showInputDialog 列表选择,
    //    用户点击列表项 = 选中分类, 复刻原 selected = titles[index] 后 setQuery 语义;
    //    content 槽用 Column 列出 kinds, 每项 TextButton 点击后回填 textFx + setQuery)
    if (exploreKindPicker) {
        val kinds = exploreKinds
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { exploreKindPicker = false },
            title = selectExploreKindLabel,
            cancelButton = AlertButton(cancelLabel),
        ) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(selectExploreLabel)
                Spacer(Modifier.height(8.dp))
                kinds.forEachIndexed { index, kind ->
                    TextButton(onClick = {
                        exploreKindPicker = false
                        val explore = kinds[index]
                        textFx = "${explore.title}::${explore.url}"
                        setQuery(textFx, true)
                    }) { Text(kind.title) }
                }
            }
        }
    }

    // 3. 源码查看 (onShowXxxSrc → showSrcDialog 触发; 替换原 JOptionPane + JScrollPane + JTextArea,
    //    Pair<title, content> 渲染, content 走 message 槽内置滚动处理长源码)
    srcDialog?.let { (title, content) ->
        AppAlertDialog(
            widthFraction = 0.8f,
            onDismissRequest = { srcDialog = null },
            title = title,
            message = content,
            okButton = AlertButton(okLabel),
        )
    }
}
