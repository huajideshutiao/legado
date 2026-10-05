package io.legado.app.model.tvbox

import io.legado.app.data.entities.VirtualPluginSourcePrefix
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * TVBox 站点 ↔ legado 数据实体身份映射 (与 AnimeSourceMapper 同构):
 *
 * - 虚拟书源 URL: `tvbox://<siteKey>` (站点 key 即 TVBox 配置 sites[].key);
 * - Book.bookUrl: `tvbox://<siteKey>/<urlencode(vod_id)>`, 详情/目录/取数由它反解 vod_id;
 * - 剧集 id 与播放线路 flag 存 BookChapter.url/tag, 取播时回传 spider.playerContent;
 * - 动作条目 (FongMi `Vod.action`) 与子分类条目 (`Vod.vod_tag=folder`) 不是可播放视频,
 *   借 legado 既有 `bookUrl 含 "::"` 伪 URL 分流进发现页 (见 [actionBookUrlOf]/[folderBookUrlOf])。
 */
object TvBoxSourceMapper {

    /**
     * 前缀取自 [VirtualPluginSourcePrefix] 单一事实来源 (ui 层书源守卫同表判定),
     * 不写裸字面量 —— 否则将来改前缀时管理页守卫会静默失配。
     */
    const val SOURCE_URL_PREFIX = VirtualPluginSourcePrefix.TVBOX

    fun siteUrlOf(siteKey: String): String = "$SOURCE_URL_PREFIX$siteKey"

    /** 虚拟书源 URL → siteKey; 多配置同 key 冲突时后导入者生效。 */
    fun siteKeyOf(siteUrl: String): String =
        siteUrl.removePrefix(SOURCE_URL_PREFIX).substringBefore('/')

    fun bookUrlOf(siteKey: String, vodId: String): String =
        "$SOURCE_URL_PREFIX$siteKey/${URLEncoder.encode(vodId, "UTF-8")}"

    /**
     * 动作条目的 bookUrl: `<可显示条目名>::<ACTION_SEGMENT_PREFIX><原始 action>`。
     *
     * legado 三处列表点击 (发现/搜索/主页) 都以 `bookUrl.split("::")` 长度 2 判定为
     * 分类跳转并 push 发现页, 且前段就是新页面的标题 (原版 `showBookInfo` 把
     * `urlParts[0]` 写进 `exploreName`)。
     *
     * 后段交给委派在 [io.legado.app.model.tvbox.TvBoxSourceDelegateImpl.getExploreAwait]
     * 里执行, UI 层零改动。
     */
    fun actionBookUrlOf(title: String, action: String): String =
        titleSegmentOf(title) + "::" + ACTION_SEGMENT_PREFIX + action

    /**
     * 子分类条目的 bookUrl: `<可显示条目名>::<folder 的 vod_id>`。
     *
     * FongMi 点击 `Vod.isFolder()` 时进下一层分类列表 (`openFolder(id, extend)`),
     * 语义等价于「发现页换一个分类」—— 本仓发现分类 url 段就是 [getExploreAwait] 的入参,
     * 故 folder 条目直接复用普通分类段, 不需要额外前缀。
     */
    fun folderBookUrlOf(title: String, folderId: String): String =
        titleSegmentOf(title) + "::" + folderId

    /**
     * 条目名 → 可安全放在 `"::"` 前段的标题段。
     *
     * `::` 是分隔符, 标题里自带它会把分类段切碎 (生态条目名常见 "第1集::上" 形态);
     * 故按既有约定 (对照 `AnimeSourceMapper.playableTitle`: `videoTitle.replace("::", "")`)
     * 把它换成单冒号。
     *
     * 必须循环替换: 非重叠替换在 `":::"` 这类输入上会重新拼出 `"::"` (例: `":::"` → `"::"`),
     * 一次替换不足以消除分隔符。
     */
    private fun titleSegmentOf(title: String): String {
        var text = title
        while (text.contains("::")) text = text.replace("::", ":")
        return text
    }

    /** 由 Book.bookUrl 反解 vod_id; 无法反解返回 null (委派据此快速失败)。 */
    fun vodIdOf(bookUrl: String, siteKey: String): String? {
        val prefix = "$SOURCE_URL_PREFIX$siteKey/"
        if (!bookUrl.startsWith(prefix)) return null
        return runCatching { URLDecoder.decode(bookUrl.removePrefix(prefix), "UTF-8") }
            .getOrNull()
    }

    /** 该发现分类段是否为 spider 动作 (而非普通分类/子分类)。 */
    fun isActionSegment(segment: String): Boolean = segment.startsWith(ACTION_SEGMENT_PREFIX)

    /** 动作段 → 原始 action 字符串 (FongMi `Vod.action` 原文, 原样透传给 `spider.action`)。 */
    fun actionOfSegment(segment: String): String = segment.removePrefix(ACTION_SEGMENT_PREFIX)

    /**
     * 动作段前缀。取 `action:` 而非裸值, 是为了与普通分类 id 及 folder 的 vod_id 区分 ——
     * 生态分类 id 常见纯数字/纯字母, 不会带冒号。
     */
    const val ACTION_SEGMENT_PREFIX = "action:"
}
