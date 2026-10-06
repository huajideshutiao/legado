package io.legado.app.help.tvbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.tvbox.TvBoxManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * fty JS 站点 js2Proxy 端到端环回验证 (真机排查用):
 *
 * fty.json 三个 JS 站点 (虎牙/斗鱼/兔小贝) 实测均不走 js2Proxy (虎牙/斗鱼靠嗅探,
 * 兔小贝直链), 故用合成 drpy2 站点驱动真实链路:
 * JS 侧 js2Proxy() 构 URL (验端口接线) → HTTP GET 127.0.0.1:{port}/proxy?do=js
 * → NanoHTTPD → TvBoxManager.proxyDispatch → jsLoader spider.proxy → drpy2
 * proxy() → 规则 proxy_rule 片段 eval → Array[code,mime,body] 回传。
 *
 * drpy2 契约两坑 (2026-10-07 真机实证):
 * - setResult 吃 {url,title,desc,img} 形态, it.url 映射 vod_id; 喂 {vod_id:...}
 *   会被 `it.url||""` 归零, 炸出的空条目与"未接线"不可区分;
 * - 该版 drpy2.min.js 的代理键是 ASCII `proxy_rule` (init 归一化
 *   hasOwnProperty("proxy_rule") 否则 ""), 中文「代理」键不被读取。
 */
@RunWith(AndroidJUnit4::class)
class TvBoxJs2ProxyE2EInstrumentedTest {

    @Test
    fun js2ProxyEndToEnd() {
        TvBoxManager.init()

        // 合成 drpy2 规则: 一级条目的 vod_id 由 JS 侧 js2Proxy 构造;
        // proxy_rule 片段把 params.url 回显成响应体 (proxyParse 契约: input 重赋值为数组)。
        val ruleJs = """
            var rule={
            title:"e2e",host:"http://e2e.invalid",url:"/list?page=fypage",
            class_name:"a&b",class_url:"a&b",
            "一级":"js:\n var u=js2Proxy(0,3,\"e2e_js\",\"hello\",\"{}\"); setResult([{url:u, title:\"probe\", desc:typeof u+'|'+u}]);",
            "二级":"*",
            "proxy_rule":"js:\n input=[200,\"text/plain\",\"js2proxy-e2e:\"+(input.url||\"\")]"
            };
        """.trimIndent()

        val api = "https://gh-proxy.com/https://raw.githubusercontent.com/qist/tvbox/master/FTY/drpy2.min.js"
        val cfg = JSONObject().put(
            "sites",
            org.json.JSONArray().put(
                JSONObject()
                    .put("key", "e2e_js")
                    .put("name", "e2e")
                    .put("type", 3)
                    .put("api", api)
                    .put("ext", ruleJs)
            )
        ).toString()

        val config = runBlocking { TvBoxManager.setConfig(cfg) }
        // 诊断: 供应器状态 (Kotlin 侧直查, 区分"未接线" vs "JS→bridge 路径断")
        println(
            "[Js2ProxyE2E] diag: TvBoxJsProxy.port=${TvBoxJsProxy.port} " +
                "urlProviderSet=${TvBoxJsProxy.urlProvider != null} url='${TvBoxJsProxy.url(true)}' " +
                "catvodPort=${com.github.catvod.Proxy.getPort()}"
        )

        val site = config.sites.firstOrNull { it.key == "e2e_js" }
        checkNotNull(site) { "合成站点未进配置: ${config.sites.map { it.key }}" }
        check(site.isJsSpider) { "合成站点未被识别为 JS 站点: api=${site.api}" }

        // 走 TvBoxManager 的 loader 实例 (与 proxyDispatch 同源)
        val spider = runBlocking { TvBoxManager.spiderFor("e2e_js").second }
        val catJson = spider.categoryContent("fyclass", "1", false, null) ?: ""
        println("[Js2ProxyE2E] category=$catJson")
        val vodId = JSONObject(catJson).getJSONArray("list").getJSONObject(0).getString("vod_id")
        println("[Js2ProxyE2E] js2Proxy URL=$vodId")

        check(vodId.startsWith("http://127.0.0.1:")) {
            "js2Proxy 端口未接线 (返回空或非本机): '$vodId' cat='$catJson' " +
                "diag[port=${TvBoxJsProxy.port}, providerSet=${TvBoxJsProxy.urlProvider != null}, " +
                "kotlinUrl='${TvBoxJsProxy.url(true)}', catvodPort=${com.github.catvod.Proxy.getPort()}]"
        }
        check(vodId.contains("/proxy?do=js")) { "js2Proxy URL 形态异常: $vodId" }
        check(vodId.contains("siteKey=e2e_js")) { "js2Proxy siteKey 传参异常: $vodId" }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val body = client.newCall(Request.Builder().url(vodId).build()).execute().use { resp ->
            val text = resp.body!!.string()
            check(resp.code == 200) { "do=js 回环 HTTP ${resp.code}: ${resp.message} body='$text' url=$vodId" }
            text
        }
        println("[Js2ProxyE2E] body=$body")
        check(body == "js2proxy-e2e:hello") { "do=js 回环响应不符: $body" }
    }
}
