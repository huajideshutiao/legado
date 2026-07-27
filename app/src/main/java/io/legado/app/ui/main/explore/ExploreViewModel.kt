package io.legado.app.ui.main.explore

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.explore.ExploreViewModelShared

/**
 * 发现页源置顶/删除 VM (Android 端, 组合委托)。
 *
 * # KMP 化重构说明
 *
 * 核心业务编排 (`topSource` / `deleteSource`) 已下沉到 shared commonMain
 * [ExploreViewModelShared], 本类仅作薄壳: 继承 [BaseViewModel] (供 ViewModelStore
 * 持有 + viewModelScope 注入), 内部持有 [shared] 实例, 转发两个方法。
 *
 * 调用方 ([ExploreTab]) 接口不变: `viewModel.topSource(source)` / `viewModel.deleteSource(source)`。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (参考 BookInfoViewModel):
 * - 本类 `extends BaseViewModel`, 内部持有 [shared] 实例;
 * - 仅注入 `scope = viewModelScope` 一个参数;
 * - `topSource` / `deleteSource` 全部转发到 [shared]。
 */
class ExploreViewModel(application: Application) : BaseViewModel(application) {

    /** 共享核心 VM (KMP), 注入 [viewModelScope] 供 shared 内部协程使用。 */
    private val shared: ExploreViewModelShared = ExploreViewModelShared(
        scope = viewModelScope,
    )

    /** 置顶书源, 转发到 [shared.topSource]。 */
    fun topSource(bookSource: BookSourcePart) = shared.topSource(bookSource)

    /** 删除书源, 转发到 [shared.deleteSource]。 */
    fun deleteSource(source: BookSourcePart) = shared.deleteSource(source)
}
