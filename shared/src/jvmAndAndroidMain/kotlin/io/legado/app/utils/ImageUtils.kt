package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import java.io.ByteArrayInputStream
import java.io.InputStream

// 下沉自 app 模块 io.legado.app.utils.ImageUtils。
// 下沉原因: 本 object 仅依赖已下沉 commonMain 的 BaseSource/Book/BookSource/AppLog
// 以及 JDK 的 ByteArrayInputStream/InputStream, 无任何 Android 平台依赖, 属于纯
// JVM/Android 共用逻辑, 落 jvmAndAndroidMain 可在 app 与 desktop/服务端复用。
// 包名与 object 名称保持不变, 调用方 (BookHelp/ImageLoader/OkHttpStreamFetcher) import 零改动。

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

    fun decode(
        src: String, inputStream: InputStream, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): InputStream? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return inputStream
        //解密库hutool.crypto ByteArray|InputStream -> ByteArray
        return kotlin.runCatching {
            ByteArrayInputStream(
                source?.evalJS(ruleJs) {
                    put("book", book)
                    put("result", inputStream)
                    put("src", src)
                } as ByteArray
            )
        }.onFailure {
            AppLog.putDebug("${src}解密错误", it)
        }.getOrNull()
    }

    private fun getRuleJs(
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
