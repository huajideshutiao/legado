package io.legado.app.help

import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter
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
