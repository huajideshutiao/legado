package io.legado.app.ui.book.import.remote

import io.legado.app.data.entities.Book
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.ImportStageOwner
import io.legado.app.ui.root.ScreenModel
import io.legado.app.ui.root.ensureBookSaveDirSelected
import io.legado.app.ui.root.screenModelScope
import io.legado.app.ui.root.toRouteRef
import kotlinx.coroutines.cancel
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 远程书籍页的宿主持有者 (按路由 entry 从 [io.legado.app.ui.root.ScreenModelStore] 取)。
 *
 * 为什么需要它: 本页原先在组合里 `remember(scope) { RemoteBookViewModelShared(...) }`,
 * 业务协程挂的是组合期 `rememberCoroutineScope()` —— 配置重建/组合被放弃就全部作废, 也不存在
 * "页面请求真退出才撤"的持有者。这里把三样东西收到模型上 (随 entry 存活):
 * 1. **业务 scope**: 远程书列表/上传/上架的请求都归它, [onCleared] 才取消;
 * 2. **现有的 [RemoteBookViewModelShared]**: 业务逻辑不动, 只换成模型持有的 scope;
 * 3. **[ImportStageOwner]**: 本页的用户选择阶段 (压缩包缺包确认/候选), 归本页请求, 由本页组合呈现。
 *
 * 导航不保存 navigator 闭包: 模型只把"要去的路由"放进 [pendingNavigation], 由**当前**路由
 * 组合消费并 push —— 重建后用的是新 navigator, 不会 push 到旧闭包上。
 */
class RemoteBookScreenModel : ScreenModel {

    /** 本页业务请求作用域 (自管, 真清理见 [onCleared])。 */
    private val businessScope = screenModelScope("远程书籍")

    /** 本页请求作用域: 页面专属动作 (选目录等) 也归它, 真退出即撤。 */
    val scope get() = businessScope

    /** 本页用户选择阶段 (候选/确认): 归本页请求, 由本页组合呈现。 */
    val importStages = ImportStageOwner()

    // 待交导航: 每请求一条 (id → 路由), 由当前 UI 按条交付并只消费本条 ——
    // 单槽会被后到的请求覆盖 (前一本书的阅读导航直接丢失), 与 BookInfoScreenModel 的
    // pendingReaderNavs 同一形状
    private val _pendingNavigation = MutableStateFlow<Map<Long, AppRoute>>(emptyMap())
    val pendingNavigation: StateFlow<Map<Long, AppRoute>> = _pendingNavigation.asStateFlow()
    // 发号用原子量: startRead 从 IO 回调 (onStartRead) 进入, 普通 ++ 会并发撞号;
    // 且在 update lambda 里自增会随 lambda 重试重复取号, 故发号放在 update 之外
    private val navSeq = atomic(0L)

    val shared = RemoteBookViewModelShared(
        scope = businessScope,
        // 已上架书点阅读: 交当前路由去 push (app 端 startReadBook / 桌面切 READER 路由同一语义)
        onStartRead = { book: Book ->
            val id = navSeq.incrementAndGet()
            _pendingNavigation.update { it + (id to AppRoute.Reader(book.toRouteRef())) }
        },
        // 上架要落盘: 保存位置不可用时挂起等用户真选定 (取消即正常结束), 归本页请求
        onPermissionDenied = {
            businessScope.launch {
                ensureBookSaveDirSelected(currentCoroutineContext()[Job])
            }
        },
        stages = importStages,
    )

    /** 某条待交导航已真实分发: 只消费本条 (别的请求的待交结果不动)。 */
    fun consumeNavigation(id: Long) {
        _pendingNavigation.update { it - id }
    }

    override fun onCleared() {
        // 页面真退出: 本页业务请求与目录选择一起撤; 待交导航已无人消费, 一并清掉
        // (被覆盖/退后台时不清 —— 那时只是本页不可见, 结果要留给回来的 UI)
        _pendingNavigation.value = emptyMap()
        businessScope.cancel()
    }
}
