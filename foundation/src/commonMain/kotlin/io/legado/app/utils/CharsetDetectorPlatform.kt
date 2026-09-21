package io.legado.app.utils

/**
 * 字符集检测 expect/actual 门面。
 *
 * 底层 icu4j [io.legado.app.lib.icu4j.CharsetDetector] (JVM 专属, 大量 java.io.InputStream/Reader
 * 与 java.util.Arrays/Collections) 进不了 commonMain; 抽出最小签名到 expect 函数:
 *
 * - commonMain: [detectCharsetName] 接收 ByteArray, 返回 nullable charset 名 (null = 未匹配);
 * - jvmAndAndroidMain actual: 委托 CharsetDetector().setText(bytes).detect()?.name, 行为不变。
 *
 * 仅 [EncodingDetect.getEncode] 内部调用, 不对外暴露。
 */
internal expect fun detectCharsetName(bytes: ByteArray): String?
