package io.legado.app.help.tvbox

import org.json.JSONArray
import org.json.JSONObject

/**
 * TVBox 配置数据面 (格式契约见 FongMi/TV 文档与 TVBox oss 约定):
 *
 * {"spider":"<jar url>;md5;<md5>","sites":[{"key","name","type","api","ext","jar",
 *   "searchable","filterable","quickSearch","timeout","header","playUrl",...}]}
 *
 * type: 0=CMS 直连 api (本轮不支持), 3=JAR Spider (api 以 csp_ 开头), 1/2=JS/Python (遗留)。
 * 站点未声明 jar 时回退全局 spider 字段 (FongMi Site.objectFrom 同语义)。
 */
data class TvBoxSite(
    val key: String,
    val name: String,
    val type: Int,
    val api: String,
    val ext: String,
    val jar: String,
    val playUrl: String,
    val searchable: Boolean,
    val filterable: Boolean,
    val quickSearch: Boolean,
    val timeoutSeconds: Int?,
    val header: Map<String, String>,
) {
    val isJarSpider: Boolean get() = api.startsWith("csp_")

    /** type=0 苹果 CMS 直连: api 即接口根 URL (无 jar, 走宿主侧 CmsSpider)。 */
    val isCmsApi: Boolean get() = api.startsWith("http")

    fun effectiveJar(globalSpider: String): String = jar.ifBlank { globalSpider }

    companion object {

        fun fromJson(obj: JSONObject): TvBoxSite? {
            val key = obj.optString("key").trim()
            val api = obj.optString("api").trim()
            if (key.isEmpty() || api.isEmpty()) return null
            return TvBoxSite(
                key = key,
                name = obj.optString("name").ifBlank { key },
                type = obj.optInt("type", 0),
                api = api,
                ext = extStringOf(obj.opt("ext")),
                jar = obj.optString("jar").trim(),
                playUrl = obj.optString("playUrl").trim(),
                searchable = obj.optBoolean("searchable", true),
                filterable = obj.optBoolean("filterable", true),
                quickSearch = obj.optBoolean("quickSearch", true),
                timeoutSeconds = obj.optInt("timeout", 0).takeIf { it > 0 },
                header = headerOf(obj.opt("header")),
            )
        }

        /** ext 可为字符串/对象/数组, spider 侧一律取其字符串形态 (FongMi ExtAdapter 同语义)。 */
        private fun extStringOf(ext: Any?): String = when (ext) {
            null -> ""
            is JSONObject, is JSONArray -> ext.toString()
            else -> ext.toString().trim()
        }

        /** header 可为对象或 "k=v&k2=v2" 形态字符串。 */
        private fun headerOf(header: Any?): Map<String, String> {
            return when (header) {
                is JSONObject -> {
                    val map = linkedMapOf<String, String>()
                    for (key in header.keys()) map[key] = header.optString(key)
                    map.filterValues { it.isNotEmpty() }
                }
                is String -> header.split("&")
                    .mapNotNull { part ->
                        val i = part.indexOf('=')
                        if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
                    }.toMap()
                else -> emptyMap()
            }
        }
    }
}

data class TvBoxConfig(
    val spider: String,
    val sites: List<TvBoxSite>,
) {
    companion object {

        /**
         * [baseUrl] 为配置拉取地址时, spider/jar/ext 中 "./x" 形态的相对路径
         * 相对配置 URL 解析 (FongMi UrlUtil.convert 同语义); 其余形态原样保留。
         */
        fun parse(json: String, baseUrl: String? = null): TvBoxConfig {
            val root = JSONObject(json)
            val spider = when (val raw = root.opt("spider")) {
                is JSONArray -> raw.optString(0)
                else -> raw?.toString()?.trim().orEmpty()
            }
            val sitesJson = root.optJSONArray("sites")
            val sites = ArrayList<TvBoxSite>(sitesJson?.length() ?: 0)
            for (i in 0 until (sitesJson?.length() ?: 0)) {
                val obj = sitesJson?.optJSONObject(i) ?: continue
                TvBoxSite.fromJson(obj)?.let(sites::add)
            }
            if (baseUrl == null) return TvBoxConfig(spider = spider, sites = sites)
            return TvBoxConfig(
                spider = resolveRelative(spider, baseUrl),
                sites = sites.map {
                    it.copy(jar = resolveRelative(it.jar, baseUrl), ext = resolveRelative(it.ext, baseUrl))
                },
            )
        }

        /**
         * 仅处理 "./x" 相对路径; jar 规格串的 ";md5;…" 段原样保留; ext 可能是
         * 美元符号分段的复合格式 (如 "./lib/token.json$$$https://site/$$$null"), 逐段解析。
         */
        private fun resolveRelative(spec: String, baseUrl: String): String {
            if (!spec.contains("./") && !spec.contains("../")) return spec
            return spec.split("$$$").joinToString("$$$") { segment ->
                resolveSegment(segment, baseUrl)
            }
        }

        private fun resolveSegment(segment: String, baseUrl: String): String {
            val trimmed = segment.trim()
            if (!trimmed.startsWith("./") && !trimmed.startsWith("../")) return segment
            val sep = trimmed.indexOf(";md5;")
            val url = if (sep >= 0) trimmed.substring(0, sep) else trimmed
            val suffix = if (sep >= 0) trimmed.substring(sep) else ""
            val resolved = runCatching {
                java.net.URI(baseUrl).resolve(url).toString()
            }.getOrNull() ?: return segment
            return resolved + suffix
        }
    }
}
