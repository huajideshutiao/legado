package io.legado.app.ui.book.filter

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.SourceFilterRule
import io.legado.app.help.coroutine.printOnDebug
import io.legado.app.help.source.SearchBookFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 书源过滤规则页 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `SourceFilterRuleViewModel(application: Application) : BaseViewModel(application)`:
 * - 全部方法都是纯 DAO 写操作 + [SearchBookFilter.reload] 刷缓存, 不依赖 Android 专属 API,
 *   可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get] (宿主启动时注册), 替代 app 端 `appDb` 单例。
 * - [SearchBookFilter] (object) 已下沉 commonMain, [SourceFilterRule] 实体已下沉, 直接复用。
 *
 * # mutate 模式对照
 *
 * 原 app 端:
 * ```
 * private fun mutate(block: suspend CoroutineScope.() -> Unit): Coroutine<Unit> =
 *     execute(block = block).onSuccess { SearchBookFilter.reload() }
 * ```
 * 下沉后:
 * ```
 * private fun mutate(block: suspend CoroutineScope.() -> Unit) {
 *     scope.launch(Dispatchers.IO) {
 *         try {
 *             block()
 *             SearchBookFilter.reload()
 *         } catch (e: Throwable) {
 *             e.printOnDebug()
 *         }
 *     }
 * }
 * ```
 * 行为等价:
 * - 原 `execute` 默认 `context = Dispatchers.IO` 执行 DAO 写;
 * - 原 `onSuccess` 默认 `executeContext = mainDispatcher` (Main) 调 `SearchBookFilter.reload()`;
 *   `reload()` 内部仅 `snapshot = null` (无 suspend / 无线程要求), 在 IO 调用等价;
 * - 原 catch 块无 `onError`, [Coroutine] 默认 `e.printOnDebug()` (DEBUG 才打栈),
 *   shared 端直接调 [printOnDebug] expect fun, 行为完全一致;
 * - 原返回 `Coroutine<Unit>` 仅供链式 `onSuccess/onError` 用, 所有调用方
 *   (SourceFilterRuleActivity) 均 fire-and-forget 不使用返回值, 改为 Unit 不影响调用方。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式:
 * - app 端 SourceFilterRuleViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 仅注入 [scope] 一个参数 (Android = `viewModelScope`), 不算"超多";
 * - 转发全部方法到 shared, 调用方 (SourceFilterRuleActivity) 代码零改动。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
 */
class SourceFilterRuleViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 通用 mutate: 执行 DAO 写操作后刷新 [SearchBookFilter] 缓存。
     *
     * 见类注释中的对照说明。catch 块仅 [printOnDebug], 与原 [Coroutine] 默认行为一致。
     */
    private fun mutate(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
                SearchBookFilter.reload()
            } catch (e: Throwable) {
                // 与原 Coroutine 默认行为等价: e.printOnDebug() 不上报 (release 不打栈)
                e.printOnDebug()
            }
        }
    }

    /** 更新规则 (对照原 SourceFilterRuleViewModel.update)。 */
    fun update(vararg rule: SourceFilterRule) = mutate {
        appDb.sourceFilterRuleDao.update(*rule)
    }

    /** 删除单条规则 (对照原 SourceFilterRuleViewModel.delete)。 */
    fun delete(rule: SourceFilterRule) = mutate {
        appDb.sourceFilterRuleDao.delete(rule)
    }

    /** 批量删除选中规则 (对照原 SourceFilterRuleViewModel.delSelection)。 */
    fun delSelection(rules: List<SourceFilterRule>) = mutate {
        appDb.sourceFilterRuleDao.delete(*rules.toTypedArray())
    }

    /** 批量启用选中规则 (对照原 SourceFilterRuleViewModel.enableSelection)。 */
    fun enableSelection(rules: List<SourceFilterRule>) = mutate {
        val array = Array(rules.size) { rules[it].copy(enabled = true) }
        appDb.sourceFilterRuleDao.update(*array)
    }

    /** 批量禁用选中规则 (对照原 SourceFilterRuleViewModel.disableSelection)。 */
    fun disableSelection(rules: List<SourceFilterRule>) = mutate {
        val array = Array(rules.size) { rules[it].copy(enabled = false) }
        appDb.sourceFilterRuleDao.update(*array)
    }

    /** 置顶单条规则 (对照原 SourceFilterRuleViewModel.toTop)。 */
    fun toTop(rule: SourceFilterRule) = mutate {
        rule.order = appDb.sourceFilterRuleDao.minOrder() - 1
        appDb.sourceFilterRuleDao.update(rule)
    }

    /** 批量置顶选中规则 (对照原 SourceFilterRuleViewModel.topSelect)。 */
    fun topSelect(rules: List<SourceFilterRule>) = mutate {
        var minOrder = appDb.sourceFilterRuleDao.minOrder() - rules.size
        rules.forEach { it.order = ++minOrder }
        appDb.sourceFilterRuleDao.update(*rules.toTypedArray())
    }

    /** 置底单条规则 (对照原 SourceFilterRuleViewModel.toBottom)。 */
    fun toBottom(rule: SourceFilterRule) = mutate {
        rule.order = appDb.sourceFilterRuleDao.maxOrder() + 1
        appDb.sourceFilterRuleDao.update(rule)
    }

    /** 批量置底选中规则 (对照原 SourceFilterRuleViewModel.bottomSelect)。 */
    fun bottomSelect(rules: List<SourceFilterRule>) = mutate {
        var maxOrder = appDb.sourceFilterRuleDao.maxOrder()
        rules.forEach { it.order = ++maxOrder }
        appDb.sourceFilterRuleDao.update(*rules.toTypedArray())
    }

    /** 重排全部规则顺序 (对照原 SourceFilterRuleViewModel.upOrder)。 */
    fun upOrder() = mutate {
        val rules = appDb.sourceFilterRuleDao.all()
        for ((index, rule) in rules.withIndex()) {
            rule.order = index + 1
        }
        appDb.sourceFilterRuleDao.update(*rules.toTypedArray())
    }
}
