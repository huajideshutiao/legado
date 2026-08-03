package io.legado.app.ui.book.source.edit

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.toast.Toasters
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CoroutineScope

/**
 * 书源编辑 VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `BookSourceEditViewModel(application) : BaseViewModel(application)`:
 * 六个方法 (initData/save/pasteSource/importSource×2/clearCookie) 仅依赖 DAO + 协程 +
 * 剪贴板/Toast/i18n/okHttpClient 等, 可下沉 commonMain 多端复用。DAO 走 [AppDbProviders.get];
 * `execute{...}` 链式回调下沉为直接调 [Coroutine.async] (业务 IO / 回调 mainDispatcher, 行为等价)。
 * Android 专属依赖通过构造 lambda 注入: Intent 解析留 app 端 (改传 [sourceUrl] 按 URL 查 DB,
 * KMP 路由只传稳定 ID/URL); 剪贴板 [clipTextProvider] / Toast [Toasters.get] / 校验文案
 * [nonNullNameUrlMessage] / okHttpClient [OkHttpClientProviders.get] / [sourceConfigRemover] /
 * [cookieRemover] / [systemCurrentTimeMillis]。
 *
 * 设计: 组合委托而非 `expect abstract class` (BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用且单继承冲突); app 端持有本类实例注入 scope + lambda, desktop 端
 * Compose remember 中构造。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 * @param clipTextProvider 剪贴板文本提供者 (替代 `getClipText()`)
 * @param sourceConfigRemover 书源配置清理器 (替代 `SourceConfig.removeSource(url)`)
 * @param cookieRemover Cookie 清理器 (替代 `CookieStore.removeCookie(url)`)
 * @param nonNullNameUrlMessage 校验失败文案提供者 (替代 `context.getString(R.string.non_null_name_url)`)
 */
