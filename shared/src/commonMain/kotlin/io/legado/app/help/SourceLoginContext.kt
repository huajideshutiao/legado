package io.legado.app.help

import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter

/**
 * 登录页跨路由上下文（对照原版 `IntentData.nowSource/nowBook/nowChapter`）。
 *
 * [AppRoute.Login][io.legado.app.ui.root.AppRoute.Login] 只能携带可序列化字段，而
 * [BaseSource] 是多态类型（BookSource/HttpTTS），HttpTTS 更不在 bookSourceDao 里，
 * 按 url 查库拿不到；登录 JS 需要的 book/chapter 也无法塞进路由。故沿用原版做法：
 * 对象存 [IntentData]，路由只带 key。
 */
data class SourceLoginContext(
    val source: BaseSource,
    val book: BaseBook? = null,
    val chapter: BookChapter? = null,
) {
    companion object {
        /** 存入 [IntentData] 并返回路由用的 key */
        fun put(
            source: BaseSource,
            book: BaseBook? = null,
            chapter: BookChapter? = null,
        ): String = IntentData.put(SourceLoginContext(source, book, chapter))

        /** 取出并从 [IntentData] 移除（one-shot，与原版 `IntentData.get` 一致） */
        fun take(key: String?): SourceLoginContext? = IntentData.get(key)
    }
}
