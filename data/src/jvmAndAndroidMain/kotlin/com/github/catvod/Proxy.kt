package com.github.catvod

import com.github.catvod.utils.Util

/**
 * TVBox 本地代理地址壳类 (签名对齐 FongMi catvod 模块)。
 * getPort 为负时 jar 内构造的代理 URL 不可达; TvBoxLocalProxy.start 成功后回填真实端口。
 */
object Proxy {

    private var port = -1

    @JvmStatic
    fun set(port: Int) {
        Proxy.port = port
    }

    @JvmStatic
    fun getPort(): Int = port

    @JvmStatic
    fun getUrl(local: Boolean): String =
        "http://" + (if (local) "127.0.0.1" else Util.getIp()) + ":" + getPort() + "/proxy"
}
