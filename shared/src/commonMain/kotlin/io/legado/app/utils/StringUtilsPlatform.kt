package io.legado.app.utils

/**
 * StringUtils 平台相关 expect 门面。
 *
 * - [createWordCountFormatter]: 返回一个 (Double) -> String 的格式化闭包,
 *   actual 在 jvmAndAndroidMain 持有 [java.text.DecimalFormat]("#.#") (HALF_EVEN + 去末尾 0);
 *   commonMain 通过 lazy 缓存闭包, 保留原 "by lazy { DecimalFormat(...) }" 单例语义。
 *
 * - [gzipAndBase64Encode]: gzip 压缩 + Base64.Default 标准编码,
 *   actual 在 jvmAndAndroidMain 用 [java.io.ByteArrayOutputStream] + [java.util.zip.GZIPOutputStream]
 *   + [kotlin.io.encoding.Base64] (KMP stable); 保留原 close 顺序(既有 close 顺序缺陷, 行为不变)。
 */
internal expect fun createWordCountFormatter(): (Double) -> String

internal expect fun gzipAndBase64Encode(str: String): String
