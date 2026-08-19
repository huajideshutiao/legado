package io.legado.app.ui.book.import.remote

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Server
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.toast.Toasters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 服务器配置 VM 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `ServerConfigViewModel(application: Application) : BaseViewModel(application)`:
 * - `init(id, onSuccess)` / `save(server, onSuccess)` 仅依赖 DAO + 协程 + toastOnUi,
 *   可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get].serverDao (宿主启动时注册;
 *   AppDbAccessor 接口已暴露 serverDao)。
 * - 原 `execute { ... }.onSuccess { ... }` (BaseViewModel.Coroutine.async, onSuccess
 *   默认在 Main 线程回调) 改为 `scope.launch(Dispatchers.IO) { ... ; onSuccess.invoke() }`,
 *   onSuccess 在 IO 线程内同步调用 (Compose mutableStateOf 线程安全, 行为等价;
 *   与 [io.legado.app.ui.dict.rule.DictRuleViewModelShared] 的 try/catch 简化模式一致)。
 * - 原 `execute { ... }.onError { context.toastOnUi(...) }` 改为 `try { ... } catch (e) {
 *   Toasters.get().toast(msg) }`, 行为等价 (Toaster 接口已下沉 commonMain,
 *   androidMain 注册的实现内部切主线程, 与 `context.toastOnUi` 一致)。
 *
 * # mServer 状态字段
 *
 * 原 app 端 `var mServer: Server? = null` 是 VM 可变状态字段 (init 加载, save 读写),
 * 下沉后保留为 [mServer] 公开可变字段, app 端组合委托 VM 通过 `shared.mServer` 读写,
 * 与原调用方 (ServerConfigDialog) 通过 `viewModel.mServer` 访问的接口一致。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式 (参考
 * `ReplaceRuleViewModelShared`):
 * - app 端 ServerConfigViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 通过构造函数注入 [scope] (app 端 = `viewModelScope`);
 * - `init` / `save` / [mServer] 转发到本类, app 端调用方接口不变。
 *
 * # 实现细节保持
 *
 * - 原 `init` 的 `if (mServer != null) return` (旋转屏幕界面重新创建, 不重新加载)
 *   下沉后保持一致 (early return 避免重复加载)。
 * - 原 `save` 的 `mServer?.let { appDb.serverDao.delete(it) }` (先删旧再插新, 实现"替换")
 *   下沉后保持一致 (不改为 update, 保留原 delete + insert 结构, 避免改变实现逻辑)。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = `rememberCoroutineScope()`)
 */
class ServerConfigViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /** 当前编辑的服务器配置 (init 加载, save 读写; 旋转屏幕时保留避免重复加载)。 */
    var mServer: Server? = null

    fun init(id: Long?, onSuccess: () -> Unit) {
        // mServer 不为空可能是旋转屏幕界面重新创建, 不用更新数据
        if (mServer != null) return
        scope.launch(IoDispatcher) {
            mServer = if (id != null) {
                appDb.serverDao.get(id)
            } else {
                Server()
            }
            onSuccess.invoke()
        }
    }

    fun save(server: Server, onSuccess: () -> Unit) {
        scope.launch(IoDispatcher) {
            try {
                mServer?.let {
                    appDb.serverDao.delete(it)
                }
                mServer = server
                appDb.serverDao.insert(server)
                onSuccess.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 替代 context.toastOnUi("保存出错\n${it.message}"),
                // Toasters.get() 已下沉 commonMain, androidMain 注册的实现内部切主线程
                Toasters.get().toast("保存出错\n${e.message}")
            }
        }
    }
}
