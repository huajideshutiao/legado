package io.legado.app.model.tvbox

import io.legado.app.data.entities.VirtualPluginSourcePrefix
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * TVBox 站点 ↔ legado 数据实体身份映射 (与 AnimeSourceMapper 同构):
 *
 * - 虚拟书源 URL: `tvbox://<siteKey>` (站点 key 即 TVBox 配置 sites[].key);
 * - Book.bookUrl: `tvbox://<siteKey>/<urlencode(vod_id)>`, 详情/目录/取数由它反解 vod_id;
 * - 剧集 id 与播放线路 flag 存 BookChapter.url/tag, 取播时回传 spider.playerContent。
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

    /** 由 Book.bookUrl 反解 vod_id; 无法反解返回 null (委派据此快速失败)。 */
    fun vodIdOf(bookUrl: String, siteKey: String): String? {
        val prefix = "$SOURCE_URL_PREFIX$siteKey/"
        if (!bookUrl.startsWith(prefix)) return null
        return runCatching { URLDecoder.decode(bookUrl.removePrefix(prefix), "UTF-8") }
            .getOrNull()
    }
}
