package io.legado.app.ui.book.source.manage

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.toBookSource
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.cnCompare
import io.legado.app.utils.outputStream
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 书源管理数据修改 VM (Android 端组合委托包装)。
 *
 * # 背景
 *
 * 对照 [BookSourceViewModelShared] (commonMain 共享核心), 本类仅做 Android 专属适配:
 * - 16 个纯 DAO 方法 (topSource/bottomSource/del/update/upOrder/enable/disable/
 *   enableExplore/disableExplore/disableSelectExplore/selectionAddToGroups/selectionRemoveFromGroups/
 *   addGroup/upGroup/delGroup) 全部转发到 [shared], 由 shared 内部走
 *   `Coroutine.async(scope) { AppDbProviders.get().bookSourceDao.xxx }` 完成实际 DAO 操作
 *   (替代原 `execute { appDb.bookSourceDao.xxx }`, 行为等价);
 * - **saveToFile (2 个重载) / getBookSources**: 依赖 `context.filesDir` +
 *   `FileUtils.createFileWithReplace` + `GSON.writeToOutputStream` + 文件流 +
 *   `List<BookSourcePart>.toBookSource()` app 端扩展 (该扩展内部 runBlocking 调
 *   appDb.bookSourceDao.getBookSourcesFix) + `String.cnCompare` Android ICU 扩展,
 *   不能下沉 commonMain, 留本类实现;
 * - **scope 注入**: `viewModelScope` (BaseViewModel 来自 AndroidViewModel) 传入 [shared]。
 *
 * # 行为等价性
 *
 * - 转发方法的调用方接口 (方法名/参数/返回值) 完全不变, BookSourceActivity 等调用方零改动;
 * - 原 `execute { ... }` 内的业务在 IO 跑, shared 端 `Coroutine.async(scope = viewModelScope)`
 *   默认 `context = Dispatchers.IO`, 行为一致;
 * - 原 `TextUtils.join(",", set)` 在 shared.upGroup 内改为 `set.joinToString(",")`,
 *   行为等价 (Kotlin stdlib, 无 Android 依赖);
 * - 原 `delGroup` 嵌套 `execute { execute { ... } }` 在 shared.delGroup 内合并为单层
 *   `Coroutine.async { ... }`, 见 [BookSourceViewModelShared.delGroup] 注释, 行为等价。
 *
 * # 设计参考
 *
 * 对照同模块 [io.legado.app.ui.replace.edit.ReplaceEditViewModel] 的组合委托模式
 * (持有 `ReplaceEditViewModelShared`), 以及
 * [io.legado.app.ui.book.group.GroupViewModel] (持有 `GroupViewModelShared`)。
 */
class BookSourceViewModel(application: Application) : BaseViewModel(application) {

    /**
     * 共享核心, 注入 `viewModelScope`。
     *
     * `viewModelScope`: AndroidViewModel 提供, Activity 销毁时自动取消,
     * 与原 `execute { ... }` 默认 `scope = viewModelScope` 行为一致。
     */
    private val shared: BookSourceViewModelShared = BookSourceViewModelShared(
        scope = viewModelScope,
    )

    /** 置顶书源, 转发到 [BookSourceViewModelShared.topSource]。 */
    fun topSource(vararg sources: BookSourcePart) {
        shared.topSource(*sources)
    }

    /** 置底书源, 转发到 [BookSourceViewModelShared.bottomSource]。 */
    fun bottomSource(vararg sources: BookSourcePart) {
        shared.bottomSource(*sources)
    }

    /** 删除书源, 转发到 [BookSourceViewModelShared.del]。 */
    fun del(sources: List<BookSourcePart>) {
        shared.del(sources)
    }

    /** 更新书源, 转发到 [BookSourceViewModelShared.update]。 */
    fun update(vararg bookSource: BookSource) {
        shared.update(*bookSource)
    }

    /** 批量更新自定义排序, 转发到 [BookSourceViewModelShared.upOrder]。 */
    fun upOrder(items: List<BookSourcePart>) {
        shared.upOrder(items)
    }

    /** 启用/禁用书源, 转发到 [BookSourceViewModelShared.enable]。 */
    fun enable(enable: Boolean, items: List<BookSourcePart>) {
        shared.enable(enable, items)
    }

    /** 启用所选书源, 转发到 [BookSourceViewModelShared.enableSelection]。 */
    fun enableSelection(sources: List<BookSourcePart>) {
        shared.enableSelection(sources)
    }

    /** 禁用所选书源, 转发到 [BookSourceViewModelShared.disableSelection]。 */
    fun disableSelection(sources: List<BookSourcePart>) {
        shared.disableSelection(sources)
    }

    /** 启用/禁用发现, 转发到 [BookSourceViewModelShared.enableExplore]。 */
    fun enableExplore(enable: Boolean, items: List<BookSourcePart>) {
        shared.enableExplore(enable, items)
    }

