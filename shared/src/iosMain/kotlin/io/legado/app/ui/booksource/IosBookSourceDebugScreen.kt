package io.legado.app.ui.booksource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.openURL
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.Debug
import io.legado.app.ui.book.source.debug.BookSourceDebugScreen as SharedBookSourceDebugScreen
import io.legado.app.ui.book.source.debug.BookSourceDebugUiActions
import io.legado.app.ui.book.source.debug.BookSourceDebugUiState
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import kotlinx.coroutines.launch

/**
 * iOS 端书源调试 Screen 入口 (包装 shared/sharedUiMain 的 [SharedBookSourceDebugScreen])。
 *
 * 对照 desktop `BookSourceDebugScreen.kt` 的包装模式, iOS 端在 [IosBookSourceScreen] 的
 * onDebug 回调中用 Dialog 全屏展示本入口。Provider 由 MainViewController 全局注入,
 * 无需在此重复包装。
 *
 * 调试逻辑 (Debug.Callback / startSearch / initExploreKinds) 与 desktop / app 端一致,
 * 仅做 iOS 平台适配 (openURL 替代 browseUrl, rememberString 替代 jvmGetString)。
 *
 * @param sourceUrl 书源 URL (对应 app 端 intent extra "key")
 * @param onBack 返回回调
 */
@Composable
fun IosBookSourceDebugScreen(
    sourceUrl: String,
    onBack: () -> Unit,
) {
    BookSourceDebugContent(sourceUrl, onBack)
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
    var textFx by remember { mutableStateOf("系统::http://xxx") }
    var clearFocusTick by remember { mutableStateOf(0) }

    // 书源 + 源码缓存
    var bookSource by remember { mutableStateOf<BookSource?>(null) }
    var exploreKinds by remember { mutableStateOf<List<ExploreKind>>(emptyList()) }
    var searchSrc by remember { mutableStateOf<String?>(null) }
    var bookSrc by remember { mutableStateOf<String?>(null) }
    var tocSrc by remember { mutableStateOf<String?>(null) }
    var contentSrc by remember { mutableStateOf<String?>(null) }
    var reviewSrc by remember { mutableStateOf<String?>(null) }

    // 注册 Debug.Callback 接收日志 (对齐 app 端 BookSourceDebugModel.observe)
    DisposableEffect(Unit) {
        Debug.callback = object : Debug.Callback {
            override fun printLog(state: Int, msg: String) {
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

    // AlertDialog 文案
    val okLabel = rememberString("ok")
    val cancelLabel = rememberString("cancel")
    val debugErrorLabel = rememberString("debug_error")
    val noSourceFoundLabel = rememberString("no_source_found")
    val selectExploreLabel = rememberString("select_explore")
    val selectExploreKindLabel = rememberString("select_explore_kind")
    val noSourceCodeLabel = rememberString("no_source_code")
    val searchSrcLabel = rememberString("search_src")
    val bookSrcLabel = rememberString("book_src")
    val tocSrcLabel = rememberString("toc_src")
    val contentSrcLabel = rememberString("content_src")
    val reviewSrcLabel = rememberString("review_src")
    val systemLabel = rememberString("system")
    val debugSourceNotFoundLabel = rememberString("debug_source_not_found")
    val debugExploreErrorLabel = rememberString("debug_explore_error")
    val debugExploreJsonErrorLabel = rememberString("debug_explore_json_error")

    // AlertDialog 显示状态
    var noSourceDialog by remember { mutableStateOf(false) }
    var exploreKindPicker by remember { mutableStateOf(false) }
    var srcDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    // ---- 调试辅助函数 ----

    // 解析发现分类 (对齐 app 端 initExploreKinds, 简化: 仅解析 JSON 数组格式)
    suspend fun initExploreKinds(source: BookSource) {
        try {
            val json = source.exploreKindsJson()
            val kinds = if (json.isJsonArray()) {
                runCatching {
                    GSON.fromJsonArray<ExploreKind>(json).getOrDefault(emptyList())
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }.filter { !it.url.isNullOrBlank() }
            exploreKinds = kinds
            kinds.firstOrNull()?.let {
                textFx = "${it.title}::${it.url}"
                if (it.title.startsWith("ERROR:")) {
                    logs.add("$debugExploreErrorLabel: ${it.url}")
                    helpVisible = false
                }
            }
        } catch (e: NullPointerException) {
            logs.add("$debugExploreJsonErrorLabel: $e")
            helpVisible = false
        }
    }

    // 启动调试 (对齐 app 端 startSearch)
    fun startSearch(key: String) {
        val source = bookSource
        if (source == null) {
            noSourceDialog = true
            return
        }
        logs.clear()
        loading = true
        Debug.startDebug(scope, source, key)
    }

    // 对齐 SearchView.setQuery(text, submit) 语义
    fun setQuery(text: String, submit: Boolean) {
        query = text
        if (submit) {
            helpVisible = false
            clearFocusTick++
            startSearch(text.ifBlank { myLabel })
        }
    }

    // 对齐 app 端 prefixAutoComplete: ++/-- 前缀自动补全
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

    // 显示源码查看对话框
    fun showSrcDialog(title: String, src: String?) {
        srcDialog = title to (src ?: noSourceCodeLabel)
    }

    // 初始化: 加载书源 + 填充帮助面板
    LaunchedEffect(sourceUrl) {
        val source = AppDbProviders.get().bookSourceDao.getBookSource(sourceUrl)
        bookSource = source
        if (source == null) {
            logs.add("$debugSourceNotFoundLabel: $sourceUrl")
            helpVisible = false
            return@LaunchedEffect
        }
        source.searchRule.checkKeyWord?.let {
            if (it.isNotBlank()) {
                textMy = it
            }
        }
        initExploreKinds(source)
    }

    // ---- BookSourceDebugUiActions 实现 ----
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
                openURL("https://github.com/gedoor/legado/wiki/书源制作")
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

    SharedBookSourceDebugScreen(state, actions) { text, linkColor ->
        linkifyText(text, linkColor)
    }

    // ---- AlertDialog 渲染 ----

    // 书源未获取到错误提示
    if (noSourceDialog) {
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { noSourceDialog = false },
            title = { Text(debugErrorLabel) },
            text = { Text(noSourceFoundLabel) },
            confirmButton = {
                TextButton(onClick = { noSourceDialog = false }) {
                    Text(okLabel)
                }
            },
        )
    }

    // 选择发现分类
    if (exploreKindPicker) {
        val kinds = exploreKinds
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { exploreKindPicker = false },
            title = { Text(selectExploreKindLabel) },
            text = {
                Column {
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
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { exploreKindPicker = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // 源码查看
    srcDialog?.let { (title, content) ->
        AlertDialog(
            modifier = Modifier.fillMaxWidth(0.8f),
            onDismissRequest = { srcDialog = null },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(content)
                }
            },
            confirmButton = {
                TextButton(onClick = { srcDialog = null }) {
                    Text(okLabel)
                }
            },
        )
    }
}

// 网址自动链接 (对齐 desktop linkifyText, 正则匹配 URL 转 AnnotatedString)
private fun linkifyText(text: String, linkColor: Color): AnnotatedString {
    val urlRegex = Regex("https?://\\S+")
    return buildAnnotatedString {
        var last = 0
        urlRegex.findAll(text).forEach { match ->
            append(text.substring(last, match.range.first))
            val url = match.value
            val href = if (url.contains("://")) url else "http://$url"
            withLink(
                LinkAnnotation.Url(
                    href,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                )
            ) {
                append(url)
            }
            last = match.range.last + 1
        }
        append(text.substring(last))
    }
}
