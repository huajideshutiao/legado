package io.legado.app.ui.book.group

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.BookGroup

/**
 * 书架分组管理 ViewModel (Android 端)。
 *
 * # KMP 化重构说明
 *
 * 全部业务方法 (upGroup / addGroup / delGroup) 已下沉到 shared commonMain
 * [GroupViewModelShared], 内部用 `scope.launch(Dispatchers.IO) + try/catch/finally`
 * 替代 `execute { ... }.onFinally { ... }`, 行为等价 (见 shared 类注释)。
 *
 * # 设计选择 (组合委托)
 *
 * 本类必须继承 [BaseViewModel] (AndroidViewModel 子类, 提供 `execute` / `context` /
 * `viewModelScope`), Kotlin 单继承无法同时继承 [GroupViewModelShared],
 * 改用组合委托模式持有 [shared] 实例, 转发全部方法, 调用方代码零改动。
 *
 * # 调用方兼容
 *
 * 调用方 (GroupEditDialog / GroupManageDialog / GroupSelectDialog) 调用方式保持不变:
 * - `viewModel.upGroup(item.copy(show = it))` (无 finally 回调)
 * - `viewModel.upGroup(*groups.toTypedArray())` (无 finally 回调, 持久化拖拽顺序)
 * - `viewModel.upGroup(it) { dismiss() }` (带 finally 回调, 编辑后关闭对话框)
 * - `viewModel.addGroup(name, bookSort, enableRefresh, coverPath) { dismiss() }`
 * - `viewModel.delGroup(it) { dismiss() }`
 *
 * 返回值由 `Coroutine<Unit>` 改为 `Unit`: 所有调用方均 fire-and-forget 不使用返回值,
 * 不影响调用方 (见 shared 类注释)。
 */
class GroupViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心 VM (KMP), 注入 [viewModelScope]。
     *
     * 原 `execute { ... }.onFinally { ... }` 已下沉 shared, shared 内部用
     * `scope.launch(Dispatchers.IO) { try { ... } catch (e) { e.printOnDebug() }
     * finally { withContext(mainDispatcher) { finally() } } }` 等价实现,
     * finally 回调经 `withContext(mainDispatcher)` 切到主线程调用 (与原 onFinally
     * 默认 executeContext=mainDispatcher 一致, dismiss() 等 UI 操作必须在主线程)。
     */
    private val shared: GroupViewModelShared = GroupViewModelShared(
        scope = viewModelScope,
    )

    /**
     * 更新分组 (转发到 [shared.upGroup])。
     *
     * @param bookGroup 待更新的分组 (可变参)
     * @param finally 完成回调 (成功 / 失败均触发), 典型为 `dismiss()`; null 表示无回调
     */
    fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) =
        shared.upGroup(*bookGroup, finally = finally)

    /**
     * 新增分组 (转发到 [shared.addGroup])。
     *
     * @param groupName 分组名
     * @param bookSort 书排序
     * @param enableRefresh 是否启用刷新
     * @param cover 封面 (可空)
     * @param finally 完成回调 (成功 / 失败均触发), 典型为 `dismiss()`
     */
    fun addGroup(
        groupName: String,
        bookSort: Int,
        enableRefresh: Boolean,
        cover: String?,
        finally: () -> Unit
    ) = shared.addGroup(groupName, bookSort, enableRefresh, cover, finally)

    /**
     * 删除分组 (转发到 [shared.delGroup])。
     *
     * @param bookGroup 待删除的分组
     * @param finally 完成回调 (成功 / 失败均触发), 典型为 `dismiss()`
     */
    fun delGroup(bookGroup: BookGroup, finally: () -> Unit) =
        shared.delGroup(bookGroup, finally)
}
