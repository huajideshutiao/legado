package io.legado.app.utils

import java.nio.charset.Charset

/**
 * PercentCodec 的 JVM/Android 专属扩展: 接收 [Charset] 的 encode 重载。
 *
 * commonMain 不暴露 Charset 类型, 本重载经 foundation 的 [textCharsetCodec] (Charset 工厂)
 * 接入 JDK CharsetEncoder, 走 commonMain 的 [PercentCodec.encode] (TextCharsetCodec 重载):
 * 整个 encode 调用共用一个编码器, 状态跨整个调用持续 —— 对齐 archive hutool 5.8.22
 * PercentCodec.encode(String, Charset)「循环外一个 OutputStreamWriter」的一手语义
 * (UTF-16 BOM 全调用仅一次)。
 *
 * 供 app 端测试 (HutoolReplacementCompatTest, 用 `Charsets.UTF_8` / `charset("GBK")` 等)、
 * AnalyzeUrlCoreExt 等 query 整段路径调用方使用。
 */
fun PercentCodec.encode(str: CharSequence, charset: Charset): String =
    encode(str, textCharsetCodec(charset))
