package io.legado.app.data.entities

/**
 * 插件虚拟书源 (漫画 / 视频 / TVBox) 的统一身份判定 —— 本文件是**唯一事实来源**。
 *
 * 三类插件装载时会把每个插件源映射成一行「虚拟 BookSource」落库, 使其出现在书源管理 /
 * 搜索范围 / 换源列表; 行内规则字段恒为空, 取数由宿主委派实现接管
 * (`MangaSourceDelegateImpl` / `VideoSourceDelegateImpl` / `TvBoxSourceDelegateImpl`)。
 * 这些行不是用户书源, **不可编辑 / 删除 / 换位 / 调试 / 搜索 / 登录**, 只保留
 * 「启用/禁用」与「发现开关」两种操作。
 *
 * 前缀常量原先各自散落在 app 模块的三个 Mapper (`MangaSourceMapper.SOURCE_URL_PREFIX` /
 * `AnimeSourceMapper.SOURCE_URL_PREFIX` / `TvBoxSourceMapper.SOURCE_URL_PREFIX`)。
 * 分层核实结论: `:app` 依赖 `:ui`, `:ui` 依赖 `:data`/`:foundation`/`:core`,
 * **反向依赖不成立** —— ui 层无法引用 app 模块的常量。故把前缀表下沉到 `:data`
 * (commonMain, 四端共享) 作为单一事实来源:
 * - app 侧三个 Mapper 的 `SOURCE_URL_PREFIX` 应改为引用本对象对应常量
 *   (见 [VirtualPluginSourcePrefix]), 不再各自持有字面量;
 * - ui 侧 (登录分流) 直接调用 [isVirtualPluginSource] 判定。
 *
 * 判定依据是 URL 前缀而非 [BookSource.bookSourceType]: 漫画插件源是 `image` 类型,
 * 与用户自建图片书源同类型, 类型字段无法区分; 前缀是三个委派 `handles()` 的唯一身份依据。
 */
@Suppress("unused")
object VirtualPluginSourcePrefix {

    /**
     * Mihon/keiyoushi 漫画插件源与 Aniyomi 视频插件源共用的前缀。
     *
     * 两者同前缀, 靠 [BookSource.bookSourceType] 区分 (漫画=image / 视频=video),
     * 委派侧 `MangaSourceDelegateImpl` / `VideoSourceDelegateImpl` 的 `handles()`
     * 本就是「类型 + 前缀」双条件判定, 同前缀不会引入分流歧义。
     */
    const val TACHIYOMI = "tachiyomi://"

    /** TVBox 站点虚拟源 (app 侧 `TvBoxSourceMapper.SOURCE_URL_PREFIX`)。 */
    const val TVBOX = "tvbox://"

    /**
     * 全部虚拟插件源前缀 (漫画 / Aniyomi 视频 / TVBox), 供统一判定遍历。
     *
     * 漫画与视频共用 [TACHIYOMI], 故此处只列不同值。
     */
    val ALL: List<String> = listOf(TACHIYOMI, TVBOX)
}

/** 该书源是否为插件虚拟源 (漫画 / 视频 / TVBox)。 */
fun BookSource.isVirtualPluginSource(): Boolean =
    VirtualPluginSourcePrefix.ALL.any { bookSourceUrl.startsWith(it) }

/** 插件虚拟源的运行时变量 key (存于 [Book.variableMap])。 */
object VirtualPluginVars {

}

/** 该书源列表投影是否为插件虚拟源 (漫画 / 视频 / TVBox)。 */
fun BookSourcePart.isVirtualPluginSource(): Boolean =
    VirtualPluginSourcePrefix.ALL.any { bookSourceUrl.startsWith(it) }
