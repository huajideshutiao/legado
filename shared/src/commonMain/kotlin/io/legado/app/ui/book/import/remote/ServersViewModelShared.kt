package io.legado.app.ui.book.import.remote

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 服务器列表删除 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ServersViewModel(application: Application) : BaseViewModel(application)`:
 * - 仅一个 `delete(server)` 方法, 依赖 DAO + 协程, 不依赖 Android 专属 API,
 *   可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get].serverDao (宿主启动时注册;
 *   AppDbAccessor 接口已暴露 serverDao)。
 * - 原 `execute { appDb.serverDao.delete(server) }` (BaseViewModel 内 Coroutine.async
 *   + try/catch) 改为 `scope.launch(Dispatchers.IO) { appDb.serverDao.delete(server) }`,
 *   行为等价 (DAO 方法 suspend, Room 内部切线程; 保留 IO 调度器与原 `execute` 默认
 *   Dispatchers.IO 一致)。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (参考
 * `ReplaceRuleViewModelShared`):
 * - app 端 ServersViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 通过构造函数注入 [scope] (app 端 = `viewModelScope`);
 * - `delete` 方法转发到本类, app 端调用方接口不变。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = `rememberCoroutineScope()`)
 */
class ServersViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    fun delete(server: Server) {
        scope.launch(Dispatchers.IO) {
            appDb.serverDao.delete(server)
        }
    }
}
