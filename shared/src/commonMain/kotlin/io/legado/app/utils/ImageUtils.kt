package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource

// 下沉自 app 模块 io.legado.app.utils.ImageUtils (经 jvmAndAndroidMain 二次下沉 commonMain)。
// 字节数组进出 + evalJS (JsEngines 共享面) 均为 commonMain 能力, iOS/鸿蒙封面解密链复用。
// JVM-only 的 InputStream 重载保留在 jvmAndAndroidMain (同包扩展函数, 调用方 import 零改动)。

/**
 * 加密图片解密工具
 */
object ImageUtils {

    /**
     * @param isCover 根据这个执行书源中不同的解密规则
     * @return 解密失败返回Null 解密规则为空不处理
     */
    fun decode(
        src: String, bytes: ByteArray, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): ByteArray? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return bytes
        //解密库hutool.crypto ByteArray|InputStream -> ByteArray
        return kotlin.runCatching {
            source?.evalJS(ruleJs) {
                put("book", book)
                put("result", bytes)
                put("src", src)
            } as ByteArray
        }.onFailure {
            AppLog.putDebug("${src}解密错误", it)
        }.getOrNull()
    }

    internal fun getRuleJs(
        source: BaseSource?, isCover: Boolean
    ): String? {
        return when (source) {
            is BookSource ->
                if (isCover) source.coverDecodeJs
                else source.contentRule.imageDecode
            else -> null
        }
    }

}
