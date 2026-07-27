package io.legado.app.utils

import java.nio.charset.Charset

/**
 * PercentCodec 的 JVM 专属扩展: 接收 [Charset] 的 encode 重载。
 *
 * commonMain 端 [PercentCodec.encode] 用 `toBytes: (String) -> ByteArray` lambda 注入字节化,
 * 不暴露 Charset 类型 (Kotlin 2.3 stdlib common 无 kotlin.text.Charset expect class)。
 * 本扩展函数委托给 commonMain 端 encode, 供 app 端测试
 * (HutoolReplacementCompatTest, 用 `Charsets.UTF_8` / `charset("GBK")` 等) 调用,
 * 行为与原 jvmAndAndroidMain 端 PercentCodec.encode(str, charset) 完全一致。
 *
 * 注: 原 jvmAndAndroidMain 端 StringExtensionsJvm.shared.kt 的 `String.encodeURI()`
 * 已下沉 commonMain (用 `it.toByteArray()` 默认 UTF_8), 不再走本重载。
 */
fun PercentCodec.encode(str: CharSequence, charset: Charset): String =
    encode(str) { it.toByteArray(charset) }
