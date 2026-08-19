package io.legado.app.ui.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.SourceLoginContext
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.book.source.SourceLoginDialog
import io.legado.app.ui.book.source.SourceLoginFormState
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.decodeStringMapOrNull
import legado.shared.generated.resources.Res
import legado.shared.generated.resources.loading
import org.jetbrains.compose.resources.stringResource

/**
 * 书源登录 Overlay 对话框 (key="sourceLogin")。
 *
 * 对照原版 `BaseSource.showLoginDialog` 的分支分发:
 * - loginUi 非空 -> [SourceLoginDialog] 表单登录 (book/chapter 作为登录 JS 上下文);
 * - loginUi 空 (URL 登录) -> 问平台直开登录 WebView ([PlatformCapabilities.openLoginWebView],
 *   移动端默认推全屏 [AppRoute.WebView] isLogin=true 路由, 桌面端开独立浏览器窗口),
 *   平台处理即关对话框 —— 登录不再有对话框外壳 (2026-08-19 用户拍板)。
 *
 * payload 格式: [io.legado.app.help.sourceLoginOverlayPayload] 编码的 {url, dataKey}
 * (dataKey 指向 [SourceLoginContext], 对照原版 IntentData; 仅 URL 的入口 (深链/列表页)
 * 只有 url, 缺失时按 url 查库)。
 */
@Composable
internal fun SourceLoginOverlayContent(overlay: AppOverlay.Dialog, navigator: AppNavigator) {
    // ===== 挂起/恢复 (对照原版 DialogFragment 被新 Activity 全屏盖住仍存活) =====
    // 表单登录 JS startBrowser → push AppRoute.WebView 时, 单页导航下路由渲染在 Overlay 之下,
    // 不隐藏对话框窗口会遮住 WebView; 故路由栈被 push 盖住时挂起 (不渲染窗口, 状态保留),
    // pop 回原栈时恢复, 表单数据不丢。挂起监听必须在所有 return 分支之前注册,
    // 保证组合存活期间持续捕获恢复时机。
    val initialStackSize = remember { navigator.backStack.value.size }
    var suspended by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        navigator.backStack.collect { entries ->
            val newSuspended = entries.size > initialStackSize
            if (newSuspended != suspended) {
                suspended = newSuspended
                navigator.setOverlaySuspended(overlay.key, newSuspended)
            }
        }
    }
    // Overlay 关闭 (dismiss / popTo / resetRoot 清栈) 时清理挂起标记
    DisposableEffect(Unit) {
        onDispose { navigator.setOverlaySuspended(overlay.key, false) }
    }

    val params = remember(overlay.payload) { parseSourceLoginPayload(overlay.payload) }
    // 无 url 且无 dataKey (payload 完全无法解析) 时直接关闭, 不落入空对话框
    if (params.sourceUrl.isBlank() && params.dataKey == null) {
        LaunchedEffect(Unit) { navigator.dismissOverlay(overlay.key) }
        return
    }

    // ===== 源解析 (对照原版 IntentData.nowSource 语义) =====
    // dataKey 内存上下文优先 (HttpTTS 不在 bookSourceDao, 只能走上下文), 其次按 url 查库;
    // inited 守卫保证挂起恢复后不重复解析 (SourceLoginContext.take 是 one-shot)。
    var source by remember { mutableStateOf<BaseSource?>(null) }
    var book by remember { mutableStateOf<BaseBook?>(null) }
    var chapter by remember { mutableStateOf<BookChapter?>(null) }
    var loading by remember { mutableStateOf(true) }
    var inited by remember { mutableStateOf(false) }
    LaunchedEffect(params.sourceUrl, params.dataKey) {
        if (inited) return@LaunchedEffect
        inited = true
        val context = SourceLoginContext.take(params.dataKey)
        // 查库失败 (DB 未就绪等) 按源缺失处理: loading 必须落 false,
        // 由下方"无内容可登录"分支关闭对话框, 不能卡在加载态
        val src = runCatching { context?.source ?: loadSourceForLogin(params.sourceUrl) }.getOrNull()
        source = src
        book = context?.book
        chapter = context?.chapter
        loading = false
    }

    // 表单登录状态 (登录数据/行) 由本组合持有: 挂起恢复后 SourceLoginDialog 重建时
    // 保留用户已输入的值, 跳过 rebuild 清空 (对照原版 DialogFragment 存活语义)
    val loginFormState = remember { SourceLoginFormState() }

    // loginUi 非空走表单登录 (对照原版 BaseSource.showLoginDialog 的 SourceLoginDialog 分支),
    // book/chapter 作为登录 JS 上下文透传
    val formSource = source?.takeIf { !it.loginUi.isNullOrEmpty() }
    // URL 登录: 源无 loginUi 时, 源对象缺失则直接用入口 url
    val loginUrl = source?.loginUrl ?: params.sourceUrl
    val urlLogin = !loading && formSource == null && loginUrl.isNotBlank()
    var urlLoginHandled by remember { mutableStateOf(false) }

    // ===== 挂起: 不渲染 UI 窗口 (新路由可见) =====
    // 注意: 挂起 return 必须位于全部状态声明之后 —— return 之前已声明的节点
    // (源解析状态 / loginFormState / 信号收集) 在挂起期间保持存活, 恢复后原样呈现;
    // return 之后的 UI 节点 (EditDialogHost/表单) 挂起时移出组合, 恢复时重建。
    if (suspended) return

    // 源解析完成但拿不到可登录内容 (源不在库 / loginUrl、loginUi 双空):
    // 对照原版 showLoginDialog 双空直接 return 的语义, 关闭即可, 不落入空白对话框。
    if (!loading && formSource == null && loginUrl.isBlank()) {
        LaunchedEffect(Unit) {
            if (source == null) {
                Toasters.get().toast("未找到书源")
            }
            navigator.dismissOverlay(overlay.key)
        }
        return
    }

    // URL 登录 (loginUi 为空, 对照原版 BaseSource.showLoginDialog 的 WebViewActivity 分支):
    // 问平台直开登录 WebView —— 移动端默认实现推全屏 AppRoute.WebView (isLogin=true),
    // 桌面端开独立浏览器窗口; 平台已处理即关对话框, 不再内嵌渲染登录页。
    // 注意: 平台结果未定前只渲染占位, 不渲染任何登录内容。
    if (urlLogin) {
        LaunchedEffect(Unit) {
            if (urlLoginHandled) return@LaunchedEffect
            urlLoginHandled = true
            PlatformCapabilityProviders.getOrNull()
                ?.openLoginWebView(loginUrl, source?.getKey().orEmpty())
            navigator.dismissOverlay(overlay.key)
        }
    }

    // 统一居中对话框外壳: 加载占位 / 表单登录 共用同一
    // EditDialogHost (AppDialog + appDialogSize 居中卡片), 全端"表单登录=对话框"。
    EditDialogHost(onDismiss = { navigator.dismissOverlay(overlay.key) }) {
        when {
            // 源还没解析出来 / URL 登录等待平台开窗: 对话框内加载占位,
            // 先不建任何登录内容 (URL 登录平台开窗后即关闭)
            loading || urlLogin -> LoginLoadingPlaceholder()

            // 表单登录: 对照原版 BaseSource.showLoginDialog 的
            // showDialogFragment<SourceLoginDialog> 分支。
            else -> SourceLoginDialog(
                source = formSource!!,
                onDismiss = { navigator.dismissOverlay(overlay.key) },
                onOpenUrl = { PlatformCapabilityProviders.getOrNull()?.openExternalUrl(it) },
                book = book,
                chapter = chapter,
                formState = loginFormState,
            )
        }
    }
}

