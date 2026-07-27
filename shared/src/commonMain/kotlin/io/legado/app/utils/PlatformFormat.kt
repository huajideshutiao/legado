package io.legado.app.utils

/**
 * String.format(Locale.ROOT, "%.0f", value) 的 commonMain expect 门面。
 *
 * AnalyzeUrlCore.replaceKeyPageJs 与 AnalyzeRuleCore.SourceRule.makeUpRule 中,
 * JS 返回的 Double 值若为整数需格式化为无小数点字符串 (对齐 rhino JS Number.toString 行为)。
 * String.format 是 JVM-only API, commonMain 不可见, 需 expect/actual 包装。
 *
 * 行为: 对齐 String.format(Locale.ROOT, "%.0f", value), 四舍五入到整数, 不带小数点。
 */
expect fun formatDoubleNoDecimal(value: Double): String
