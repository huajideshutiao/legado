package io.legado.app.ui.replace

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.ReplaceRule

/**
 * 替换规则数据修改 VM (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 全部业务逻辑 (12 个 DAO 写方法) 已下沉到 shared commonMain
 * [ReplaceRuleViewModelShared]。本类采用**组合委托**模式持有 [shared] 实例,
 * 不通过继承 `ReplaceRuleViewModelShared`:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [ReplaceRuleViewModelShared];
 * - 通过构造函数注入 [viewModelScope] 到 [shared], 所有方法转发到 [shared]。
 *
 * # 调用方兼容
 *
 * [ReplaceRuleActivity] / [ReplaceRuleListViewModel] 调用方式保持不变:
 * - `viewModel.update(rule)` / `viewModel.delete(rule)` / `viewModel.toTop(rule)` 等
 * - 方法签名 (vararg / List 参数) 完全一致
 *
 * # Android 专属依赖移除
 *
 * - 原 `android.text.TextUtils.join(",", set)` 已在 [shared] 中替换为
 *   `set.joinToString(",")` (Kotlin 标准库);
 * - 原 `io.legado.app.utils.splitNotBlank` 已下沉 commonMain, 直接由 [shared] 复用;
 * - 本类不再 import Android 专属 API。
 *
 * 修改数据要 copy, 直接修改会导致界面不刷新 (data class equals 按 id)。
 */
class ReplaceRuleViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope]。
     *
     * 无平台专属 lambda 注入 (本 VM 全部操作走 DAO, 无 Android 专属依赖)。
     */
    private val shared: ReplaceRuleViewModelShared = ReplaceRuleViewModelShared(
        scope = viewModelScope,
    )

    fun update(vararg rule: ReplaceRule) = shared.update(*rule)

    fun delete(rule: ReplaceRule) = shared.delete(rule)

    fun toTop(rule: ReplaceRule) = shared.toTop(rule)

    fun topSelect(rules: List<ReplaceRule>) = shared.topSelect(rules)

    fun toBottom(rule: ReplaceRule) = shared.toBottom(rule)

    fun bottomSelect(rules: List<ReplaceRule>) = shared.bottomSelect(rules)

    fun upOrder() = shared.upOrder()

    fun enableSelection(rules: List<ReplaceRule>) = shared.enableSelection(rules)

    fun disableSelection(rules: List<ReplaceRule>) = shared.disableSelection(rules)

    fun delSelection(rules: List<ReplaceRule>) = shared.delSelection(rules)

    fun addGroup(group: String) = shared.addGroup(group)

    fun upGroup(oldGroup: String, newGroup: String?) = shared.upGroup(oldGroup, newGroup)

    fun delGroup(group: String) = shared.delGroup(group)
}
