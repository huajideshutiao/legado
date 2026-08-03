package io.legado.app.ui.book.group

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.coroutine.printStackTraceOnDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架分组管理 ViewModel 共享核心 (commonMain)。
 *
 * 对照 app 端 `GroupViewModel(app) : BaseViewModel(app)`: 全部方法都是纯 DAO 写操作
 * (bookGroupDao.update/insert/delete/getUnusedId/maxOrder/getByID + bookDao.removeGroup),
 * 不依赖 Android 专属 API, 可下沉多端复用。DAO 走 [AppDbProviders.get]。
 *
 * onFinally 模式: 原 `execute { dao }.onFinally { finally }` (IO 执行 + mainDispatcher 回调)
 * 下沉为 `scope.launch(Dispatchers.IO) { try { dao } catch { printStackTraceOnDebug() }
 * finally { withContext(mainDispatcher) { finally?.invoke() } } }`, 行为等价
 * (finally 典型为 dismiss() 必须在主线程; cancel 时抛 CancellationException 同样进 finally)。
 *
 * 设计: 组合委托 (BaseViewModel 是 AndroidViewModel 不能继承), 仅注入 [scope],
 * 调用方 (GroupEditDialog 等) 代码零改动。
 *
 * @param scope 协程作用域 (Android = viewModelScope / 桌面 = 应用主作用域)
 */
class GroupViewModelShared(
    private val scope: CoroutineScope,
) {

    /** DAO 容器 (宿主启动时由 app 端注册 AppDbAccessorImpl)。 */
    private val appDb get() = AppDbProviders.get()

    /**
     * 更新分组 (对照原 GroupViewModel.upGroup)。
     *
     * 见类注释中的 onFinally 对照说明。`finally` 回调经 [withContext]([mainDispatcher])
     * 切到主线程调用, 与原 `onFinally` 默认 `executeContext = mainDispatcher` 等价。
     *
     * @param bookGroup 待更新的分组 (可变参)
     * @param finally 完成回调 (成功 / 失败均触发), 典型为 `dismiss()`; null 表示无回调
     */
    fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) {
        scope.launch(IoDispatcher) {
            try {
                appDb.bookGroupDao.update(*bookGroup)
            } catch (e: Throwable) {
                // 与原 Coroutine 默认行为等价: e.printStackTraceOnDebug() 不上报 (release 不打栈)
                e.printStackTraceOnDebug()
            } finally {
                if (finally != null) withContext(mainDispatcher) { finally.invoke() }
            }
        }
    }

    /**
     * 新增分组 (对照原 GroupViewModel.addGroup)。
     *
     * 见类注释中的 onFinally 对照说明。`finally` 回调必填非空 (调用方 GroupEditDialog
     * 总是传 `dismiss()`), 同样经 [withContext]([mainDispatcher]) 切到主线程调用。
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
    ) {
        scope.launch(IoDispatcher) {
            try {
                val groupId = appDb.bookGroupDao.getUnusedId()
                val bookGroup = BookGroup(
                    groupId = groupId,
                    groupName = groupName,
                    cover = cover,
                    bookSort = bookSort,
                    enableRefresh = enableRefresh,
                    order = appDb.bookGroupDao.maxOrder().plus(1)
                )
                appDb.bookGroupDao.getByID(groupId) ?: appDb.bookDao.removeGroup(groupId)
                appDb.bookGroupDao.insert(bookGroup)
            } catch (e: Throwable) {
                // 与原 Coroutine 默认行为等价: e.printStackTraceOnDebug() 不上报 (release 不打栈)
                e.printStackTraceOnDebug()
            } finally {
                withContext(mainDispatcher) { finally() }
            }
        }
    }

    /**
     * 删除分组 (对照原 GroupViewModel.delGroup)。
     *
     * 见类注释中的 onFinally 对照说明。`finally` 回调必填非空 (调用方 GroupEditDialog
     * 总是传 `dismiss()`), 同样经 [withContext]([mainDispatcher]) 切到主线程调用。
     *
     * @param bookGroup 待删除的分组
     * @param finally 完成回调 (成功 / 失败均触发), 典型为 `dismiss()`
     */
    fun delGroup(bookGroup: BookGroup, finally: () -> Unit) {
        scope.launch(IoDispatcher) {
            try {
                appDb.bookGroupDao.delete(bookGroup)
                appDb.bookDao.removeGroup(bookGroup.groupId)
            } catch (e: Throwable) {
                // 与原 Coroutine 默认行为等价: e.printStackTraceOnDebug() 不上报 (release 不打栈)
                e.printStackTraceOnDebug()
            } finally {
                withContext(mainDispatcher) { finally() }
            }
        }
    }
}
