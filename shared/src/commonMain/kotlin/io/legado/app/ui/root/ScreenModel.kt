package io.legado.app.ui.root

import io.legado.app.constant.AppLog

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
        removed.forEach { id -> models.remove(id)?.let(::clearSafely) }
    }

    fun clear() {
        models.values.forEach(::clearSafely)
        models.clear()
    }

    /**
     * retain/clear 由 LegadoApp 的 LaunchedEffect 调用, onCleared 抛出会连坐整个 Recomposer
     * (桌面端表现为窗口能重绘但键鼠全失灵), 故逐个隔离并记下是哪个 ScreenModel。
     */
    private fun clearSafely(model: ScreenModel) {
        runCatching { model.onCleared() }
            .onFailure { AppLog.put("ScreenModel.onCleared 异常: ${model::class.simpleName}", it) }
    }

    val size: Int get() = models.size
}
