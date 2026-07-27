package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import kotlinx.coroutines.runBlocking

/**
 * iOS/鸿蒙 (Native target) 共用 [ContentProcessorAccessor] 实现:
 * 复用 commonMain 的 [ContentProcessorShared]。
 *
 * # 共用原因
 * iOS 与鸿蒙两端 ContentProcessorAccessor 主体实现完全一致 (均复用 commonMain 的
 * ContentProcessorShared, 通过 [DefaultContentProcessorDeps] 注入平台专属依赖,
 * processorCache 缓存策略相同), 仅类名 (IosContentProcessorAccessor /
 * OhosContentProcessorAccessor) 与注册函数不同, 故下沉到 nativeMain 共用,
 * 平台源集用 typealias 别名 + 各自 register 函数。
 *
 * 与桌面端 [io.legado.desktop.model.webBook.DesktopContentProcessorAccessor] 行为对齐,
 * 完整执行 ContentProcessorShared 提供的核心逻辑 (替换规则 / 简繁 / 段落重排 / 去重复标题),
 * 平台专属依赖通过 [DefaultContentProcessorDeps] 注入:
 * - `isAndroid8` = false (非 Android 端)
 * - `toastOnUi` no-op (Native 端无 UI toast 桥接到 accessor, 错误日志走 AppLog.put)
 * - `getChapterFiles` 委托 [BookStorageProviders.get].getChapterFiles
 *   (Native 端 NativeBookStorage 已实现, 行为与 Android BookHelp.getChapterFiles 等价)
 *
 * # 缓存策略
 *
 * 用普通 [MutableMap] 按 `bookName + bookOrigin` 缓存 [ContentProcessorShared] 实例,
 * 与 app 端 WeakReference 缓存行为对齐 (避免每次 new + 重新查 replaceRuleDao)。
 * Native 端默认单线程 (iOS main actor / 鸿蒙 main ability), 无需 ConcurrentHashMap;
 * 后续如多线程访问再补冻结/synchronized。
 *
 * 模式参考 [NativeBookHelpAccessor] / desktop `DesktopContentProcessorAccessor`。
 */
class NativeContentProcessorAccessor : ContentProcessorAccessor {

    private val deps = DefaultContentProcessorDeps()

    // ContentProcessorShared 缓存: 按 bookName + bookOrigin 索引, 与 app 端 WeakReference 缓存对齐
    // Native 端单线程访问, 用普通 MutableMap; 多线程访问需后续补冻结/synchronized
    private val processorCache = mutableMapOf<String, ContentProcessorShared>()

    private fun getProcessor(book: Book): ContentProcessorShared {
        val key = book.name + book.origin
        return processorCache.getOrPut(key) {
            ContentProcessorShared(book.name, book.origin, deps).also { p ->
                // 初始化: 同步刷新替换规则 + 去重标题缓存 (与 app 端 ContentProcessor.init 行为一致)
                runBlocking {
                    p.upReplaceRules()
                    p.upRemoveSameTitle()
                }
            }
        }
    }

    override fun getTitleReplaceRules(book: Book): List<ReplaceRule> =
        getProcessor(book).getTitleReplaceRules()

    override fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        useReplace: Boolean
    ): CharSequence = getProcessor(book).getContent(
        book, chapter, content, useReplace = useReplace
    ).toString()

    // BookController.getBookContent web API 下沉用: 显式传 includeTitle (原 app 端 includeTitle = false)
    override fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean,
        useReplace: Boolean
    ): CharSequence = getProcessor(book).getContent(
        book, chapter, content, includeTitle = includeTitle, useReplace = useReplace
    ).toString()

    override fun upReplaceRules() {
        // 刷新所有缓存实例的替换规则 (与 app 端 ContentProcessor.upReplaceRules 静态方法一致)
        processorCache.values.forEach {
            runBlocking { it.upReplaceRules() }
        }
    }
}

/**
 * 注册 [NativeContentProcessorAccessor] 到 [ContentProcessorProviders] (iOS/鸿蒙共用)。
 *
 * 前置依赖: [AppDbProviders] / [BookStorageProviders] 已注册
 * (ContentProcessorShared 通过 DefaultContentProcessorDeps 取 replaceRuleDao/bookDao/chapterFiles)。
 */
fun registerNativeContentProcessorAccessor() {
    ContentProcessorProviders.register(NativeContentProcessorAccessor())
}
