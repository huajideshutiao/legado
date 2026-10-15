package io.legado.app.ui.book.source

/**
 * 书源列表排序方式。
 */
enum class BookSourceSort {
    Default, Name, Url, Weight, Update, Enable, Respond
}

/**
 * 书源列表过滤条件: host 端把 searchKey 字面串映射到本 sealed class,
 * commonMain 不依赖 `R.string` 资源。
 */
sealed class SourceFilter {
    /** 全部 (对应空 searchKey) */
    object All : SourceFilter()
    /** 启用 (对应 R.string.enabled) */
    object Enabled : SourceFilter()
    /** 禁用 (对应 R.string.disabled) */
    object Disabled : SourceFilter()
    /** 需登录 (对应 R.string.need_login) */
    object NeedLogin : SourceFilter()
    /** 未分组 (对应 R.string.no_group) */
    object NoGroup : SourceFilter()
    /** 启用发现 (对应 R.string.enabled_explore) */
    object EnabledExplore : SourceFilter()
    /** 禁用发现 (对应 R.string.disabled_explore) */
    object DisabledExplore : SourceFilter()
    /** 按分组名筛选 (对应 searchKey.startsWith("group:")) */
    data class Group(val name: String) : SourceFilter()
    /** 关键字搜索 (对应 else 分支) */
    data class Search(val key: String) : SourceFilter()
}
