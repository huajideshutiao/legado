package io.legado.app.ui.dict

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.search
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

/**
 * 字典查询 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `DictViewModel(application: Application) : BaseViewModel(application)`:
 * - 两个方法 (initData / dict) 仅依赖 DAO + 协程 + DictRule.search, 无其他 Android API,
 *   可以下沉 commonMain 供多端复用 (Android / Desktop / iOS / 鸿蒙)。
 * - DAO 访问走 [AppDbProviders.get].dictRuleDao (宿主启动时由 app 端注册
 *   AppDbAccessorImpl, 已暴露 dictRuleDao)。
 * - 原 `execute { ... }.onSuccess { ... }.onError { ... }` (BaseViewModel 内委托
 *   [Coroutine.async]) 下沉后直接调 [Coroutine.async], 保留链式 onSuccess/onError
 *   回调结构, 行为等价 (业务 context=IO, 回调 executeContext=mainDispatcher,
 *   与 BaseViewModel.execute 默认值一致)。
 *
 * # Android 专属依赖替换
 *
 * - **viewModelScope**: BaseViewModel 来自 Android ViewModel, 不能下沉,
 *   通过构造函数 [scope] 注入 (Android = `viewModelScope` / 桌面 = 应用主作用域)。
 * - **appDb**: 原 `appDb.dictRuleDao` (顶层 val, 委托 appCtx) 下沉为
 *   [AppDbProviders.get].dictRuleDao (接口由宿主注册)。
 * - [DictRule.search] 扩展函数已下沉 commonMain (DictRuleExt.kt), 直接复用。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (对照
 * [io.legado.app.ui.replace.edit.ReplaceEditViewModelShared] /
 * [io.legado.app.ui.dict.rule.DictRuleViewModelShared]):
 * - app 端 `DictViewModel(application)` `extends BaseViewModel(application)`,
 *   内部持有本类实例, 通过 `viewModelScope` 注入;
 * - desktop 端在 Compose `remember` 中构造本类, 注入应用 scope。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 */
class DictViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 当前字典查询协程, 对应 app 端 `private var dictJob: Coroutine<String>?`。
     *
     * 调用 [dict] 时先 cancel 旧 job 再赋新 job, 实现新查询取消旧查询。
     * 类型为 [Coroutine]<String>?, 与 app 端原声明一致。
     */
    private var dictJob: Coroutine<String>? = null

    /**
     * 加载启用的字典规则列表, 对应 app 端 `initData(onSuccess)`。
     *
     * # 实现细节保持
     *
     * - 调 `appDb.dictRuleDao.enabled()` 取启用规则;
     * - 成功回调 [onSuccess] (与 app 端 `onSuccess { onSuccess.invoke(it) }` 等价)。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param onSuccess 加载成功回调, 参数为启用的字典规则列表
     */
    fun initData(onSuccess: (List<DictRule>) -> Unit) {
        Coroutine.async(scope = this.scope) {
            appDb.dictRuleDao.enabled()
        }.onSuccess {
            onSuccess.invoke(it)
        }
    }

    /**
     * 执行字典查询, 对应 app 端 `dict(dictRule, word, onFinally)`。
     *
     * # 实现细节保持
     *
     * - 先 `dictJob?.cancel()` 取消上次查询 (与 app 端完全一致);
     * - 调 `dictRule.search(word)` 执行查询 (扩展函数已下沉 commonMain);
     * - 成功回调 `onFinally.invoke(it)` 传查询结果;
     * - 失败回调 `onFinally.invoke(it.localizedMessage ?: "ERROR")` 传错误信息
     *   (与 app 端 onError 完全一致, 不"偷懒"合并回调签名);
     * - 保存返回的 Coroutine 到 [dictJob], 供下次调用 cancel。
     *
     * 业务在 IO 跑, 回调在 mainDispatcher 跑 (与 BaseViewModel.execute 默认值一致)。
     *
     * @param dictRule 字典规则
     * @param word 待查单词
     * @param onFinally 完成回调 (成功传结果字符串, 失败传错误信息字符串)
     */
    fun dict(
        dictRule: DictRule,
        word: String,
        onFinally: (String) -> Unit,
    ) {
        dictJob?.cancel()
        dictJob = Coroutine.async(scope = this.scope) {
            dictRule.search(word)
        }.onSuccess {
            onFinally.invoke(it)
        }.onError {
            onFinally.invoke(it.localizedMessage ?: "ERROR")
        }
    }
}
