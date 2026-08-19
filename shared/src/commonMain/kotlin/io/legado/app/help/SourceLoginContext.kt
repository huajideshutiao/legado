package io.legado.app.help

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.utils.encodeStringMap

/**
 * 登录 Overlay 跨组合上下文（对照原版 `IntentData.nowSource/nowBook/nowChapter`）。
 *
 * 登录 Overlay 的 payload 只能携带字符串（[sourceLoginOverlayPayload] 编码 {url, dataKey}），而
 * [BaseSource] 是多态类型（BookSource/HttpTTS），HttpTTS 更不在 bookSourceDao 里，
 * 按 url 查库拿不到；登录 JS 需要的 book/chapter 也无法塞进 payload。故沿用原版做法：
 * 对象存 [IntentData]，payload 只带 key。
 */
data class SourceLoginContext(
    val source: BaseSource,
    val book: BaseBook? = null,
    val chapter: BookChapter? = null,
) {
    companion object {
        /** 存入 [IntentData] 并返回 Overlay payload 用的 key */
        fun put(
            source: BaseSource,
            book: BaseBook? = null,
            chapter: BookChapter? = null,
        ): String = IntentData.put(SourceLoginContext(source, book, chapter))

        /** 取出并从 [IntentData] 移除（one-shot，与原版 `IntentData.get` 一致） */
        fun take(key: String?): SourceLoginContext? = IntentData.get(key)
    }
}

/**
 * 构造登录 Overlay (key="sourceLogin") 的 payload: {url, dataKey}。
 *
 * url 供 [io.legado.app.ui.root.SourceLoginOverlayContent] 在 dataKey 失效（进程重建等）时按库
 * 回查源；dataKey 指向 [SourceLoginContext] 内存上下文（源对象 + book/chapter），仅 URL 的入口
 * （深链/列表页）可不传。纯 URL 编码的 JSON map，与 Overlay payload 字符串契约一致。
 */
fun sourceLoginOverlayPayload(sourceUrl: String, dataKey: String? = null): String =
    encodeStringMap(
        buildMap {
            put("url", sourceUrl)
            if (dataKey != null) put("dataKey", dataKey)
        }
    )

/**
 * 书源登录统一入口 (JS showLoginDialog / 各 UI 菜单登录共用)。
 *
 * 对照原版 `BaseSource.showLoginDialog` 的两条分支: URL 登录 (loginUi 为空且 loginUrl 非空)
 * 直开全屏 WebView ([PlatformCapabilities.openLoginWebView], 移动端 = AppRoute.WebView
 * isLogin=true, 桌面端 = 带 isLogin 工具栏的独立浏览器窗口), 表单登录 (loginUi 非空) 弹
 * sourceLogin Overlay (由 [io.legado.app.ui.root.SourceLoginOverlayContent] 分发), 两者皆空
 * 则什么都不做 —— 2026-08-07 用户拍板: 去掉登录中转界面; 2026-08-19: 去掉对话框外壳。
 *
 * [source] 为空的入口 (发现/书源列表菜单、深链只有 url) 先查库解析源再分发, 对照原版
 * `ExploreAdapter` 的 `getBookSource()?.showLoginDialog()`: 分支判定必须在拿到源之后,
 * 否则 URL 登录会先弹再关 Overlay, 闪一下对话框外壳。
 *
 * @param sourceUrl 源地址 (查库/深链回退用, 对照原版 showLoginDialog 的 key)
 * @param source 内存中的源对象 (为空时按 [sourceUrl] 查库)
 * @param book 登录 JS 的 book 绑定 (对照原版 IntentData.nowBook)
 * @param chapter 登录 JS 的 chapter 绑定 (对照原版 IntentData.nowChapter)
 */
fun showSourceLogin(
    sourceUrl: String,
    source: BaseSource? = null,
    book: BaseBook? = null,
    chapter: BookChapter? = null,
) {
    if (source != null) {
        dispatchSourceLogin(sourceUrl, source, book, chapter)
        return
    }
    if (sourceUrl.isBlank()) return
    Coroutine.async { loadSourceForLogin(sourceUrl) }.onSuccess { resolved ->
        if (resolved == null) {
            Toasters.get().toast("未找到书源")
        } else {
            dispatchSourceLogin(sourceUrl, resolved, book, chapter)
        }
    }
}

/** 源已解析后的分支分发 (对照原版 `BaseSource.showLoginDialog` 的 if/else)。 */
private fun dispatchSourceLogin(
    sourceUrl: String,
    source: BaseSource,
    book: BaseBook?,
    chapter: BookChapter?,
) {
    if (source.loginUi.isNullOrEmpty()) {
        // URL 登录: 原版 startActivity<WebViewActivity> { url/title/sourceName/sourceOrigin/
        // sourceType/isLogin }, 标题栏文案与源标识全程带着走
        val loginUrl = source.loginUrl
        if (loginUrl.isNullOrBlank()) return
        PlatformCapabilityProviders.getOrNull()?.openLoginWebView(
            url = loginUrl,
            sourceKey = source.getKey(),
            sourceName = source.getTag(),
            sourceType = source.getSourceType(),
        )
        return
    }
    // 表单登录: 原版 IntentData.source = this + showDialogFragment<SourceLoginDialog>
    val navigator = AppNavigatorProviders.getOrNull() ?: return
    navigator.showOverlay(
        AppOverlay.Dialog(
            key = "sourceLogin",
            payload = sourceLoginOverlayPayload(
                sourceUrl,
                SourceLoginContext.put(source, book, chapter),
            ),
            // 登录对话框被 push 路由 (登录 JS startBrowser → WebView) 盖住时保留 Overlay,
            // 由 SourceLoginOverlayContent 自管挂起/恢复 (对照原版 DialogFragment 被新
            // Activity 盖住仍存活, 返回后对话框原样恢复)
            keepOnPush = true,
        )
    )
}

/** 按 url 查库解析登录源 (HttpTTS key 形如 "httpTts:$id", 不在 bookSourceDao)。 */
internal suspend fun loadSourceForLogin(sourceUrl: String): BaseSource? {
    val db = AppDbProviders.get()
    if (sourceUrl.startsWith(HTTP_TTS_KEY_PREFIX)) {
        val id = sourceUrl.removePrefix(HTTP_TTS_KEY_PREFIX).toLongOrNull() ?: return null
        return db.httpTTSDao.get(id)
    }
    return db.bookSourceDao.getBookSource(sourceUrl)
}

private const val HTTP_TTS_KEY_PREFIX = "httpTts:"
