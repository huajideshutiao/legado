package io.legado.app.help.tvbox

import com.github.catvod.crawler.Spider
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 苹果 CMS V10 (MacCMS) JSON 直连站点的宿主侧 Spider 实现。
 *
 * TVBox 生态 type=0 站点 (api 直接为接口根 URL, 无需 jar) 走本实现; 契约按实测响应:
 * - 分类: `?ac=list` → {class:[{type_id,type_name}]}
 * - 列表: `?ac=videolist&t=<tid>&pg=<pg>` → {pagecount, list:[Vod]}
 * - 详情: `?ac=detail&ids=<vod_id>` → {list:[Vod]}
 * - 搜索: `?ac=videolist&wd=<key>&pg=<pg>` → {pagecount, list:[Vod]}
 * - Vod 的 vod_play_from/vod_play_url 与 jar spider 同款 "$$$" 分线路、"#" 分集、"集名$URL";
 *   播放值为绝对地址, playerContent 直接回直链 (parse=0) 并带上站点 UA/Referer 防盗链头。
 */
class TvBoxCmsSpider(private val site: TvBoxSite) : Spider() {

    override fun homeContent(filter: Boolean): String {
        val classes = JSONArray()
        val classList = get("ac=list").optJSONArray("class") ?: JSONArray()
        for (i in 0 until classList.length()) {
            val item = classList.optJSONObject(i) ?: continue
            classes.put(
                JSONObject()
                    .put("type_id", item.optString("type_id"))
                    .put("type_name", item.optString("type_name")),
            )
        }
        return JSONObject().put("class", classes).toString()
    }

    override fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String, String>?,
    ): String = listResultOf(get("ac=videolist&t=" + encode(tid.orEmpty()) + "&pg=" + encode(pg.orEmpty())))

    override fun detailContent(ids: List<String>?): String =
        listResultOf(get("ac=detail&ids=" + encode(ids.orEmpty().firstOrNull().orEmpty())))

    override fun searchContent(key: String?, quick: Boolean): String =
        searchContent(key, quick, "1")

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String =
        listResultOf(get("ac=videolist&wd=" + encode(key.orEmpty()) + "&pg=" + encode(pg.orEmpty())))

    /** 播放值为绝对地址: 直链回投 (parse=0), 防盗链头沿用站点 header + UA/Referer。 */
    override fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String {
        val headers = JSONObject()
        for ((key, value) in site.header) headers.put(key, value)
        headers.put("User-Agent", Util.CHROME)
        val base = site.api.substringBefore("?")
        headers.put("Referer", base.substringBeforeLast("/") + "/")
        return JSONObject()
            .put("parse", 0)
            .put("url", id.orEmpty())
            .put("header", headers)
            .toString()
    }

    private fun listResultOf(response: JSONObject): String {
        val list = JSONArray()
        val source = response.optJSONArray("list") ?: JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            list.put(
                JSONObject()
                    .put("vod_id", item.optString("vod_id"))
                    .put("vod_name", item.optString("vod_name"))
                    .put("vod_pic", item.optString("vod_pic"))
                    .put("vod_remarks", item.optString("vod_remarks"))
                    .put("vod_content", item.optString("vod_content"))
                    .put("vod_actor", item.optString("vod_actor"))
                    .put("vod_director", item.optString("vod_director"))
                    .put("vod_class", item.optString("vod_class"))
                    .put("vod_year", item.optString("vod_year"))
                    .put("vod_area", item.optString("vod_area"))
                    .put("vod_play_from", item.optString("vod_play_from"))
                    .put("vod_play_url", item.optString("vod_play_url")),
            )
        }
        return JSONObject()
            .put("list", list)
            .put("pagecount", response.optInt("pagecount", 1))
            .toString()
    }

    private fun get(query: String): JSONObject {
        val url = site.api.trimEnd('/') + (if (site.api.contains('?')) "&" else "?") + query
        val body = OkHttp.string(url, mapOf("User-Agent" to Util.CHROME))
        check(body.isNotBlank()) { "CMS 接口返回为空: ${site.name} $url" }
        val json = body.trim()
        // 非 JSON 对象形态 (防爬/劫持回 HTML, 或 http 形态的非 CMS 端点) 如实报错:
        // 带站点名与 URL 交发现页错误链呈现, 不静默吞掉也不伪造空数据。
        check(json.startsWith("{")) {
            "CMS 接口返回非 JSON: ${site.name} $url (响应开头: ${json.take(120)})"
        }
        return runCatching { JSONObject(json) }.getOrElse {
            throw IllegalStateException("CMS 接口解析失败: ${site.name} $url", it)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
