package io.legado.app.ui.root

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [shareTextTarget] 的去向判据回归测试 (JVM/桌面跑; Android/iOS/鸿蒙共用同一份判据, 一处断言即全端口径)。
 *
 * # 覆盖的是"一整类输入", 不是某条具体分享
 * 按判据的三条分量各取一类: 协议头有无与大小写 ([io.legado.app.utils.isAbsUrl])、整段是否含空白、
 * 是否纯空白。原版 `AssociationActivity.dispose()` 把文本按空白分词后逐 token 用 `http.+` 匹配,
 * 命中只把主界面带回前台、丢弃链接; `http.+` 也不看协议头, `http不是地址` 这类文本同样命中。
 * 本用例把判据两侧都钉住。
 * 命中链接的去向是自动识别链 (先零网络按书源认领判书籍, 不认领才下载内容嗅探规则类型),
 * 本用例只钉判据分流, 不测导入结果。
 */
class ShareTextTargetTest {

    /** 整段就是一条 http(s) 绝对地址: 协议头齐、无空白, 大小写不敏感 → 交自动识别类型导入。 */
    private val autoImportTexts = listOf(
        "http://www.qidian.com/book/100",
        "https://www.qidian.com/book/100",
        "HTTPS://WWW.QIDIAN.COM/BOOK/100",
        "Http://x",
        "https://www.example.com/path?a=1&b=2#frag",
    )

    /** 不是"整段一条链接": 缺协议头、链接夹其它文字、首尾空白、多行、纯书名、深链文本。 */
    private val searchTexts = listOf(
        "httpfoo",
        "http不是地址",
        "https:/单斜杠不是地址",
        "https://x.com 这本不错",
        " https://x.com",
        "https://x.com\t",
        "https://a.com\nhttps://b.com",
        "诡秘之主 爱潜水的乌贼",
        // legado:// 深链只有作为 URI 投递 (Android VIEW / iOS openURL / 鸿蒙 Want) 才解析, 文本不作深链
        "legado://import/bookSource?src=https://a.com",
    )

    /** 空白: 原版 `finish()` 后直接 return —— 不开页面也不提示。 */
    private val blankTexts = listOf("", " ", "\n\t ")

    @Test
    fun absoluteHttpAddressGoesToAutoImport() {
        autoImportTexts.forEach { text ->
            assertEquals(
                "整段是 http(s) 绝对地址应交自动识别类型导入: $text",
                ShareTextTarget.AUTO_IMPORT,
                shareTextTarget(text),
            )
        }
    }

    @Test
    fun anythingElseGoesToSearch() {
        searchTexts.forEach { text ->
            assertEquals(
                "不是\"整段一条链接\"的文本应送搜索页: $text",
                ShareTextTarget.SEARCH,
                shareTextTarget(text),
            )
        }
    }

    @Test
    fun blankTextOpensNothing() {
        blankTexts.forEach { text ->
            assertEquals("空白文本不打开任何页面: [$text]", ShareTextTarget.NOTHING, shareTextTarget(text))
        }
    }
}
