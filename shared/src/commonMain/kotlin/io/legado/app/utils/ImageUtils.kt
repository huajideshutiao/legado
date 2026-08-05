package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource

// 下沉自 app 模块 io.legado.app.utils.ImageUtils (经 jvmAndAndroidMain 二次下沉 commonMain)。
// 字节数组进出 + evalJS (JsEngines 共享面) 均为 commonMain 能力, 四端 (Android/desktop/iOS/鸿蒙)
// 封面与正文图片解密共用本 ByteArray 单一路径 (2026-08 拍板: 统一字节数组注入,
// 删除原 JVM-only InputStream 重载, 避免双路径重复逻辑)。

/**
 * 加密图片解密工具
 */
object ImageUtils {

    /**
     * @param isCover 根据这个执行书源中不同的解密规则 (封面 → coverDecodeJs, 正文图 → contentRule.imageDecode)
     * @return 解密失败返回Null 解密规则为空不处理
     */
    fun decode(
        src: String, bytes: ByteArray, isCover: Boolean,
        source: BaseSource?, book: Book? = null
    ): ByteArray? {
        val ruleJs = getRuleJs(source, isCover)
        if (ruleJs.isNullOrBlank()) return bytes
        // 解密: evalJS 注入 result=字节数组。JVM/quickjs 侧 result 是 byte[] JavaObject
        // (length 为 number), 脚本 `result.length==undefined` 判断走 XOR 主分支, 不触发
        // Packages.readBytes 分支; native 侧 (iOS/鸿蒙) 注入为 Uint8Array (同 length 语义),
        // 四端脚本行为一致。
        return kotlin.runCatching {
            val result = source?.evalJS(ruleJs) {
                put("book", book)
                put("result", bytes)
                put("src", src)
            }
            when (result) {
                // JVM/quickjs (Android/desktop): JS 侧 result 是 byte[] 的 JavaObject 包装,
                // 脚本索引写回直接修改底层数组, eval 返回值解包回原 byte[]
                is ByteArray -> result
                // native 引擎 (iOS/鸿蒙): 注入 Uint8Array, 脚本写回后整体返回,
                // [NativeJsEngine.tryGetUint8ArrayBytes] 已拷回 ByteArray;
                // 书源直接返回 JS Array 的兼容分支 (逐元素 Number→Byte)
                is List<*> -> {
                    val out = ByteArray(result.size)
                    result.forEachIndexed { i, item ->
                        out[i] = (item as? Number)?.toByte()
                            ?: throw ClassCastException("解密结果元素非数字: $item")
                    }
                    out
                }

                else -> throw ClassCastException(
                    "解密结果类型异常: ${result?.let { it::class.simpleName } ?: "null"}"
                )
            }
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
