package io.legado.app.ui.association

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.RuleSub

/**
 * 规则订阅数据修改 VM (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 全部业务逻辑 (5 个 DAO 写方法: save/delete/upOrder/toTop/toBottom) 已下沉到
 * shared commonMain [RuleSubViewModelShared]。本类采用**组合委托**模式持有 [shared]
 * 实例, 不通过继承 `RuleSubViewModelShared`:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [RuleSubViewModelShared];
 * - 通过构造函数注入 [viewModelScope] 到 [shared], 所有方法转发到 [shared]。
 *
 * # 调用方兼容
 *
 * [RuleSubActivity] 调用方式保持不变:
 * - `viewModel.save(ruleSub)` / `viewModel.delete(ruleSub)` /
 *   `viewModel.upOrder(ruleSubs)` / `viewModel.toTop(ruleSub)` / `viewModel.toBottom(ruleSub)`
 * - 方法签名完全一致
 *
 * # Android 专属依赖移除
 *
 * - 原 `io.legado.app.data.appDb` 已在 [shared] 中替换为 `AppDbProviders.get()`;
 * - 本类不再 import Android 专属 API (除 BaseViewModel / viewModelScope)。
 */
class RuleSubViewModel(app: Application) : BaseViewModel(app) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope]。
     *
     * 无平台专属 lambda 注入 (本 VM 全部操作走 DAO, 无 Android 专属依赖)。
     */
    private val shared: RuleSubViewModelShared = RuleSubViewModelShared(
        scope = viewModelScope,
    )

    fun save(ruleSub: RuleSub) = shared.save(ruleSub)

    fun delete(ruleSub: RuleSub) = shared.delete(ruleSub)

    fun upOrder(items: List<RuleSub>) = shared.upOrder(items)

    fun toTop(ruleSub: RuleSub) = shared.toTop(ruleSub)

    fun toBottom(ruleSub: RuleSub) = shared.toBottom(ruleSub)
}
