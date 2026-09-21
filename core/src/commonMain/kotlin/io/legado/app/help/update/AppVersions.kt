package io.legado.app.help.update

/**
 * 版本号比较。
 *
 * 旧实现直接用 `String.compareTo`, 对不等宽数字段与预发布后缀都会判错
 * (如 "3.9.0" > "3.10.0"、"3.25.0-beta1" > "3.25.0")。
 *
 * legado 的版本号是 `3.<yy>.<MMDDHH>` (beta 名 `3.<yy>.<MMDDHHmm>`), 第三段是零填充日期码,
 * 纯数值比较会把 `07152306` 判成大于 `080112`。故按段判别: 零填充段 (含前导 0) 走字符串比较,
 * 普通段走数值比较, 预发布后缀走 semver 规则。
 */
object AppVersions {

    /** [candidate] 是否比 [current] 新。 */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        val segA = coreA.split('.').map { it.trim() }
        val segB = coreB.split('.').map { it.trim() }
        if (segA.any { it.toLongOrNull() == null } || segB.any { it.toLongOrNull() == null }) {
            return a.compareTo(b)
        }
        for (i in 0 until maxOf(segA.size, segB.size)) {
            val x = segA.getOrNull(i) ?: "0"
            val y = segB.getOrNull(i) ?: "0"
            // 零填充日期码 (07152306 / 080112) 左对齐, 只有字符串比较才得出正确先后
            val cmp = if (isZeroPadded(x) || isZeroPadded(y)) {
                x.compareTo(y)
            } else {
                x.toLong().compareTo(y.toLong())
            }
            if (cmp != 0) return cmp
        }
        return comparePreRelease(preA, preB)
    }

    private fun isZeroPadded(segment: String): Boolean =
        segment.length > 1 && segment[0] == '0'

    /** 去 v/V 前缀与 build metadata, 拆成 (核心版本, 预发布标识)。 */
    private fun split(version: String): Pair<String, String> {
        val normalized = version.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = normalized.substringBefore('-')
        val pre = normalized.substringAfter('-', "")
        return core to pre
    }

    /** semver 规则: 无预发布 > 有预发布; 数字标识按数值比且低于字母标识; 标识多者胜。 */
    private fun comparePreRelease(a: String, b: String): Int {
        if (a.isEmpty() && b.isEmpty()) return 0
        if (a.isEmpty()) return 1
        if (b.isEmpty()) return -1
        val idsA = a.split('.')
        val idsB = b.split('.')
        for (i in 0 until maxOf(idsA.size, idsB.size)) {
            val x = idsA.getOrNull(i) ?: return -1
            val y = idsB.getOrNull(i) ?: return 1
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            val cmp = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
