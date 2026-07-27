package io.legado.app.ui.dict.rule

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.DictRule
import io.legado.app.help.DefaultData

/**
 * 字典规则 VM (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 全部业务逻辑 (6 个方法: update/delete/upSortNumber/enableSelection/
 * disableSelection/importDefault) 已下沉到 shared commonMain [DictRuleViewModelShared]。
 * 本类采用**组合委托**模式持有 [shared] 实例:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [DictRuleViewModelShared];
 * - 通过构造函数注入 [viewModelScope] 到 [shared];
 * - 平台专属逻辑通过 lambda 注入 [shared]:
 *   - [DefaultData.importDefaultDictRules] 导入默认字典规则 (依赖 Android assets,
 *     未下沉 commonMain)。
 *
 * # 调用方兼容
 *
 * [DictRuleActivity] 调用方式保持不变:
 * - `viewModel.update(rule)` / `viewModel.delete(rule)` / `viewModel.upSortNumber()` 等
 * - `viewModel.importDefault()` / `viewModel.enableSelection(rule)`
 * - 方法签名 (vararg 参数) 完全一致
 *
 * # Android 专属依赖移除
 *
 * - 原 `context.toastOnUi(msg)` (update/delete 的 onError) 已在 [shared] 中替换为
 *   `Toasters.get().toast(msg)` (Toaster 接口已下沉 commonMain, androidMain 注册的
 *   实现内部切主线程, 与 context.toastOnUi 行为等价);
 * - 原 [io.legado.app.constant.AppLog] 已下沉 commonMain, 直接由 [shared] 复用;
 * - 原 [io.legado.app.utils.toastOnUi] 不再 import (app 端不再直接调用)。
 *
 * # DefaultData 处理
 *
 * [DefaultData.importDefaultDictRules] 依赖 Android assets (读 JSON),
 * 未下沉 commonMain。通过 lambda 注入 [shared], 在 shared 内 `scope.launch` 调用,
 * 与原 `execute { DefaultData.importDefaultDictRules() }` 行为等价。
 */
class DictRuleViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope] 与一个平台专属 lambda。
     *
     * - `importDefaultRules`: 调 [DefaultData.importDefaultDictRules] 导入默认规则
     *   (DefaultData 依赖 Android assets, 未下沉)
     *
     * 注: update/delete 的 onError (AppLog + toast) 在 [shared] 内用 try/catch 捕获,
     * 通过已下沉的 [io.legado.app.constant.AppLog] + [io.legado.app.help.toast.Toasters]
     * 直接调用, 不需要 lambda 注入。
     */
    private val shared: DictRuleViewModelShared = DictRuleViewModelShared(
        scope = viewModelScope,
        importDefaultRules = { DefaultData.importDefaultDictRules() },
    )

    fun update(vararg dictRule: DictRule) = shared.update(*dictRule)

    fun delete(vararg dictRule: DictRule) = shared.delete(*dictRule)

    fun upSortNumber() = shared.upSortNumber()

    fun enableSelection(vararg dictRule: DictRule) = shared.enableSelection(*dictRule)

    fun disableSelection(vararg dictRule: DictRule) = shared.disableSelection(*dictRule)

    fun importDefault() = shared.importDefault()
}
