package io.legado.app.help.book

import android.os.Build
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.lang.ref.WeakReference

/**
 * ContentProcessor 安卓端入口 (WeakReference 缓存层)。
 *
 * 核心正文处理逻辑 (替换规则 / 简繁 / 段落重排 / 去重复标题) 已下沉到
 * [ContentProcessorShared] (shared commonMain), 本类仅保留:
 *
 * - WeakReference 缓存 (WeakReference 是 JVM 专属, commonMain 不可用):
 *   避免长时间持有 ContentProcessor 实例 + 内部规则缓存
 * - companion [upReplaceRules] 静态刷新所有缓存实例 (后台替换规则变更后)
 * - [AndroidContentProcessorDeps] 注入 Android 专属依赖 (Build.VERSION / appCtx / BookHelp)
 *
 * API 签名完全不变, app 端调用方 (ReadBookActivity / BookHelp.setRemoveSameTitle /
 * ExportBookService / ChangeBookSourceViewModel 等) 零改动。
 *
 * # ContentProcessorShared 行为对齐
 *
 * 内部 `titleReplaceRules` / `contentReplaceRules` 改用 `@Volatile var` + 不可变 List
 * (commonMain 无 CopyOnWriteArrayList), 行为等价: 每次 [upReplaceRules] 整体替换 List,
 * [getContent] 遍历时拿到 snapshot, 不会被并发 [upReplaceRules] 干扰 (与原 COW 迭代语义一致)。
 *
 * `removeSameTitleCache` 改为 `MutableSet` 视图 (代理 [ContentProcessorShared.removeSameTitleCache]),
 * BookHelp.setRemoveSameTitle 调用 add/remove 仍生效。
 */
class ContentProcessor private constructor(
    private val bookName: String,
    private val bookOrigin: String
) {

    private val shared = ContentProcessorShared(
        bookName, bookOrigin, AndroidContentProcessorDeps
    )

    /** 去重标题缓存 (代理 ContentProcessorShared.removeSameTitleCache)。 */
    val removeSameTitleCache: MutableSet<String> get() = shared.removeSameTitleCache

    init {
        runBlocking { shared.upReplaceRules() }
        runBlocking { shared.upRemoveSameTitle() }
    }

    /** 刷新替换规则缓存 (实例方法, 委托 [ContentProcessorShared.upReplaceRules])。 */
    fun upReplaceRules() = runBlocking { shared.upReplaceRules() }

    fun getTitleReplaceRules() = shared.getTitleReplaceRules()

    @Suppress("MemberVisibilityCanBePrivate")
    fun getContentReplaceRules() = shared.getContentReplaceRules()

    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true
    ) = shared.getContent(
        book, chapter, content, includeTitle, useReplace, chineseConvert, reSegment
    )

    companion object {
        private val processors = hashMapOf<String, WeakReference<ContentProcessor>>()

        fun get(book: Book) = get(book.name, book.origin)

        fun get(bookName: String, bookOrigin: String): ContentProcessor {
            val processorWr = processors[bookName + bookOrigin]
            var processor: ContentProcessor? = processorWr?.get()
            if (processor == null) {
                processor = ContentProcessor(bookName, bookOrigin)
                processors[bookName + bookOrigin] = WeakReference(processor)
            }
            return processor
        }

        fun upReplaceRules() {
            processors.forEach {
                it.value.get()?.upReplaceRules()
            }
        }

    }

}

/**
 * Android 端 [ContentProcessorDeps] 实现: 桥接 Build / appCtx / BookHelp 三处 Android API。
 *
 * - [isAndroid8]: `Build.VERSION.SDK_INT in 26..27`, 影响getContent末尾 \u00A0 → 空格替换
 * - [toastOnUi]: `appCtx.toastOnUi(msg)`, UI Toast 提示 (替换规则出错时调用)
 * - [getChapterFiles]: `BookHelp.getChapterFiles(book)`, 列出已缓存章节文件名
 *
 * Desktop / iOS / 鸿蒙 端使用 commonMain 的 [DefaultContentProcessorDeps]
 * (isAndroid8=false / toastOnUi=no-op / getChapterFiles=BookStorageProviders)。
 */
private object AndroidContentProcessorDeps : ContentProcessorDeps {
    override val isAndroid8: Boolean = Build.VERSION.SDK_INT in 26..27

    override fun toastOnUi(msg: String) = appCtx.toastOnUi(msg)

    override fun getChapterFiles(book: Book): Set<String> =
        BookHelp.getChapterFiles(book)
}
