package com.github.catvod.crawler

import android.content.Context
import com.github.catvod.net.OkHttp
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * TVBox/FongMi 生态 Spider 壳基类: 包名/签名逐字对齐 FongMi/TV catvod 模块。
 * spider jar 以 compileOnly 方式按这些 FQCN 编译, 由宿主提供运行时实现;
 * @Throws 对齐原 throws 子句 (jar 二进制兼容), 参数一律可空 (jar 侧可能传 null)。
 */
abstract class Spider {

    @JvmField
    var siteKey: String? = null

    companion object {

        @JvmStatic
        fun safeDns(): Dns = OkHttp.dns()

        @JvmStatic
        fun client(): OkHttpClient = OkHttp.client()
    }

    @Throws(Exception::class)
    open fun init(context: Context?) {
    }

    @Throws(Exception::class)
    open fun init(context: Context?, extend: String?) {
        init(context)
    }

    @Throws(Exception::class)
    open fun homeContent(filter: Boolean): String = ""

    @Throws(Exception::class)
    open fun homeVideoContent(): String = ""

    @Throws(Exception::class)
    open fun categoryContent(tid: String?, pg: String?, filter: Boolean, extend: HashMap<String, String>?): String = ""

    @Throws(Exception::class)
    open fun detailContent(ids: List<String>?): String = ""

    @Throws(Exception::class)
    open fun searchContent(key: String?, quick: Boolean): String = ""

    @Throws(Exception::class)
    open fun searchContent(key: String?, quick: Boolean, pg: String?): String = ""

    @Throws(Exception::class)
    open fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String = ""

    @Throws(Exception::class)
    open fun liveContent(url: String?): String = ""

    @Throws(Exception::class)
    open fun manualVideoCheck(): Boolean = false

    @Throws(Exception::class)
    open fun isVideoFormat(url: String?): Boolean = false

    @Throws(Exception::class)
    open fun proxy(params: Map<String, String>?): Array<Any?>? = null

    @Throws(Exception::class)
    open fun action(action: String?): String? = null

    open fun destroy() {
    }
}
