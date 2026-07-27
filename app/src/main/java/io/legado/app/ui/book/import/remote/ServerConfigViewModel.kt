package io.legado.app.ui.book.import.remote

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.Server

/**
 * 服务器配置 VM (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 全部业务逻辑 (`init` / `save` / `mServer` 状态) 已下沉到 shared commonMain
 * [ServerConfigViewModelShared]。本类采用**组合委托**模式持有 [shared] 实例,
 * 不通过继承 [ServerConfigViewModelShared]:
 * - 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 *   `viewModelScope`), Kotlin 单继承无法同时继承 [ServerConfigViewModelShared];
 * - 通过构造函数注入 [viewModelScope] 到 [shared], `init` / `save` 方法转发到 [shared];
 * - [mServer] 通过 getter/setter 转发到 [shared], 保持原 `var mServer` 接口不变。
 *
 * # 调用方兼容
 *
 * [ServerConfigDialog] 调用方式保持不变:
 * - `viewModel.init(id) { upConfigView(viewModel.mServer) }`
 * - `viewModel.save(getServer()) { dismissAllowingStateLoss() }`
 * - `viewModel.mServer?.copy()` 读访问。
 *
 * # Android 专属依赖移除
 *
 * - 原 `io.legado.app.data.appDb` 直接 DAO 访问已移除, 改由 [shared] 走
 *   `AppDbProviders.get().serverDao`;
 * - 原 `io.legado.app.utils.toastOnUi` (save 出错时调用) 已移除, 改由 [shared] 走
 *   `Toasters.get().toast(msg)` (androidMain 注册的实现内部切主线程, 行为等价);
 * - 本类不再 import Android 专属 API (除 viewModelScope)。
 */
class ServerConfigViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope]。
     *
     * 无平台专属 lambda 注入 (本 VM 仅 DAO + toast 操作, 无 Android 专属依赖)。
     */
    private val shared: ServerConfigViewModelShared = ServerConfigViewModelShared(
        scope = viewModelScope,
    )

    /**
     * 当前编辑的服务器配置 (转发到 [shared], 供 [ServerConfigDialog] 读写)。
     *
     * 保持原 `var mServer: Server? = null` 接口: get 直读 [shared], set 直写 [shared]。
     */
    var mServer: Server?
        get() = shared.mServer
        set(value) {
            shared.mServer = value
        }

    fun init(id: Long?, onSuccess: () -> Unit) = shared.init(id, onSuccess)

    fun save(server: Server, onSuccess: () -> Unit) = shared.save(server, onSuccess)
}
