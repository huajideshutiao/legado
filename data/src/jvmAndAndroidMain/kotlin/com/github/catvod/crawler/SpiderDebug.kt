package com.github.catvod.crawler

import java.util.logging.Logger

/** TVBox 生态壳日志类 (签名对齐 FongMi catvod 模块; JVM 端走 JUL, Android 端语义不变)。 */
object SpiderDebug {

    private val logger = Logger.getLogger("SpiderDebug")

    @JvmStatic
    fun log(th: Throwable?) {
        th?.let { logger.severe(it.toString()) }
    }

    @JvmStatic
    fun log(msg: String?) {
        if (!msg.isNullOrEmpty()) logger.fine(msg)
    }

    @JvmStatic
    fun log(tag: String?, msg: String?, vararg args: Any?) {
        if (!msg.isNullOrEmpty()) logger.fine("[$tag] ${String.format(msg, *args)}")
    }
}
