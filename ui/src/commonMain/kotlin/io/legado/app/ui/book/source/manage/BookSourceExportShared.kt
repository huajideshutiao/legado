package io.legado.app.ui.book.source.manage

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.book.source.BookSourceSort
import io.legado.app.utils.cnCompare

/**
 * 书源导出编排 (commonMain, 四端共用)。
 *
 * 取数 + 排序 + 选中比例分流 + 危险 API 开关清理, 文件序列化与落盘由各端实现。
 *
 * 排序与分流语义逐字对照原版 BookSourceViewModel (archive d0c42f3242):
 * - 六种排序的基准方向: Weight/Name/Url/Respond 升序, Update 降序, Enable 先启用后禁用
 *   再按名称, `sortAscending=false` 时整体反转;
 * - 选中比例分流: 全选按排序输出, 比例 < 0.3 只输出选中项不排序, 其余排序后过滤出选中项;
 * - 导出前一律关闭 enableDangerousApi。
 */
/** 按界面排序规则取书源, 对照原版 BookSourceViewModel.getBookSources。 */
suspend fun getSortedBookSources(
    selection: List<BookSourcePart>,
    sortAscending: Boolean,
    sort: BookSourceSort
): List<BookSource> {
    val data = AppDbProviders.get().bookSourceDao.getBookSourcesFix(selection.map { it.bookSourceUrl })
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
    return if (!sortAscending) tmp.reversed() else tmp
}

/**
 * 导出用书源列表, 对照原版 BookSourceViewModel.saveToFile 的分流与危险 API 清理段。
 *
 * @param selection 界面选中的书源
 * @param allCount 当前列表条目总数, 用于计算选中比例 (对照原版 adapter.itemCount)
 */
suspend fun getExportBookSources(
    selection: List<BookSourcePart>,
    allCount: Int,
    sortAscending: Boolean,
    sort: BookSourceSort
): List<BookSource> {
    val selectedRate = selection.size.toFloat() / allCount.toFloat()
    val sources = if (selectedRate == 1f) {
        getSortedBookSources(selection, sortAscending, sort)
    } else if (selectedRate < 0.3) {
        AppDbProviders.get().bookSourceDao.getBookSourcesFix(selection.map { it.bookSourceUrl })
    } else {
        val keys = selection.map { it.bookSourceUrl }.toHashSet()
        getSortedBookSources(selection, sortAscending, sort).filter {
            keys.contains(it.bookSourceUrl)
        }
    }
    sources.forEach { if (it.enableDangerousApi == true) it.enableDangerousApi = false }
    return sources
}
