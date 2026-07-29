package io.legado.app.ui.book.toc.rule

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultData

/**
 * TxtToc 规则 VM (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 全部业务逻辑 (9 个 DAO 写方法 + importDefault) 已下沉到 shared commonMain
 * [TxtTocRuleViewModelShared]。本类采用**组合委托**模式持有 [shared] 实例:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [TxtTocRuleViewModelShared];
 * - 通过构造函数注入 [viewModelScope] 到 [shared];
 * - 平台专属逻辑通过 lambda 注入 [shared]:
 *   - [DefaultData.importDefaultTocRules] 导入默认 TxtToc 规则 (依赖 Android assets,
 *     未下沉 commonMain)。
 *
 * # 调用方兼容
 *
 * [TxtTocRuleDialog] 调用方式保持不变:
 * - `viewModel.save(rule)` / `viewModel.del(rule)` / `viewModel.update(rule)` 等
 * - `viewModel.importDefault()` / `viewModel.toTop(rule)` / `viewModel.toBottom(rule)`
 * - 方法签名 (vararg 参数) 完全一致
 *
 * # DefaultData 处理
 *
 * [DefaultData.importDefaultTocRules] 依赖 Android assets (读 JSON),
 * 未下沉 commonMain。通过 lambda 注入 [shared], 在 shared 内 `scope.launch` 调用,
 * 与原 `execute { DefaultData.importDefaultTocRules() }` 行为等价。
 */
class TxtTocRuleViewModel(app: Application) : BaseViewModel(app) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 与一个平台专属 lambda。
     *
     * - `importDefaultRules`: 调 [DefaultData.importDefaultTocRules] 导入默认规则
     *   (DefaultData 依赖 Android assets, 未下沉)
     */
    private val shared: TxtTocRuleViewModelShared = TxtTocRuleViewModelShared(
        scope = viewModelScope,
        importDefaultRules = { DefaultData.importDefaultTocRules() },
    )

    fun save(txtTocRule: TxtTocRule) = shared.save(txtTocRule)

    fun del(vararg txtTocRule: TxtTocRule) = shared.del(*txtTocRule)

    fun update(vararg txtTocRule: TxtTocRule) = shared.update(*txtTocRule)

    fun importDefault() = shared.importDefault()

    fun toTop(vararg rules: TxtTocRule) = shared.toTop(*rules)

    fun toBottom(vararg sources: TxtTocRule) = shared.toBottom(*sources)

    fun upOrder() = shared.upOrder()

    fun enableSelection(vararg txtTocRule: TxtTocRule) = shared.enableSelection(*txtTocRule)

    fun disableSelection(vararg txtTocRule: TxtTocRule) = shared.disableSelection(*txtTocRule)
}