/** 源解析期间对话框内的加载占位 (避免整窗闪白 / 表单源闪空内容)。 */
@Composable
private fun LoginLoadingPlaceholder() {
    val loadingText = stringResource(Res.string.loading)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = AppTheme.colors.accent,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(loadingText, color = AppTheme.colors.secondaryText)
    }
}

/** 按 url 查库解析登录源 (HttpTTS key 形如 "httpTts:$id", 不在 bookSourceDao)。 */
private suspend fun loadSourceForLogin(sourceUrl: String): BaseSource? {
    val db = AppDbProviders.get()
    if (sourceUrl.startsWith(HTTP_TTS_KEY_PREFIX)) {
        val id = sourceUrl.removePrefix(HTTP_TTS_KEY_PREFIX).toLongOrNull() ?: return null
        return db.httpTTSDao.get(id)
    }
    return db.bookSourceDao.getBookSource(sourceUrl)
}

private const val HTTP_TTS_KEY_PREFIX = "httpTts:"

private data class SourceLoginPayload(val sourceUrl: String, val dataKey: String?)

private fun parseSourceLoginPayload(payload: String?): SourceLoginPayload {
    val map = decodeStringMapOrNull(payload)
    if (map != null && map.containsKey("url")) {
        return SourceLoginPayload(map["url"].orEmpty(), map["dataKey"])
    }
    // 旧格式兜底 (历史快照: 整个 payload 即 dataKey): 源对象只能靠上下文, 取不到即关闭
    return SourceLoginPayload("", payload)
}
