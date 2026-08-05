package io.legado.app.help

import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter
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
 * URL 登录 (loginUi 为空且 loginUrl 非空) 时先问平台是否直接开登录窗口
 * ([PlatformCapabilities.openLoginWebView], 桌面端 = 带 isLogin 工具栏的独立浏览器窗口),
 * 平台已处理则不再弹 Overlay 对话框 —— 2026-08-07 用户拍板: 去掉登录中转界面。
 * 表单登录 (loginUi 非空) 与平台未直接处理的场景保持原行为: 弹 sourceLogin Overlay,
 * 由 [io.legado.app.ui.root.SourceLoginOverlayContent] 统一分发。
 *
 * @param sourceUrl 源地址 (查库/深链回退用, 对照原版 showLoginDialog 的 key)
 * @param source 内存中的源对象 (非空时短路判断与 dataKey 复用; null 时只弹 Overlay 按库回查)
 * @param book 登录 JS 的 book 绑定 (对照原版 IntentData.nowBook)
 * @param chapter 登录 JS 的 chapter 绑定 (对照原版 IntentData.nowChapter)
 */
fun showSourceLogin(
    sourceUrl: String,
    source: BaseSource? = null,
    book: BaseBook? = null,
    chapter: BookChapter? = null,
) {
    // URL 登录 + 平台支持直开登录窗口 → 不弹对话框外壳 (桌面端零闪烁)
    if (source != null && source.loginUi.isNullOrEmpty() && !source.loginUrl.isNullOrBlank()) {
        val platform = PlatformCapabilityProviders.getOrNull()
        if (platform?.openLoginWebView(source.loginUrl!!, source.getKey()) == true) return
    }
    val navigator = AppNavigatorProviders.getOrNull() ?: return
    if (source == null && sourceUrl.isBlank()) return
    val dataKey = source?.let { SourceLoginContext.put(it, book, chapter) }
    navigator.showOverlay(
        AppOverlay.Dialog(
            key = "sourceLogin",
            payload = sourceLoginOverlayPayload(sourceUrl, dataKey),
        )
    )
}
