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
 * 正文排版规则编辑 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `TxtTocRuleEditDialog.ViewModel(application: Application) :
 * BaseViewModel(application)` (内部类):
 * - 两个方法 (initData / pasteRule) 仅依赖 DAO + 协程 + Toasters + 剪贴板文本 +
 *   GSON, 可以下沉 commonMain 供多端复用 (Android / Desktop / iOS / 鸿蒙)。
 * - DAO 访问走 [AppDbProviders.get].txtTocRuleDao (宿主启动时由 app 端注册
 *   AppDbAccessorImpl, 已暴露 txtTocRuleDao)。
 * - 原 `execute { ... }.onFinally { ... }` / `execute(context=Dispatchers.Main) { ... }
 *   .onSuccess { ... }.onError { ... }` (BaseViewModel 内委托 [Coroutine.async])
 *   下沉后直接调 [Coroutine.async], 保留链式 onFinally/onSuccess/onError 回调结构,
 *   行为等价 (业务 context=IO, 回调 executeContext=mainDispatcher,
 *   与 BaseViewModel.execute 默认值一致)。
 *
 * # Android 专属依赖替换
 *
 * - **viewModelScope**: BaseViewModel 来自 Android ViewModel, 不能下沉,
 *   通过构造函数 [scope] 注入 (Android = `viewModelScope` / 桌面 = 应用主作用域)。
 * - **剪贴板访问**: 原 `getClipText()` (Android ClipboardManager) 不能下沉,
 *   通过构造函数 lambda [clipTextProvider] 注入:
 *   - app 端实现 `{ getClipText() }` (委托 utils.ContextExtensions.getClipText);
 *   - desktop 端实现用 `Toolkit.getDefaultToolkit().systemClipboard.getData(...)`;
 *   - iOS/鸿蒙留宿主自实现。
 * - **Toast 提示**: 原 `context.toastOnUi(msg)` → [Toasters.get].toast(msg)
 *   (Toaster 接口已下沉 commonMain, androidMain 注册的实现内部切主线程,
 *   与 `context.toastOnUi` 行为等价)。
 * - **错误日志**: 原 `it.printStackTraceOnDebug()` 走 [printStackTraceOnDebug] 扩展 (已下沉 commonMain,
 *   各平台 actual 决定是否打栈, 行为对齐)。
 * - **GSON**: shared 端 [GSON] 已是 KS_JSON 的别名 (见 GsonExtensions.kt),
 *   [fromJsonObject] 扩展也已下沉 commonMain, 直接复用, 与 app 端行为一致。
 * - **Dispatchers.Main**: 原 `pasteRule` 用 `execute(context = Dispatchers.Main)`
 *   让业务在 Main 跑是为了 ClipboardManager 主线程访问, 下沉后由宿主
 *   [clipTextProvider] 决定线程模型 (Android ClipboardManager.primaryClip 为
 *   Binder 调用可跨线程, desktop AWT Clipboard 亦可跨线程), 故业务可放 IO 跑,
 *   行为等价 (对照 ReplaceEditViewModelShared.pasteRule 注释)。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (对照
 * [io.legado.app.ui.replace.edit.ReplaceEditViewModelShared]):
 * - app 端 `TxtTocRuleEditDialog.ViewModel(application)` `extends BaseViewModel(application)`,
 *   内部持有本类实例, 通过 `viewModelScope` + `{ getClipText() }` 注入;
 * - desktop 端在 Compose `remember` 中构造本类, 注入应用 scope + AWT Clipboard lambda。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 * @param clipTextProvider 剪贴板文本提供者 (替代 `getClipText()`):
 *   - app 端实现 `{ getClipText() }`
 *   - desktop 端实现 `Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String`
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
