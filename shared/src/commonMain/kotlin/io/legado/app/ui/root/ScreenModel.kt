package io.legado.app.ui.root

/** 共享页面状态持有者；不允许持有 Activity、View 或平台控制器。 */
interface ScreenModel {
    fun onCleared() = Unit
}

object EmptyScreenModel : ScreenModel

/**
 * ScreenModel 生命周期与 RouteEntryId 绑定，而不是由各平台页面重复 remember/ViewModelProvider。
 *
 * 每个 [RouteContent] 分支按路由类型自行决定 factory, 不再依赖构造期单一 factory。
 */
class ScreenModelStore {
    private val models = mutableMapOf<RouteEntryId, ScreenModel>()

    fun getOrCreate(entry: RouteEntry, factory: () -> ScreenModel): ScreenModel =
        models.getOrPut(entry.id) { factory() }

    inline fun <reified T : ScreenModel> getOrCreateTyped(
        entry: RouteEntry,
        crossinline factory: () -> T,
    ): T = getOrCreate(entry) { factory() } as T

    fun retain(entries: List<RouteEntry>) {
        val activeIds = entries.mapTo(mutableSetOf()) { it.id }
        val removed = models.keys.filterNot { it in activeIds }
        removed.forEach { id -> models.remove(id)?.onCleared() }
    }

    fun clear() {
        models.values.forEach(ScreenModel::onCleared)
        models.clear()
    }

    val size: Int get() = models.size
}
