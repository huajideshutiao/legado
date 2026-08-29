package io.legado.desktop.model.webBook

import io.legado.app.api.controller.ImageControllerProviders
import io.legado.app.api.controller.ReadBookStateProviders
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.IntentData
import io.legado.app.help.IntentDataAccessor
import io.legado.app.help.IntentDataProviders
import io.legado.app.help.book.ContentProcessorAccessor
import io.legado.app.help.book.ContentProcessorProviders
import io.legado.app.help.book.ContentProcessorShared
import io.legado.app.help.book.DefaultContentProcessorDeps
import io.legado.app.model.ActiveReadBookStateProvider
import io.legado.app.model.analyzeRule.registerDesktopAnalyzeRuleFactory
import io.legado.app.model.webBook.BookInfoRefresher
import io.legado.app.model.webBook.BookInfoRefreshers
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.RegexReplacerImpl
import io.legado.app.utils.RegexReplacers
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * webBook 编排层下沉的桌面端 provider 注册入口。
 *
 * 对应 app 端 `registerAndroidWebBookProviders` 中尚未在桌面端 Main.kt 注册的 4 项:
 * - [BookInfoRefreshers]: 桥接 [WebBook.getBookInfoAwait] (已下沉 shared commonMain, 可直接调用)
 * - [IntentDataProviders]: 桥接 shared [IntentData] 单例 (跨调用临时数据容器)
 * - [ContentProcessorProviders]: 复用 commonMain 的 [ContentProcessorShared] (替换规则 / 简繁 / 段落重排 / 去重标题)
 * - [RegexReplacers]: 复用 shared jvmAndAndroidMain 的 [RegexReplacerImpl] (超时检测 + @js: 求值)
 *
 * 已注册项 (Main.kt 中已注册): AppDbProviders / AppConfigProviders / BookHelpProviders /
 * SourceHelpAccessors / ThemeConfigProviders / AppDatabaseProviders / OkHttpClientProviders。
 *
 * 注册时机: desktop main 入口, 在 AppDbProviders / OkHttpClientProviders 注册之后
 * (本文件 ContentProcessorAccessor 依赖 AppDbProviders 读 replaceRuleDao)。
 */
fun registerDesktopWebBookProviders() {
    // shared webBook 编排层创建 AnalyzeRule 走工厂: 返回 shared jvmMain 的 DesktopAnalyzeRule,
    // 补全 JsEncodeUtils 面 (md5Encode/createSymmetricCrypto 等) 与 refreshTocUrl
    registerDesktopAnalyzeRuleFactory()
    BookInfoRefreshers.register(DesktopBookInfoRefresher)
    IntentDataProviders.register(DesktopIntentDataAccessor)
    ContentProcessorProviders.register(DesktopContentProcessorAccessor)
    // 与 app 端一致直接注册 shared 实现: 桌面端已注册 QuickJs 引擎与 RegexErrorHandler,
    // 超时检测与 @js: 替换规则均可用
    RegexReplacers.register(RegexReplacerImpl)
    // 注册 Web 服务封面/插图 provider: 未注册时 BookController.getCover/getImg 抛 IllegalStateException
    ImageControllerProviders.register(DesktopImageControllerProvider)
    // 注册 Web 服务阅读状态桥 (commonMain ActiveReadBookStateProvider 读 ActiveReadBookRegistry,
    // shared 阅读页全平台挂接): /deleteBook /saveBookProgress 同步"正在阅读的实例";
    // 此前未注册时静默跳过
    ReadBookStateProviders.register(ActiveReadBookStateProvider)
}

/**
 * 桌面端 [BookInfoRefresher] 实现: 委托 shared commonMain 的 [WebBook.getBookInfoAwait]。
 *
 * app 端 [io.legado.app.model.webBook.BookInfoRefresherImpl] 也是同样委托,
 * 桌面端直接复用 shared 的 WebBook 编排层 (已下沉, 无 Android 依赖)。
 *
 * 未注册时 AnalyzeRule.refreshTocUrl() 经 [BookInfoRefreshers.getOrNull] 拿到 null 静默跳过,
 * 表现为书源 ruleTocUrl 规则失效 (infoUrl 不能刷新 tocUrl); 注册后行为与 app 端一致。
 */
private object DesktopBookInfoRefresher : BookInfoRefresher {
    override suspend fun refreshBookInfo(
        bookSource: BaseSource,
        book: Any,
        canReName: Boolean,
    ) {
        // bookSource 实际是 BookSource (AnalyzeRule 调用方传入), book 实际是 Book
        @Suppress("UNCHECKED_CAST_cast_is_safe_by_call_contract")
        WebBook.getBookInfoAwait(
            bookSource as BookSource,
            book as Book,
            canReName,
        )
    }
}

