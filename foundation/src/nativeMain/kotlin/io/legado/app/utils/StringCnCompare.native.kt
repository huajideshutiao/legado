package io.legado.app.utils

/**
 * iOS/鸿蒙 actual: 简体中文拼音序比较 (纯 Kotlin, nativeMain 共用)。
 *
 * 实现: [PinyinTable] (GB2312 一级+二级 6763 汉字 → 无调全拼静态映射表, 见
 * tools/gen_pinyin_map.py) + 逐字符比较:
 * - 两个字符都是表内汉字 → 比较全拼 (拼音序, 对齐 Android android.icu / JVM java.text
 *   Collator(zh_CN) 的主序); 拼音相同 → 回退码点序 (稳定序, 同拼音字 ICU 按笔画/部首,
 *   码点序为确定性近似)
 * - 任一方非表内字符 (拉丁/数字/生僻字/符号) → 码点序 (Latin/数字码点低于 CJK,
 *   与 ICU zh 的"西文先于汉字"一致)
 *
 * 与 jvmAndAndroid 的行为差异 (已知近似):
 * - 多音字按"词组库频次最高读音"静态取音, 无上下文消歧 (如 重庆 的 重 静态取 chong,
 *   ICU 语境读 zhong), 实测 3000 词组排序位移约为码点序的 1/7
 * - 同拼音汉字按码点而非笔画/部首排序
 * - 非汉字的整体顺序按码点, 不严格等价 ICU 四级强度
 * 排序稳定不崩; 常用 3000+ 字覆盖, 生僻字回退码点。
 */
actual fun String.cnCompare(other: String): Int {
    val len = minOf(length, other.length)
    for (i in 0 until len) {
        val c1 = this[i]
        val c2 = other[i]
        if (c1 == c2) continue
        val p1 = PinyinTable.pinyin(c1)
        val p2 = PinyinTable.pinyin(c2)
        if (p1 != null && p2 != null) {
            val cmp = p1.compareTo(p2)
            if (cmp != 0) return cmp
            // 同拼音: 码点序兜底 (稳定确定性)
            return c1.code.compareTo(c2.code)
        }
        return c1.code.compareTo(c2.code)
    }
    return length.compareTo(other.length)
}
