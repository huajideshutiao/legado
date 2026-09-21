package io.legado.app.ui.book.toc.rule

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.printStackTraceOnDebug
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope

/**
 * 正文排版规则编辑 VM 共享核心 (commonMain)。
 *
 * 对照 app 端 `TxtTocRuleEditDialog.ViewModel(app) : BaseViewModel(app)` (内部类):
 * 两个方法 (initData/pasteRule) 仅依赖 DAO + 协程 + Toasters + 剪贴板 + GSON,
 * 可下沉多端复用。DAO 走 [AppDbProviders.get].txtTocRuleDao; `execute{...}` 链式回调
 * 下沉为直接调 [Coroutine.async] (业务 IO / 回调 mainDispatcher, 行为等价)。
 *
 * Android 专属依赖替换: scope 经构造函数注入; 剪贴板经 [clipTextProvider] 注入
 * (desktop 用 AWT Toolkit); Toast → [Toasters.get]; 错误日志走 [printStackTraceOnDebug]。
 * Dispatchers.Main: 原 pasteRule 用 Main 跑是给 ClipboardManager 主线程访问, 下沉后
 * 由宿主 clipTextProvider 决定线程模型 (Android primaryClip 是 Binder 调用可跨线程,
 * desktop AWT 亦可跨线程), 业务可放 IO 跑, 行为等价。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承)。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 * @param clipTextProvider 剪贴板文本提供者 (替代 `getClipText()`)
 */
class TxtTocRuleEditViewModelShared(
    private val scope: CoroutineScope,
    private val clipTextProvider: () -> String?,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 当前编辑的规则 (initData 后非空), 对应 app 端 `var tocRule: TxtTocRule? = null`。
     *
     * setter 公开 (与 app 端一致), 供宿主 (Dialog) 的 `getRuleFromView()` 在
     * 新增分支 `viewModel.tocRule = this` 赋值。
     */
    var tocRule: TxtTocRule? = null

    /**
     * 初始化规则数据, 对应 app 端 `initData(id, finally)`。
     *
     * # 实现细节保持
     *
     * - `if (tocRule != null) return` 早退 (与 app 端完全一致, 避免重复加载覆盖已编辑内容);
     * - id == null: 跳过 DAO 加载 (对应 app 端 `return@execute`), tocRule 保持 null,
     *   由宿主在 onFinally 中走新增分支;
     * - id != null: 走 `appDb.txtTocRuleDao.get(id)` 加载已有规则;
     * - 加载完成后回调 [finally], 参数为当前 tocRule (可能为 null, 与 app 端一致)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param id 规则 id, null 表示新增
     * @param finally 加载完成回调, 参数为当前 tocRule (可能为 null)
     */
    fun initData(id: Long?, finally: (tocRule: TxtTocRule?) -> Unit) {
        if (tocRule != null) return
        Coroutine.async(scope = this.scope) {
            if (id == null) return@async
            tocRule = appDb.txtTocRuleDao.get(id)
        }.onFinally {
            finally.invoke(tocRule)
        }
    }

    /**
     * 粘贴规则, 对应 app 端 `pasteRule(success)`。
     *
     * # 实现细节保持
     *
     * - 调 [clipTextProvider]() 取剪贴板文本 (替代 app 端 `getClipText()`);
     * - 空文本抛 `NoStackTraceException("剪贴板为空")`;
     * - 用 `GSON.fromJsonObject<TxtTocRule>(text)` 解析, 失败抛
     *   `NoStackTraceException("格式不对")` (与 app 端完全一致);
     * - 成功回调 [success];
     * - 失败 `Toasters.get().toast(it.message ?: "Error")`
     *   (替代 `context.toastOnUi(...)`) + `it.printStackTraceOnDebug()` (保留原错误日志)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致;
     * 注: app 端原 `execute(context = Dispatchers.Main)` 让业务在 Main 跑是为了
     * ClipboardManager 主线程访问, 下沉后由宿主 clipTextProvider 决定线程模型,
     * Android ClipboardManager.primaryClip 为 Binder 调用可跨线程,
     * desktop AWT Clipboard 亦可跨线程, 故业务可放 IO 跑, 行为等价)。
     *
     * @param success 解析成功回调, 参数为从剪贴板文本反序列化的 TxtTocRule
     */
    fun pasteRule(success: (TxtTocRule) -> Unit) {
        Coroutine.async(scope = this.scope) {
            val text = clipTextProvider()
            if (text.isNullOrBlank()) {
                throw NoStackTraceException("剪贴板为空")
            }
            GSON.fromJsonObject<TxtTocRule>(text).getOrNull()
                ?: throw NoStackTraceException("格式不对")
        }.onSuccess {
            success.invoke(it)
        }.onError {
            // 替代 context.toastOnUi(msg), Toasters.get() 已下沉 commonMain
            Toasters.get().toast(it.message ?: "Error")
            it.printStackTraceOnDebug()
        }
    }
}
