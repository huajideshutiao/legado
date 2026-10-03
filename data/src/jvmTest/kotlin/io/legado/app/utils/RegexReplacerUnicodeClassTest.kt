package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JvmRegexReplacer] 的字符类语义回归测试 (JVM/桌面)。
 *
 * # 背景
 *
 * `\w` 在两端语义不同: JDK 默认是 ASCII-only (不含中文), Android (ICU) 是 Unicode 词字符
 * (含中文)。净化规则大量使用 `[^\n\w…]{4,}` 这类"非文字符号串"写法, 不补齐则桌面端会把
 * 整段正常中文当符号串误替换。实测同一段正文、同一条规则 #07: 补齐前 JVM 匹配 11 处、
 * Android 匹配 0 处; 补齐后两端一致 (均 0 处, 正文不变)。
 *
 * 规则取自 https://legado.aoaostar.com/sources/3f067eb2.json 第 7 条 "标点——"。
 */
class RegexReplacerUnicodeClassTest {

    private val rule07Pattern =
        "(?mi)(?<=^|\\w[^\\n\\w]{0,3})(?!(?<=\\d)--\\d|\\b——\\b)([\\u002d\\u2010-\\u2015\\u2212\\u2500\\u2501\\u268a\\u268b\\u2e3a\\u2e3b\\uff0d\\uff70\\uffda～~])(?:\\1+|[\\u2010-\\u2013]+|(?<=\\u2014|\\u2015)(?!\\d)|(?<=\\w[～~])\\B|(?<!\\u002d)[。，、]+)|^(?:[—\\–_×ꁘ]{2,}$|[^\\n\\wꁘ“”【】（）…]{4,}|[■◎](?=(?:\\[\\w{1,4}\\])?[\\w\\u00b7\\u318d]{1,18}$))|(?<=[\\u4e00-\\u9fcc])(?=([“‘])[^\\n“”‘’＂]{1,27}[。！？—…][”’](?:\\1|\\n)|[“‘][^\\n“”‘’＂]{29})"

    /** 与 Android (ICU) 一致的正文: 规则不应命中, 正文保持不变。 */
    private val plainChapter =
        "第一章 归来\n\n大麦哲伦星云。\n\n星海坐标：X12575.89Y57。\n\n岳恒回头看了一眼。\n\n在他的后方，上百艘长度超过50000米的泰坦战列舰，正率领着成千上万星舰向虫族军团发起了决死的冲锋。"

    /** `\w` 必须含中文: 否则 [^\n\w…]{4,} 会把整段中文当符号串。 */
    @Test
    fun wordClassIncludesChinese() {
        val actual = JvmRegexReplacer.replace(
            plainChapter, rule07Pattern.toRegex(), "——", 3000L
        )
        assertEquals("正常中文正文被误替换, 说明 \\w 未包含中文", plainChapter, actual)
    }

    /** 该替换的字符确实仍要正常替换 (防补齐过度导致规则完全失效)。 */
    @Test
    fun stillReplacesActualPunctuation() {
        val text = "他--说。"
        val actual = JvmRegexReplacer.replace(text, rule07Pattern.toRegex(), "——", 3000L)
        assertTrue("真实需要净化的字符未被替换", actual != text)
    }

    /** 传非 String 的 CharSequence 时, 结果必须与传 String 一致。 */
    @Test
    fun charSequenceMatchesString() {
        val asString = JvmRegexReplacer.replace(
            plainChapter, rule07Pattern.toRegex(), "——", 3000L
        )
        val asCharSequence = JvmRegexReplacer.replace(
            StringBuilder(plainChapter), rule07Pattern.toRegex(), "——", 3000L
        )
        assertEquals(asString, asCharSequence)
    }
}
