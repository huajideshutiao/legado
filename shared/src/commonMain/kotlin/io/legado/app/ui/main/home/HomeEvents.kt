package io.legado.app.ui.main.home

import io.legado.app.data.entities.HomeSection

/** 主页分组结构变更事件，外壳监听重建 ViewPager 与 TabLayout。 */
data class HomeTabEvent(
    val action: String,
    /** rename 时为旧标题；其它操作可为空 */
    val oldTitle: String? = null,
    /** rename / add 时为新标题；其它操作可为空 */
    val newTitle: String? = null
) {
    companion object {
        const val ADD = "add"
        const val REMOVE = "remove"
        const val RENAME = "rename"
        const val REORDER = "reorder"
    }
}

/** 主页展示项变更事件，所属 HomeTabFragment 按 tabTitle 过滤后做增量更新。 */
data class HomeSectionEvent(
    val action: String,
    val tabTitle: String,
    val section: HomeSection? = null
) {
    companion object {
        const val ADD = "add"
        const val UPDATE = "update"
        const val REMOVE = "remove"
        const val REORDER = "reorder"
    }
}
