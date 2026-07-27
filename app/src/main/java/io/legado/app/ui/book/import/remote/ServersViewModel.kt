package io.legado.app.ui.book.import.remote

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.Server

/**
 * 服务器列表删除 VM (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 全部业务逻辑 (`delete`) 已下沉到 shared commonMain [ServersViewModelShared]。
 * 本类采用**组合委托**模式持有 [shared] 实例, 不通过继承
 * [ServersViewModelShared]:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [ServersViewModelShared];
 * - 通过构造函数注入 [viewModelScope] 到 [shared], `delete` 方法转发到 [shared]。
 *
 * # 调用方兼容
 *
 * [ServersDialog] 调用方式保持不变: `viewModel.delete(server)`。
 *
 * # Android 专属依赖移除
 *
 * - 原 `io.legado.app.data.appDb` 直接 DAO 访问已移除, 改由 [shared] 走
 *   `AppDbProviders.get().serverDao`;
 * - 本类不再 import Android 专属 API (除 viewModelScope)。
 */
class ServersViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope]。
     *
     * 无平台专属 lambda 注入 (本 VM 仅 delete 操作走 DAO, 无 Android 专属依赖)。
     */
    private val shared: ServersViewModelShared = ServersViewModelShared(
        scope = viewModelScope,
    )

    fun delete(server: Server) = shared.delete(server)
}