    /** 启用所选书源发现, 转发到 [BookSourceViewModelShared.enableSelectExplore]。 */
    fun enableSelectExplore(sources: List<BookSourcePart>) {
        shared.enableSelectExplore(sources)
    }

    /** 禁用所选书源发现, 转发到 [BookSourceViewModelShared.disableSelectExplore]。 */
    fun disableSelectExplore(sources: List<BookSourcePart>) {
        shared.disableSelectExplore(sources)
    }

    /** 批量加入分组, 转发到 [BookSourceViewModelShared.selectionAddToGroups]。 */
    fun selectionAddToGroups(sources: List<BookSourcePart>, groups: String) {
        shared.selectionAddToGroups(sources, groups)
    }

    /** 批量移出分组, 转发到 [BookSourceViewModelShared.selectionRemoveFromGroups]。 */
    fun selectionRemoveFromGroups(sources: List<BookSourcePart>, groups: String) {
        shared.selectionRemoveFromGroups(sources, groups)
    }

    /**
     * 导出书源到文件 (List<BookSource> 重载), 留 app 端。
     *
     * 依赖 `context.filesDir` + `FileUtils.createFileWithReplace` +
     * `GSON.writeToOutputStream` + 文件流, 不能下沉 commonMain。
     *
     * @param sources 待导出的书源列表 (已处理 enableDangerousApi=false)
     * @param success 导出成功回调, 参数为输出文件
     */
    private fun saveToFile(sources: List<BookSource>, success: (file: File) -> Unit) {
        execute {
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, sources)
            }
            file
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    /**
     * 导出书源到文件 (List<BookSourcePart> 重载), 留 app 端。
     *
     * 根据选中比例选择数据源 (全量 / 仅选中 / 过滤选中), 关闭 enableDangerousApi 后
     * 调 [saveToFile] (List<BookSource> 重载) 写文件。
     *
     * @param selection 选中的书源 (BookSourcePart)
     * @param allCount 全部书源总数 (用于计算选中比例)
     * @param sortAscending 是否升序
     * @param sort 排序方式
     * @param success 导出成功回调, 参数为输出文件
     */
    fun saveToFile(
        selection: List<BookSourcePart>,
        allCount: Int,
        sortAscending: Boolean,
        sort: BookSourceSort,
        success: (file: File) -> Unit
    ) {
        execute {
            val selectedRate = selection.size.toFloat() / allCount.toFloat()
            val sources = if (selectedRate == 1f) {
                getBookSources(selection, sortAscending, sort)
            } else if (selectedRate < 0.3) {
                selection.toBookSource()
            } else {
                val keys = selection.map { it.bookSourceUrl }.toHashSet()
                val bookSources = getBookSources(selection, sortAscending, sort)
                bookSources.filter {
                    keys.contains(it.bookSourceUrl)
                }
            }
            sources.forEach { if (it.enableDangerousApi == true) it.enableDangerousApi = false }
            saveToFile(sources, success)
        }
    }

    /**
     * 取排序后的书源列表, 留 app 端。
     *
     * 依赖 `appDb.bookSourceDao.getBookSourcesFix` (runBlocking) +
     * `String.cnCompare` Android ICU 扩展, 不能下沉 commonMain。
     *
     * @param selection 选中的书源 (BookSourcePart, 仅取 bookSourceUrl 用于查询)
     * @param sortAscending 是否升序
     * @param sort 排序方式
     */
    private fun getBookSources(
        selection: List<BookSourcePart>,
        sortAscending: Boolean,
        sort: BookSourceSort
    ): List<BookSource> {
        return runBlocking { appDb.bookSourceDao.getBookSourcesFix(selection.map { it.bookSourceUrl }) }
        .let { data ->
            val tmp = when (sort) {
                BookSourceSort.Weight -> data.sortedBy { it.weight }
                BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                    o1.bookSourceName.cnCompare(o2.bookSourceName)
                }
                BookSourceSort.Url -> data.sortedBy { it.bookSourceUrl }
                BookSourceSort.Update -> data.sortedByDescending { it.lastUpdateTime }
                BookSourceSort.Respond -> data.sortedBy { it.respondTime }
                BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                    var sortNum = -o1.enabled.compareTo(o2.enabled)
                    if (sortNum == 0) {
                        sortNum = o1.bookSourceName.cnCompare(o2.bookSourceName)
                    }
                    sortNum
                }
                else -> data
            }
            if (!sortAscending) tmp.reversed() else tmp
        }
    }

    /** 给所有未分组书源设置分组, 转发到 [BookSourceViewModelShared.addGroup]。 */
    fun addGroup(group: String) {
        shared.addGroup(group)
    }

    /** 重命名分组, 转发到 [BookSourceViewModelShared.upGroup]。 */
    fun upGroup(oldGroup: String, newGroup: String?) {
        shared.upGroup(oldGroup, newGroup)
    }

    /** 删除分组, 转发到 [BookSourceViewModelShared.delGroup]。 */
    fun delGroup(group: String) {
        shared.delGroup(group)
    }

}