/**
 * 桌面端 [IntentDataAccessor] 实现: 桥接 shared commonMain 的 [IntentData] 单例。
 *
 * IntentData 是纯 Kotlin object (mutableMap 内存版, 已下沉 commonMain),
 * 桌面端可直接复用, setSource 转发到 IntentData.source = value 即可。
 *
 * 与 app 端 WebBookProvidersImpl 的 IntentDataAccessor 实现段行为完全一致。
 */
private object DesktopIntentDataAccessor : IntentDataAccessor {
    override fun setSource(source: Any?) {
        IntentData.source = source
    }
}

/**
 * 桌面端 [ContentProcessorAccessor] 实现: 复用 commonMain 的 [ContentProcessorShared]。
 *
 * 原 desktop 简化实现 (直接查 replaceRuleDao + 简化 getContent, 不做简繁 / 段落重排 /
 * 去重复标题) 已废弃, 改为完整逻辑下沉后的 [ContentProcessorShared], 与 app 端
 * [io.legado.app.help.book.ContentProcessor] 行为对齐 (替换规则 / 简繁 / 段落重排 / 去重 标题)。
 *
 * 平台专属依赖通过 [DefaultContentProcessorDeps] 注入:
 * - `isAndroid8` = false (非 Android 端, 不做 \u00A0 特殊处理)
 * - `toastOnUi` no-op (非 Android 端无 UI toast, 错误日志走 AppLog.put)
 * - `getChapterFiles` 委托 [io.legado.app.help.book.BookStorageProviders.get].getChapterFiles
 *
 * # 缓存策略
 *
 * 与 app 端 `ContentProcessor.processors` 同构: `bookName + bookOrigin` → [WeakReference],
 * 无强引用滞留; 实例构造 (含 upReplaceRules/upRemoveSameTitle 的 DB IO) 在缓存锁外完成,
 * 与 app 端 `ContentProcessor.get` 的 init 时机一致。
 *
 * 注册时机: 在 [AppDbProviders] 已注册之后 (ContentProcessorShared 依赖 replaceRuleDao/bookDao),
 * 由 [registerDesktopWebBookProviders] 完成。
 *
 * 模式参考 app 端 [io.legado.app.model.webBook.WebBookProvidersImpl] 的 ContentProcessorAccessor 实现段。
 */
private object DesktopContentProcessorAccessor : ContentProcessorAccessor {

    private val deps = DefaultContentProcessorDeps()

    // 与 app 端 ContentProcessor.processors 对齐: 弱引用, book 不再被引用后可回收
    private val processors = ConcurrentHashMap<String, WeakReference<ContentProcessorShared>>()

    private fun getProcessor(book: Book): ContentProcessorShared {
        val key = book.name + book.origin
        processors[key]?.get()?.let { return it }
        // 构造 + 初始化 (DB IO) 放在 map 操作之外, 避免在 ConcurrentHashMap 分段锁内阻塞;
        // 并发下可能多建一个实例, 与 app 端同样是良性竞争
        val processor = ContentProcessorShared(book.name, book.origin, deps)
        runBlocking {
            processor.upReplaceRules()
            processor.upRemoveSameTitle()
        }
        processors[key] = WeakReference(processor)
        return processor
    }

    override fun getTitleReplaceRules(book: Book): List<ReplaceRule> =
        getProcessor(book).getTitleReplaceRules()

    override fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        useReplace: Boolean,
    ): CharSequence = getProcessor(book).getContent(
        book, chapter, content, useReplace = useReplace
    ).toString()

    // BookController.getBookContent web API 下沉用: 显式传 includeTitle (原 app 端 includeTitle = false)
    override fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean,
        useReplace: Boolean,
    ): CharSequence = getProcessor(book).getContent(
        book, chapter, content, includeTitle = includeTitle, useReplace = useReplace
    ).toString()

    override fun getBookContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean,
        useReplace: Boolean,
        chineseConvert: Boolean,
        reSegment: Boolean,
    ): io.legado.app.help.book.BookContent = getProcessor(book).getContent(
        book = book,
        chapter = chapter,
        content = content,
        includeTitle = includeTitle,
        useReplace = useReplace,
        chineseConvert = chineseConvert,
        reSegment = reSegment,
    )

    override fun upReplaceRules() {
        // 刷新所有存活实例的替换规则 (与 app 端 ContentProcessor.upReplaceRules 静态方法一致)
        processors.values.forEach { ref ->
            ref.get()?.let { runBlocking { it.upReplaceRules() } }
        }
    }
}
