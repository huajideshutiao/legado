package io.legado.app.ui.replace.edit

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.coroutine.printOnDebug
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 替换规则编辑 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ReplaceEditViewModel(application: Application) : BaseViewModel(application)`:
 * - 三个方法 (initData / pasteRule / save) 仅依赖 DAO + 协程 + Toasters + 剪贴板文本,
 *   可以下沉 commonMain 供多端复用 (Android / Desktop / iOS / 鸿蒙)。
 * - DAO 访问走 [AppDbProviders.get].replaceRuleDao (宿主启动时由 app 端注册
 *   AppDbAccessorImpl, 已暴露 dictRuleDao/replaceRuleDao 等 DAO)。
 * - 原 `execute { ... }.onSuccess { ... }.onError { ... }.onFinally { ... }`
 *   (BaseViewModel 内委托 [Coroutine.async]) 下沉后直接调 [Coroutine.async],
 *   保留链式 onSuccess/onError/onFinally 回调结构, 行为等价 (业务 context=IO,
 *   回调 executeContext=mainDispatcher, 与 BaseViewModel.execute 默认值一致)。
 *
 * # Android 专属依赖替换
 *
 * - **Intent 解析留 app 端**: 原 `initData(intent: Intent, ...)` 用
 *   `intent.getLongExtra("id", -1)` / `getStringExtra("pattern")` /
 *   `getBooleanExtra("isRegex", false)` / `getStringExtra("scope")` 解析 Bundle,
 *   下沉后改为接收已解析的显式参数 [id]/[pattern]/[isRegex]/[scope]
 *   (字段名与 app 端 intent key 一一对应, 不"改变实现逻辑")。
 * - **剪贴板访问**: 原 `getClipText()` (Android ClipboardManager) 不能下沉,
 *   通过构造函数 lambda [clipTextProvider] 注入:
 *   - app 端实现 `{ getClipText() }` (委托 utils.ContextExtensions.getClipText);
 *   - desktop 端实现用 `Toolkit.getDefaultToolkit().systemClipboard.getData(...)`;
 *   - iOS/鸿蒙留宿主自实现。
 * - **Toast 提示**: 原 `context.toastOnUi(msg)` → [Toasters.get].toast(msg)
 *   (Toaster 接口已下沉 commonMain, androidMain 注册的实现内部切主线程,
 *   与 `context.toastOnUi` 行为等价)。
 * - **错误日志**: 原 `it.printOnDebug()` 走 [printOnDebug] 扩展 (已下沉 commonMain,
 *   各平台 actual 决定是否打栈, 行为对齐)。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (对照
 * [io.legado.app.ui.replace.ReplaceRuleViewModelShared] / DictRuleViewModelShared):
 * - app 端 `ReplaceEditViewModel(application)` `extends BaseViewModel(application)`,
 *   内部持有本类实例, 通过 `viewModelScope` + `{ getClipText() }` 注入;
 * - desktop 端在 Compose `remember` 中构造本类, 注入应用 scope + AWT Clipboard lambda。
 *
 * # 与 [io.legado.app.ui.replace.ReplaceEditViewModel] 区别
 *
 * - [io.legado.app.ui.replace.ReplaceEditViewModel] (旧版, commonMain) 是早期下沉的
 *   独立 KMP VM, 自带 SupervisorJob scope, 用回调 (success/error) 暴露结果, 供
 *   sharedUiMain `ReplaceEditScreen` 直接订阅;
 * - 本类 (Shared) 走组合委托模式, scope 由宿主注入 (Android=viewModelScope /
 *   desktop=应用 scope), 与 ReplaceRuleViewModelShared / DictRuleViewModelShared 一致,
 *   统一下沉规范, 便于多端宿主接管生命周期。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 * @param clipTextProvider 剪贴板文本提供者 (替代 `getClipText()`):
 *   - app 端实现 `{ getClipText() }`
 *   - desktop 端实现 `Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String`
 */
class ReplaceEditViewModelShared(
    private val scope: CoroutineScope,
    private val clipTextProvider: () -> String?,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 当前编辑的规则 (initData 后非空)。
     *
     * 对照 app 端 `var replaceRule: ReplaceRule?` 公开字段, 供宿主 (Activity / Screen)
     * 读取并组装表单。setter 私有, 仅在 [initData] 内赋值。
     */
    var replaceRule: ReplaceRule? = null
        private set

    /**
     * 规则加载完成信号 (与旧版 commonMain `ReplaceEditViewModel.state` 接口一致)。
     *
     * Compose 宿主 (sharedUiMain `ReplaceEditScreen` / desktop 包装层) 用
     * `collectAsState` 订阅, 触发表单回填; app 端 Activity 不订阅, 用 [initData] 的
     * `finally` 回调路径, 两条路径互不影响。
     *
     * 仅在 [initData] 成功加载后通过 `_state.value = replaceRule` 同步推送
     * (与旧版 `onSuccess { _state.value = it }` 行为一致)。
     */
    private val _state = MutableStateFlow<ReplaceRule?>(null)
    val state: StateFlow<ReplaceRule?> = _state.asStateFlow()

    /**
     * 初始化规则数据, 对应 app 端 `initData(intent, finally)`。
     *
     * # 实现细节保持
     *
     * - id > 0: 走 `appDb.replaceRuleDao.findById(id)` 加载已有规则;
     * - id <= 0: 用 [pattern]/[isRegex]/[scope] 构造空模板, `name = pattern`
     *   (与 app 端原 `ReplaceRule(name = pattern, pattern = pattern, ...)` 完全一致,
     *   不"偷懒"分离 name/pattern);
     * - 加载完成后回调 [finally] (与 app 端 `onFinally { replaceRule?.let { finally(it) } }`
     *   等价: replaceRule 为 null 时不回调)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param id 规则 id, >0 表示编辑已有; <=0 表示新增 (用 pattern/isRegex/scope 构造空模板)
     * @param pattern 新增模式的初始 pattern (id<=0 时用作 name+pattern, 默认 "")
     * @param isRegex 是否正则
     * @param scope 作用范围
     * @param finally 加载完成回调, 参数为最终 replaceRule (replaceRule 为 null 时不回调)
     */
    fun initData(
        id: Long,
        pattern: String? = null,
        isRegex: Boolean = false,
        scope: String? = null,
        finally: (replaceRule: ReplaceRule) -> Unit,
    ) {
        Coroutine.async(scope = this.scope) {
            replaceRule = if (id > 0) {
                appDb.replaceRuleDao.findById(id)
            } else {
                val patternStr = pattern ?: ""
                ReplaceRule(
                    name = patternStr,
                    pattern = patternStr,
                    isRegex = isRegex,
                    scope = scope,
                )
            }
            replaceRule
        }.onSuccess {
            // 推送 state 供 Compose 宿主 collectAsState 订阅 (旧版 ReplaceEditViewModel 接口)
            _state.value = it
        }.onFinally {
            replaceRule?.let { finally(it) }
        }
    }

    /**
     * 粘贴规则, 对应 app 端 `pasteRule(success)`。
     *
     * # 实现细节保持
     *
     * - 调 [clipTextProvider]() 取剪贴板文本 (替代 app 端 `getClipText()`);
     * - 空文本抛 `NoStackTraceException("剪贴板为空")`;
     * - 用 `GSON.fromJsonObject<ReplaceRule>(text)` 解析, 失败抛
     *   `NoStackTraceException("格式不对")` (与 app 端完全一致);
     * - 成功回调 [success];
     * - 失败 `Toasters.get().toast(it.message ?: "Error")`
     *   (替代 `context.toastOnUi(...)`) + `it.printOnDebug()` (保留原错误日志)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致;
     * 注: app 端原 `execute(context = Dispatchers.Main)` 让业务在 Main 跑是为了
     * ClipboardManager 主线程访问, 下沉后由宿主 clipTextProvider 决定线程模型,
     * Android ClipboardManager.primaryClip 为 Binder 调用可跨线程,
     * desktop AWT Clipboard 亦可跨线程, 故业务可放 IO 跑, 行为等价)。
     *
     * @param success 解析成功回调, 参数为从剪贴板文本反序列化的 ReplaceRule
     */
    fun pasteRule(success: (ReplaceRule) -> Unit) {
        Coroutine.async(scope = this.scope) {
            val text = clipTextProvider()
            if (text.isNullOrBlank()) {
                throw NoStackTraceException("剪贴板为空")
            }
            GSON.fromJsonObject<ReplaceRule>(text).getOrNull()
                ?: throw NoStackTraceException("格式不对")
        }.onSuccess {
            success.invoke(it)
        }.onError {
            // 替代 context.toastOnUi(msg), Toasters.get() 已下沉 commonMain
            Toasters.get().toast(it.message ?: "Error")
            it.printOnDebug()
        }
    }

    /**
     * 保存规则, 对应 app 端 `save(replaceRule, success)`。
     *
     * # 实现细节保持
     *
     * - 调 `rule.checkValid()` (ReplaceRule 成员函数, 已下沉 commonMain) 校验;
     * - 新规则 (order==Int.MIN_VALUE) 自动取 `maxOrder() + 1`;
     * - `appDb.replaceRuleDao.insert(rule)` 写入;
     * - 成功回调 [success];
     * - 失败 `Toasters.get().toast("save error, ${it.message}")`
     *   (替代 `context.toastOnUi(...)`)。
     *
     * 业务在 IO 跑 (DAO 写入必须 IO), 回调在 mainDispatcher 跑
     * (与 BaseViewModel.execute 默认值一致)。
     *
     * @param rule 待保存规则 (id 已存在则更新, 否则插入)
     * @param success 保存成功回调
     */
    fun save(rule: ReplaceRule, success: () -> Unit) {
        Coroutine.async(scope = this.scope) {
            rule.checkValid()
            if (rule.order == Int.MIN_VALUE) {
                rule.order = appDb.replaceRuleDao.maxOrder() + 1
            }
            appDb.replaceRuleDao.insert(rule)
        }.onSuccess {
            success()
        }.onError {
            // 替代 context.toastOnUi(msg), Toasters.get() 已下沉 commonMain
            Toasters.get().toast("save error, ${it.message}")
        }
    }
}