class BookSourceEditViewModelShared(
    private val scope: CoroutineScope,
    private val clipTextProvider: () -> String?,
    private val sourceConfigRemover: (String) -> Unit,
    private val cookieRemover: (String) -> Unit,
    private val nonNullNameUrlMessage: () -> String,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 当前编辑的书源 (initData 后非空), 对应 app 端 `var bookSource: BookSource? = null`。
     *
     * setter 私有, 仅在 [initData] / [save] 内赋值。宿主 (Activity / Screen) 只读访问,
     * 读取并组装表单 (对照 [BookSourceEditActivity.upSourceView] / `getSource`。
     * app 端 Activity 仅读不写, 故 [io.legado.app.ui.book.source.edit.BookSourceEditViewModel]
     * 暴露为 `val bookSource get() = shared.bookSource` 只读, 行为等价)。
     */
    var bookSource: BookSource? = null
        private set

    /**
     * 初始化书源数据, 对应 app 端 `initData(intent, onFinally)`。
     *
     * # 实现细节保持
     *
     * - sourceUrl 非空: 走 `appDb.bookSourceDao.getBookSource(sourceUrl)` 加载已有书源,
     *   为 null 时提前 return (与 app 端 `?: return@execute` 完全一致, 不回调 onFinally
     *   之外的逻辑);
     * - sourceUrl 为空: 新建空书源, bookSource 保持 null (app 端此处取
     *   `IntentData.source as? BookSource`, 即书源管理页"编辑"直传对象少查一次 DB;
     *   KMP 的 [io.legado.app.ui.root.AppRoute] 明确只传稳定 ID/URL 以支持序列化恢复,
     *   全部调用方都改传 bookSourceUrl 由本方法查 DB, 故不再有对象入参);
     * - 加载完成后回调 [onFinally] (与 app 端 `onFinally { onFinally() }` 等价, 无参回调,
     *   无论 bookSource 是否为 null 均回调, 与 app 端一致)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param sourceUrl 书源 URL (app 端 `intent.getStringExtra("sourceUrl")`), 非空时按 URL 查 DAO
     * @param onFinally 加载完成回调 (无参, 与 app 端 onFinally 一致, 始终触发)
     */
    fun initData(sourceUrl: String?, onFinally: () -> Unit) {
        Coroutine.async(scope = this.scope) {
            if (sourceUrl != null) {
                bookSource = appDb.bookSourceDao.getBookSource(sourceUrl) ?: return@async
            }
        }.onFinally {
            onFinally()
        }
    }

    /**
     * 保存书源, 对应 app 端 `save(source, success)`。
     *
     * # 实现细节保持
     *
     * - 校验 `bookSourceUrl` / `bookSourceName` 非空, 失败抛
     *   `NoStackTraceException(nonNullNameUrlMessage())` (替代
     *   `context.getString(R.string.non_null_name_url)`, 文案由宿主注入);
     * - 与 oldSource (bookSource ?: BookSource()) 不相等时:
     *   - 更新 `lastUpdateTime` (用 [systemCurrentTimeMillis] 替代 `System.currentTimeMillis()`);
     *   - exploreUrl 变更时 `oldSource.clearExploreKindsCache()` (已下沉 commonMain);
     *   - jsLib 变更时 `SharedJsScope.remove(oldSource.jsLib)` (已下沉 commonMain);
     * - 旧 bookSource 存在时:
     *   - bookSourceUrl 变更: `SourceHelp.deleteBookSource(it.bookSourceUrl)` (已下沉,
     *     内部含 DAO 删除 + cache 清理 + SourceConfig 等价清理);
     *   - bookSourceUrl 未变更: `appDb.bookSourceDao.delete(it)` + [sourceConfigRemover]
     *     (替代 `SourceConfig.removeSource`, 仅删 DAO 记录 + 清理配置, 不删 cache variables,
     *     与 app 端完全一致);
     * - `appDb.bookSourceDao.insert(source)` 写入;
     * - `bookSource = source` 更新当前引用;
     * - 成功回调 [success] (参数为保存的 source, 与 app 端 `source` 返回值一致);
     * - 失败 `Toasters.get().toast(it.message)` (替代 `context.toastOnUi(...)`,
     *   注意原 app 端 save 的 onError 无 `?: "Error"` fallback, 保持一致) + `it.printStackTraceOnDebug()`。
     *
     * 业务在 IO 跑 (DAO 写入必须 IO), 回调在 mainDispatcher 跑
     * (与 BaseViewModel.execute 默认值一致)。
     *
     * @param source 待保存书源 (bookSourceUrl 已存在则更新, 否则插入)
     * @param success 保存成功回调, 参数为保存的 source (可空, 与 app 端默认 null 一致)
     */
    fun save(source: BookSource, success: ((BookSource) -> Unit)? = null) {
        Coroutine.async(scope = this.scope) {
            if (source.bookSourceUrl.isBlank() || source.bookSourceName.isBlank()) {
                throw NoStackTraceException(nonNullNameUrlMessage())
            }
            val oldSource = bookSource ?: BookSource()
            if (!source.equal(oldSource)) {
                source.lastUpdateTime = systemCurrentTimeMillis()
                if (oldSource.exploreUrl != source.exploreUrl) {
                    oldSource.clearExploreKindsCache()
                }
                if (oldSource.jsLib != source.jsLib) {
                    SharedJsScope.remove(oldSource.jsLib)
                }
            }
            bookSource?.let {
                if (it.bookSourceUrl != source.bookSourceUrl) {
                    SourceHelp.deleteBookSource(it.bookSourceUrl)
                } else {
                    appDb.bookSourceDao.delete(it)
                    sourceConfigRemover(it.bookSourceUrl)
                }
            }
            appDb.bookSourceDao.insert(source)
            bookSource = source
            source
        }.onSuccess {
            success?.invoke(it)
        }.onError {
            // 替代 context.toastOnUi(msg), Toasters.get() 已下沉 commonMain
            // 原 app 端 save 的 onError 直接 toast localizedMessage; toast 需非空, 空时给空串
            Toasters.get().toast(it.message ?: "")
            it.printStackTraceOnDebug()
        }
    }

    /**
     * 粘贴书源, 对应 app 端 `pasteSource(onSuccess)`。
     *
     * # 实现细节保持
     *
     * - 调 [clipTextProvider]() 取剪贴板文本 (替代 app 端 `getClipText()`);
     * - 空文本抛 `NoStackTraceException("剪贴板为空")`;
     * - 非空调 [importSource] (非 suspend 版, text + finally 回调) 解析;
     * - 失败 `Toasters.get().toast(it.message ?: "Error")`
     *   (替代 `context.toastOnUi(...)`) + `it.printStackTraceOnDebug()`。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致;
     * 注: app 端原 `execute(context = Dispatchers.Main)` 让业务在 Main 跑是为了
     * ClipboardManager 主线程访问, 下沉后由宿主 clipTextProvider 决定线程模型,
     * Android ClipboardManager.primaryClip 为 Binder 调用可跨线程,
     * desktop AWT Clipboard 亦可跨线程, 故业务可放 IO 跑, 行为等价;
     * 对照 [io.legado.app.ui.replace.edit.ReplaceEditViewModelShared.pasteRule] 同样处理)。
     *
     * @param onSuccess 解析成功回调, 参数为从剪贴板文本反序列化的 BookSource
     */
    fun pasteSource(onSuccess: (source: BookSource) -> Unit) {
        Coroutine.async(scope = this.scope) {
            val text = clipTextProvider()
            if (text.isNullOrBlank()) {
                throw NoStackTraceException("剪贴板为空")
            } else {
                importSource(text, onSuccess)
            }
        }.onError {
            // 替代 context.toastOnUi(msg), Toasters.get() 已下沉 commonMain
            Toasters.get().toast(it.message ?: "Error")
            it.printStackTraceOnDebug()
        }
    }

    /**
     * 导入书源 (回调版), 对应 app 端 `importSource(text, finally)`。
     *
     * # 实现细节保持
     *
     * - 调 [importSource] (suspend 版) 解析文本;
     * - 成功回调 [finally] (参数为解析出的 BookSource);
     * - 失败 `Toasters.get().toast(it.message ?: "Error")`
     *   (替代 `context.toastOnUi(...)`) + `it.printStackTraceOnDebug()`。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param text 待导入文本 (URL / JSON 对象 / JSON 数组)
     * @param finally 解析成功回调, 参数为解析出的 BookSource
     */
    fun importSource(text: String, finally: (source: BookSource) -> Unit) {
        Coroutine.async(scope = this.scope) {
            importSource(text)
        }.onSuccess {
            finally.invoke(it)
        }.onError {
            // 替代 context.toastOnUi(msg), Toasters.get() 已下沉 commonMain
            Toasters.get().toast(it.message ?: "Error")
            it.printStackTraceOnDebug()
        }
    }

    /**
     * 导入书源 (suspend 版), 对应 app 端 `suspend fun importSource(text: String): BookSource`。
     *
     * # 实现细节保持
     *
     * - URL: 用 [OkHttpClientProviders.get].okHttpClient (替代 app 端 `okHttpClient` 顶层 val)
     *   调 [newCallStrResponse] 取 body 后递归调自身解析 (与 app 端完全一致);
     * - JSON 数组: `GSON.fromJsonArray<BookSource>(text).getOrThrow()[0]` (取第一个,
     *   与 app 端完全一致);
     * - JSON 对象: `GSON.fromJsonObject<BookSource>(text).getOrThrow()` (与 app 端完全一致);
     * - 其他: 抛 `NoStackTraceException("格式不对")` (与 app 端完全一致)。
     *
     * @param text 待导入文本 (URL / JSON 对象 / JSON 数组)
     * @return 解析出的 BookSource
     */
    suspend fun importSource(text: String): BookSource {
        return when {
            text.isAbsUrl() -> {
                val text1 = OkHttpClientProviders.get().okHttpClient.newCallStrResponse { url(text) }.body
                importSource(text1!!)
            }

            text.isJsonArray() -> {
                GSON.fromJsonArray<BookSource>(text).getOrThrow()[0]
            }

            text.isJsonObject() -> {
                GSON.fromJsonObject<BookSource>(text).getOrThrow()
            }

            else -> throw NoStackTraceException("格式不对")
        }
    }

    /**
     * 清除 Cookie, 对应 app 端 `clearCookie(url)`。
     *
     * # 实现细节保持
     *
     * - 调 [cookieRemover] 清理指定 URL 的 Cookie (替代 `CookieStore.removeCookie(url)`,
     *   CookieStore 未下沉, 由宿主 lambda 注入);
     * - 无回调 (与 app 端 `execute { CookieStore.removeCookie(url) }` 一致, 无 onSuccess/onError)。
     *
     * 业务在 IO 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param url 待清理 Cookie 的 URL (书源 bookSourceUrl)
     */
    fun clearCookie(url: String) {
        Coroutine.async(scope = this.scope) {
            cookieRemover(url)
        }
    }

}
