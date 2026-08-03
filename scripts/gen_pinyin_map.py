#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 nativeMain 的简体汉字→拼音静态映射表 (StringCnCompare.native.kt 用)。

数据源:
- 汉字集合: GB2312 一级 (0xB0-0xD7) + 二级 (0xD8-0xF7), 共 6763 字
- 拼音: pypinyin; 多音字取"词组库频次最高读音" (遍历 pypinyin phrases_dict
  统计每个字在所有词组读音中的出现频次取众数, 无词组统计时回落 pinyin_dict 首读音),
  比 pypinyin 单字默认读音更贴近 ICU zh 拼音序 (实测排序位移更小)

输出: shared/src/nativeMain/kotlin/io/legado/app/utils/PinyinTable.native.kt
用法: pip install pypinyin && python scripts/gen_pinyin_map.py
2026-08-04: 改动拼音表请重新生成而非手改。
"""
import sys

from pypinyin import lazy_pinyin, Style, phrases_dict, pinyin_dict
import collections

OUT = "shared/src/nativeMain/kotlin/io/legado/app/utils/PinyinTable.native.kt"


def gb2312_hanzi():
    chars = []
    # GB2312 汉字区: 一级 0xB0-0xD7 (3755), 二级 0xD8-0xF7 (3008)
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                ch = bytes([hi, lo]).decode("gb2312")
                chars.append(ch)
            except UnicodeDecodeError:
                pass
    return chars


def build_freq_readings():
    """每字在 pypinyin 词组库中的读音频次统计 → 众数读音 (去声调, ü→v 对齐 pypinyin NORMAL 风格)。"""
    import unicodedata

    def strip_tone(s):
        out = []
        for ch in s:
            if ch in "ǖǘǚǜü":
                out.append("v")
                continue
            decomposed = unicodedata.normalize("NFD", ch)
            out.append(decomposed[0] if decomposed and decomposed[0].isalpha() else ch)
        return "".join(out)

    counter = collections.defaultdict(collections.Counter)
    for word, readings in phrases_dict.phrases_dict.items():
        for reading in readings:  # 多音词组列表, 等权累计
            for ch, py in zip(word, reading):
                if py and len(ch) == 1:
                    counter[ch][strip_tone(py)] += 1

    def best(ch):
        c = counter.get(ch)
        if c:
            return c.most_common(1)[0][0]
        pys = pinyin_dict.pinyin_dict.get(ord(ch), "")
        return strip_tone(pys.split(",")[0]) if pys else ""

    return best


def main():
    chars = gb2312_hanzi()
    best = build_freq_readings()
    entries = []
    for ch in chars:
        py = best(ch)
        if not py:
            continue
        entries.append((ch, py))
    entries.sort(key=lambda e: ord(e[0]))
    print(f"total chars: {len(chars)}, mapped: {len(entries)}")

    chars_str = "".join(e[0] for e in entries)
    pinyins = ",".join(e[1] for e in entries)

    out = f"""// 本文件由 scripts/gen_pinyin_map.py 生成, 勿手改。
package io.legado.app.utils

/**
 * 简体汉字 → 拼音 (无调全拼) 静态映射表, iOS/鸿蒙共用 (nativeMain)。
 *
 * - 汉字集合: GB2312 一级 + 二级共 {len(entries)} 字 (常用 3000+ 字覆盖)
 * - 拼音: 每字取"词组库频次最高"读音 (多音字与 ICU 上下文读音可能存在个别差异)
 * - 存储: 按码点升序的字符串 + 逗号分隔拼音串, 首次访问时惰性拆分;
 *   查找走 CharArray.binarySearch (O(log n)), 满足排序场景频繁调用需求
 * - 表外字符 (生僻字/非汉字) 返回 null, 由调用方回退码点比较
 */
internal object PinyinTable {{

    private val chars: CharArray = "{chars_str}".toCharArray()

    private const val PINYIN_DATA = "{pinyins}"

    private val pinyins: Array<String> by lazy {{ PINYIN_DATA.split(',').toTypedArray() }}

    /** 码点升序 CharArray 二分查找 (stdlib 无 CharArray.binarySearch, 自实现); 未命中返回负插入点-1 */
    private fun CharArray.binarySearch(element: Char): Int {{
        var low = 0
        var high = size - 1
        while (low <= high) {{
            val mid = (low + high) ushr 1
            val midVal = this[mid]
            when {{
                midVal < element -> low = mid + 1
                midVal > element -> high = mid - 1
                else -> return mid
            }}
        }}
        return -(low + 1)
    }}

    /** 汉字 → 全拼 (无调); 非表内字符返回 null。 */
    fun pinyin(c: Char): String? {{
        val i = chars.binarySearch(c)
        return if (i >= 0) pinyins[i] else null
    }}
}}
"""
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print(f"written: {OUT}")


if __name__ == "__main__":
    sys.exit(main())
