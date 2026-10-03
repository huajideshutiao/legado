package io.legado.app.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 替换净化的 Android 侧回归测试 (instrumented, 真机/模拟器)。
 *
 * 覆盖 [AndroidRegexReplacer]: 输入先落地成 String, 匹配跑在 IO 线程上由协程看门狗计时。
 *
 * # 为什么要专门跑在设备上
 *
 * 曾经的实现把正文包进带截止时间的 CharSequence 再交给 `java.util.regex.Matcher`, 该手段
 * 依赖「正则引擎按需回调查询 CharSequence」这一 JDK 行为。Android 的 libcore Matcher 在
 * reset 里做 `text = input.toString()`, 而 region 上界仍取 `input.length()`, 于是 ICU 按正文
 * 长度去读包装对象身份串所在的内存。实测 (Redmi 5 Plus / Android 12):
 *
 * ```
 * PROBE toString=io.legado.app.utils.DeadlineWrapProbeTest$Wrap@14f0e21
 * PROBE length=30 strLen=54
 * PROBE find(广告)=false                      // 正文里的内容一个都匹配不到
 * PROBE pattern=\w+ matches=5 out=X.X.X.X.X   // 正则实际跑在身份串/相邻堆内存上
 * ```
 *
 * 后果即用户看到的「替换净化: 规则 #07标点——替换出错」toast, 以及正文静默未被净化。
 * 该用例锁死修复后的行为, 防止包装方案被重新引入 Android 路径。
 *
 * 规则与正文取自 https://legado.aoaostar.com/sources/3f067eb2.json 第 7 条 "标点——"。
 * 断言值全部手写 (不取任一平台的实测输出作基准), 两端共用同一组期望。
 */
@RunWith(AndroidJUnit4::class)
class RegexReplacerAndroidTest {

    private val rule07Pattern =
        "(?mi)(?<=^|\\w[^\\n\\w]{0,3})(?!(?<=\\d)--\\d|\\b——\\b)([\\u002d\\u2010-\\u2015\\u2212\\u2500\\u2501\\u268a\\u268b\\u2e3a\\u2e3b\\uff0d\\uff70\\uffda～~])(?:\\1+|[\\u2010-\\u2013]+|(?<=\\u2014|\\u2015)(?!\\d)|(?<=\\w[～~])\\B|(?<!\\u002d)[。，、]+)|^(?:[—\\–_×ꁘ]{2,}$|[^\\n\\wꁘ“”【】（）…]{4,}|[■◎](?=(?:\\[\\w{1,4}\\])?[\\w\\u00b7\\u318d]{1,18}$))|(?<=[\\u4e00-\\u9fcc])(?=([“‘])[^\\n“”‘’＂]{1,27}[。！？—…][”’](?:\\1|\\n)|[“‘][^\\n“”‘’＂]{29})"

    private val rule07Replacement = "——"

    private val chapter =
        "第一章 归来\n\n大麦哲伦星云。\n\n星海坐标：X12575.89Y57。\n\n岳恒回头看了一眼。\n\n在他的后方，上百艘长度超过50000米的泰坦战列舰，正率领着成千上万星舰向虫族军团发起了决死的冲锋。\n\n高能粒子炮的辉芒和战舰爆炸的火光交相辉映，比所有的星辰更加璀璨。\n\n每一秒都有无数英勇的战士在死去。\n\n他们的牺牲，仅仅只是为了给岳恒打开一条前进通道。\n\n更遥远的地方，一座巨大的星门悬浮在宇宙之中，散发着淡淡的光芒。\n\n星门附近的虚空忽然扭曲，无数道银白色的光柱从星门深处激射而出，照亮了整片星域。\n\n岳恒深深吸了一口长气，将战神VII型星战机甲的功率提升到极限。"

    /**
     * 手写期望: 该章节没有破折号类字符, 且 `\w` 含中文, 故规则 #07 不应命中, 正文原样保留。
     *
     * 这正是修复前 Android 静默"不替换"的场景——区别在于修复前是正则跑错了对象,
     * 现在是真的判定为无匹配。
     */
    private val expectedUnchanged = chapter

    /** 正常中文正文不得被误替换 (`\w` 必须含中文, 与 ICU 语义一致)。 */
    @Test
    fun plainChapterUntouched() {
        val actual = AndroidRegexReplacer.replace(
            chapter, rule07Pattern.toRegex(), rule07Replacement, 3000L
        )
        assertEquals("正常中文正文被误替换", expectedUnchanged, actual)
    }

    /** 真正需要净化的字符仍要正常替换 (防规则整体失效)。 */
    @Test
    fun replacesActualPunctuation() {
        val text = "他--说。"
        val actual = AndroidRegexReplacer.replace(
            text, rule07Pattern.toRegex(), rule07Replacement, 3000L
        )
        assertEquals("真实需要净化的字符未被替换", "他——说。", actual)
    }

    /** 传非 String 的 CharSequence 时不得退化: 实现内部必须先落地成 String。 */
    @Test
    fun acceptsCharSequence() {
        val asCharSequence: CharSequence = StringBuilder(chapter)
        val actual = AndroidRegexReplacer.replace(
            asCharSequence, rule07Pattern.toRegex(), rule07Replacement, 3000L
        )
        assertEquals(expectedUnchanged, actual)
    }

    /** 与直接 Regex.replace 一致: 证明替换结果不受超时机制影响。 */
    @Test
    fun matchesPlainRegexReplace() {
        val text = "他--说。"
        val expected = text.replace(rule07Pattern.toRegex(), rule07Replacement)
        val actual = AndroidRegexReplacer.replace(
            text, rule07Pattern.toRegex(), rule07Replacement, 3000L
        )
        assertEquals("替换结果与直接 Regex.replace 不一致", expected, actual)
        assertTrue(actual != text)
    }
}
