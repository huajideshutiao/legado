package io.legado.app.ui.book.source.edit

import android.app.Application
import android.content.Intent
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.getClipText

/**
 * 书源编辑 VM (Android 端组合委托包装)。
 *
 * # 背景
 *
 * 对照 [BookSourceEditViewModelShared] (commonMain 共享核心), 本类仅做 Android 专属适配:
 * - **Intent 解析**: 原 `initData(intent, onFinally)` 用 `intent.getStringExtra("sourceUrl")`
 *   解析 Bundle, 并读 `IntentData.source as? BookSource`, commonMain 不能直接下沉,
 *   留在本类解析后转发到 [shared.initData] 的显式参数;
 * - **剪贴板访问**: `getClipText()` 是 Android ClipboardManager 扩展, 不能下沉,
 *   通过 lambda `{ getClipText() }` 注入 [shared] 的 `clipTextProvider`;
 * - **SourceConfig 清理**: `SourceConfig.removeSource(url)` 依赖 Android SharedPreferences,
 *   未下沉, 通过 lambda `{ url -> SourceConfig.removeSource(url) }` 注入 [shared] 的 `sourceConfigRemover`;
 * - **CookieStore 清理**: `CookieStore.removeCookie(url)` 依赖 Android CookieJar, 未下沉,
 *   通过 lambda `{ url -> CookieStore.removeCookie(url) }` 注入 [shared] 的 `cookieRemover`;
 * - **i18n 字符串**: `context.getString(R.string.non_null_name_url)` 依赖 Android 资源系统,
 *   通过 lambda `{ context.getString(R.string.non_null_name_url) }` 注入 [shared] 的 `nonNullNameUrlMessage`;
 * - **scope 注入**: `viewModelScope` (BaseViewModel 来自 AndroidViewModel) 传入 [shared]。
 *
 * # 行为等价性
 *
 * - [initData] 解析 intent 后转发, `bookSource` 在 shared 内同步赋值 (与原 `bookSource = ...`
 *   在 execute block 内赋值语义等价, 主线程回调保证 UI 读取安全);
 * - [save] / [pasteSource] / [importSource] / [clearCookie] 直接转发到 [shared],
 *   Toast / 错误日志在 shared 内通过 `Toasters.get().toast(...)` + `printOnDebug()` 完成
 *   (替代原 `context.toastOnUi` + `it.printOnDebug()`), 调用方接口不变;
 * - `var bookSource: BookSource?` 公开字段保留为只读委托 `val bookSource get() = shared.bookSource`
 *   (Activity 仅读不写, 行为等价, 对照 [io.legado.app.ui.replace.edit.ReplaceEditViewModel] 同样处理)。
 *
 * # 设计参考
 *
 * 对照同模块 [io.legado.app.ui.replace.edit.ReplaceEditViewModel] 的组合委托模式
 * (持有 `ReplaceEditViewModelShared`), 以及
 * [io.legado.app.ui.dict.rule.DictRuleEditDialog] 内部 DictRuleEditViewModel
 * (持有 `DictRuleEditViewModelShared`)。
 */
class BookSourceEditViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心, 注入 `viewModelScope` + Android 专属 lambda。
     *
     * - `viewModelScope`: AndroidViewModel 提供, Activity 销毁时自动取消;
     * - `{ getClipText() }`: 替代原 `pasteSource` 内的 `getClipText()` 直接调用,
     *   commonMain 端通过 `clipTextProvider()` 间接访问 (Android ClipboardManager
     *   为 Binder 调用, 可跨线程, 与 shared 端业务在 IO 跑兼容);
     * - `{ url -> SourceConfig.removeSource(url) }`: 替代原 `save` 内的 `SourceConfig.removeSource`,
     *   SourceConfig 未下沉 (依赖 SharedPreferences), 由 app 端 lambda 注入;
     * - `{ url -> CookieStore.removeCookie(url) }`: 替代原 `clearCookie` 内的 `CookieStore.removeCookie`,
     *   CookieStore 未下沉 (依赖 CookieJar), 由 app 端 lambda 注入;
     * - `{ context.getString(R.string.non_null_name_url) }`: 替代原 `save` 内的 `context.getString`,
     *   保持 locale 切换行为。
     */
    private val shared: BookSourceEditViewModelShared = BookSourceEditViewModelShared(
        scope = viewModelScope,
        clipTextProvider = { getClipText() },
        sourceConfigRemover = { url -> SourceConfig.removeSource(url) },
        cookieRemover = { url -> CookieStore.removeCookie(url) },
        nonNullNameUrlMessage = { context.getString(R.string.non_null_name_url) },
    )

    /**
     * 当前编辑的书源 (initData 后非空), 由 [shared] 同步赋值。
     *
     * Activity 通过 `viewModel.bookSource` 读取组装表单 (见
     * `BookSourceEditActivity.upSourceView` / `getSource`)。原 `var bookSource: BookSource? = null`
     * 字段下沉后改为委托 [shared.bookSource] (private set), app 端只读访问。
     * Activity 实际只读不写, 行为等价。
     */
    val bookSource: BookSource? get() = shared.bookSource

    /**
     * 初始化书源数据, 对应原 `initData(intent, onFinally)`。
     *
     * 解析 intent 的 sourceUrl 字段 + IntentData.source 后转发到 [shared.initData],
     * 加载完成回调 [onFinally] 由 shared 内部 onFinally 触发 (始终回调, 与原 `onFinally { onFinally() }`
     * 行为一致)。
     */
    fun initData(intent: Intent, onFinally: () -> Unit) {
        val sourceUrl = intent.getStringExtra("sourceUrl")
        val source = IntentData.source as? BookSource
        shared.initData(
            sourceUrl = sourceUrl,
            source = source,
            onFinally = onFinally,
        )
    }

    /**
     * 保存书源, 对应原 `save(source, success)`。
     *
     * 直接转发到 [shared.save], 由 shared 内部校验 + 清理旧源 + insert, 成功回调 [success],
     * 失败 toast + printOnDebug。
     */
    fun save(source: BookSource, success: ((BookSource) -> Unit)? = null) {
        shared.save(source, success)
    }

    /**
     * 粘贴书源, 对应原 `pasteSource(onSuccess)`。
     *
     * 直接转发到 [shared.pasteSource], 由 shared 内部调 `clipTextProvider()` (即
     * `getClipText()`) 取剪贴板文本并解析, 成功回调 [onSuccess], 失败 toast + printOnDebug。
     */
    fun pasteSource(onSuccess: (source: BookSource) -> Unit) {
        shared.pasteSource(onSuccess)
    }

    /**
     * 导入书源 (回调版), 对应原 `importSource(text, finally)`。
     *
     * 直接转发到 [shared.importSource], 由 shared 内部解析文本, 成功回调 [finally],
     * 失败 toast + printOnDebug。
     */
    fun importSource(text: String, finally: (source: BookSource) -> Unit) {
        shared.importSource(text, finally)
    }

    /**
     * 导入书源 (suspend 版), 对应原 `suspend fun importSource(text: String): BookSource`。
     *
     * 直接转发到 [shared.importSource], 由 shared 内部按 URL/JSON 数组/JSON 对象解析。
     */
    suspend fun importSource(text: String): BookSource {
        return shared.importSource(text)
    }

    /**
     * 清除 Cookie, 对应原 `clearCookie(url)`。
     *
     * 直接转发到 [shared.clearCookie], 由 shared 内部调 `cookieRemover(url)` (即
     * `CookieStore.removeCookie(url)`) 清理。
     */
    fun clearCookie(url: String) {
        shared.clearCookie(url)
    }

}
