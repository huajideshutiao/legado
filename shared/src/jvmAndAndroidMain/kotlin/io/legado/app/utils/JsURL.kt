package io.legado.app.utils

import java.net.URL
import java.net.URLDecoder

/**
 * JsURL 的 JVM 半区 actual（android + jvm 共用）。
 *
 * 详见 commonMain/utils/JsURL.kt expect 注释。
 * 实现逻辑与原 jvmAndAndroidMain 直接 new URL(...) 一致, 行为不变。
 */
actual class JsURL actual constructor(url: String, baseUrl: String?) {

    actual val searchParams: Map<String, String>?
    actual val host: String
    actual val origin: String
    actual val pathname: String

    init {
        val mUrl = if (!baseUrl.isNullOrEmpty()) {
            val base = URL(baseUrl)
            URL(base, url)
        } else {
            URL(url)
        }
        host = mUrl.host
        origin = if (mUrl.port > 0) {
            "${mUrl.protocol}://$host:${mUrl.port}"
        } else {
            "${mUrl.protocol}://$host"
        }
        pathname = mUrl.path
        val query = mUrl.query
        searchParams = query?.let { _ ->
            val map = hashMapOf<String, String>()
            query.split("&").forEach {
                val x = it.split("=", limit = 2)
                if (x.size == 2) {
                    map[x[0]] = URLDecoder.decode(x[1], "utf-8")
                }
            }
            map
        }
    }

}
