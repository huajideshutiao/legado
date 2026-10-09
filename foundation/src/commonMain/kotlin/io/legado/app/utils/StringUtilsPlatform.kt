package io.legado.app.utils

/**
 * StringUtils 平台相关 expect 门面。
 *
 * - [createWordCountFormatter]: 返回一个 (Double) -> String 的格式化闭包,
 *   actual 在 jvmAndAndroidMain 持有 [java.text.DecimalFormat]("#.#") (HALF_EVEN + 去末尾 0);
 *   commonMain 通过 lazy 缓存闭包, 保留原 "by lazy { DecimalFormat(...) }" 单例语义。
 */
internal expect fun createWordCountFormatter(): (Double) -> String
