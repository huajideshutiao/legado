package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import java.io.ByteArrayInputStream
import java.io.InputStream

// ImageUtils object 本体已下沉 commonMain (utils/ImageUtils.kt), 本文件仅保留
// JVM-only 的 InputStream 重载 (java.io 流进出, 同包扩展函数, 调用方写法不变)。

/**
 * @param isCover 根据这个执行书源中不同的解密规则
 * @return 解密失败返回Null 解密规则为空不处理
 */
fun ImageUtils.decode(
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
