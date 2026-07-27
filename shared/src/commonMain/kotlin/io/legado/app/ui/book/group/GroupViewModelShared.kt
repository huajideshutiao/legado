package io.legado.app.ui.book.group

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.coroutine.printOnDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书架分组管理 ViewModel 共享核心 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端原 `GroupViewModel(application: Application) : BaseViewModel(application)`:
 * - 全部方法都是纯 DAO 写操作 (bookGroupDao.update/insert/delete/getUnusedId/maxOrder/getByID +
 *   bookDao.removeGroup), 不依赖 Android 专属 API, 可以下沉 commonMain 供多端复用。
 * - DAO 访问走 [AppDbProviders.get] (宿主启动时注册), 替代 app 端 `appDb` 单例。
 * - [BookGroup] 实体已下沉 commonMain, bookGroupDao / bookDao 已在 [AppDbAccessor] 暴露,
 *   直接复用。
 *
 * # onFinally 模式对照
 *
 * 原 app 端:
 * ```
 * fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) {
 *     execute { appDb.bookGroupDao.update(*bookGroup) }.onFinally { finally?.invoke() }
 * }
 * ```
 * 下沉后:
 * ```
 * fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) {
 *     scope.launch(Dispatchers.IO) {
 *         try {
 *             appDb.bookGroupDao.update(*bookGroup)
 *         } catch (e: Throwable) {
 *             e.printOnDebug()
 *         } finally {
 *             if (finally != null) withContext(mainDispatcher) { finally.invoke() }
 *         }
 *     }
 * }
 * ```
 * 行为等价:
 * - 原 `execute` 默认 `context = Dispatchers.IO` 执行 DAO 写;
 * - 原 `onFinally` 默认 `executeContext = mainDispatcher` (Main) 调 finally 回调,
 *   调用方 finally 回调典型为 `dismiss()` (GroupEditDialog), 必须在主线程;
 *   shared 用 [withContext]([mainDispatcher]) 切到 Main 调 finally, 行为完全一致;
 * - 原 catch 块无 `onError`, [Coroutine] 默认 `e.printOnDebug()` (DEBUG 才打栈),
 *   shared 端直接调 [printOnDebug] expect fun, 行为完全一致;
 * - 原 `onFinally` 在 [Coroutine.executeInternal] 的 finally 块中执行,
 *   无论成功 / 失败 / cancel 都会执行 (除非 NonCancellable); shared 端 Kotlin
 *   `try { } catch { } finally { }` 同样无论成功 / 失败都执行, cancel 时由协程机制
 *   抛 CancellationException 进入 catch + finally, 行为等价。
 *
 * # 设计选择 (组合委托)
 *
 * 不采用 `expect abstract class` 让 app 端子类继承: BaseViewModel 是 AndroidViewModel,
 * commonMain 不可用, Kotlin 单继承会冲突。改用组合委托模式:
 * - app 端 GroupViewModel `extends BaseViewModel`, 内部持有本类实例;
 * - 仅注入 [scope] 一个参数 (Android = `viewModelScope`), 不算"超多";
 * - 转发全部方法到 shared, 调用方 (GroupEditDialog / GroupManageDialog /
 *   GroupSelectDialog) 代码零改动。
 *
 * @param scope 协程作用域, actual 平台注入
 *   (Android = `viewModelScope` / 桌面 = 应用主作用域 / 窗口 scope)
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
        scope.launch(Dispatchers.IO) {
            try {
                appDb.bookGroupDao.update(*bookGroup)
            } catch (e: Throwable) {
                // 与原 Coroutine 默认行为等价: e.printOnDebug() 不上报 (release 不打栈)
                e.printOnDebug()
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
        scope.launch(Dispatchers.IO) {
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
                // 与原 Coroutine 默认行为等价: e.printOnDebug() 不上报 (release 不打栈)
                e.printOnDebug()
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
        scope.launch(Dispatchers.IO) {
            try {
                appDb.bookGroupDao.delete(bookGroup)
                appDb.bookDao.removeGroup(bookGroup.groupId)
            } catch (e: Throwable) {
                // 与原 Coroutine 默认行为等价: e.printOnDebug() 不上报 (release 不打栈)
                e.printOnDebug()
            } finally {
                withContext(mainDispatcher) { finally() }
            }
        }
    }
}
