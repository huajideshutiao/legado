package io.legado.app.ui.book.filter

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.SourceFilterRule

/**
 * 书源过滤规则页 ViewModel (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 全部业务方法 (update / delete / delSelection / enableSelection / disableSelection /
 * toTop / topSelect / toBottom / bottomSelect / upOrder) 已下沉到 shared commonMain
 * [SourceFilterRuleViewModelShared], 内部用 `scope.launch(Dispatchers.IO) + try/catch`
 * 替代 `execute(block).onSuccess { SearchBookFilter.reload() }`, 行为等价 (见 shared 类注释)。
 *
 * # 设计选择 (组合委托)
 *
 * 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 * `viewModelScope`), Kotlin 单继承无法同时继承 [SourceFilterRuleViewModelShared],
 * 改用组合委托模式持有 [shared] 实例, 转发全部方法, 调用方 (SourceFilterRuleActivity) 代码零改动。
 *
 * # 调用方兼容
 *
 * [SourceFilterRuleActivity] 调用方式保持不变:
 * - `viewModel.update(*filterRules.toTypedArray())`
 * - `viewModel.delSelection(selection())`
 * - `viewModel.delete(rule)`
 * - `viewModel.enableSelection(selection())` / `viewModel.disableSelection(selection())`
 * - `viewModel.topSelect(selection())` / `viewModel.bottomSelect(selection())`
 * - `viewModel.toTop(rule)` / `viewModel.toBottom(rule)`
 * - `viewModel.update(rule.copy(enabled = enabled))`
 *
 * 返回值由 `Coroutine<Unit>` 改为 `Unit`: 所有调用方均 fire-and-forget 不使用返回值,
 * 不影响调用方 (见 shared 类注释)。
 */
class SourceFilterRuleViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope]。
     *
     * 原 `mutate(execute(block).onSuccess { SearchBookFilter.reload() })` 已下沉 shared,
     * shared 内部用 `scope.launch(Dispatchers.IO) { try { block(); SearchBookFilter.reload() }
     * catch (e) { e.printOnDebug() } }` 等价实现。
     */
    private val shared: SourceFilterRuleViewModelShared = SourceFilterRuleViewModelShared(
        scope = viewModelScope,
    )

    /** 更新规则 (转发到 [shared.update])。 */
    fun update(vararg rule: SourceFilterRule) = shared.update(*rule)

    /** 删除单条规则 (转发到 [shared.delete])。 */
    fun delete(rule: SourceFilterRule) = shared.delete(rule)

    /** 批量删除选中规则 (转发到 [shared.delSelection])。 */
    fun delSelection(rules: List<SourceFilterRule>) = shared.delSelection(rules)

    /** 批量启用选中规则 (转发到 [shared.enableSelection])。 */
    fun enableSelection(rules: List<SourceFilterRule>) = shared.enableSelection(rules)

    /** 批量禁用选中规则 (转发到 [shared.disableSelection])。 */
    fun disableSelection(rules: List<SourceFilterRule>) = shared.disableSelection(rules)

    /** 置顶单条规则 (转发到 [shared.toTop])。 */
    fun toTop(rule: SourceFilterRule) = shared.toTop(rule)

    /** 批量置顶选中规则 (转发到 [shared.topSelect])。 */
    fun topSelect(rules: List<SourceFilterRule>) = shared.topSelect(rules)

    /** 置底单条规则 (转发到 [shared.toBottom])。 */
    fun toBottom(rule: SourceFilterRule) = shared.toBottom(rule)

    /** 批量置底选中规则 (转发到 [shared.bottomSelect])。 */
    fun bottomSelect(rules: List<SourceFilterRule>) = shared.bottomSelect(rules)

    /** 重排全部规则顺序 (转发到 [shared.upOrder])。 */
    fun upOrder() = shared.upOrder()
}
