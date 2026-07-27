package io.legado.app.model.analyzeRule

// 仅被 AnalyzeRule 直接静态调用，无反射/JS 反射入口，故不需 @Keep。
// kotlin.text.Regex 在 JVM 即 Pattern 包装；多匹配用 next() 链，与连续 matcher.find() 等价（零宽推进一致）。
object AnalyzeByRegex {

    fun getElement(res: String, regs: Array<String>, index: Int = 0): List<String>? {
        var vIndex = index
        val match = Regex(regs[vIndex]).find(res) ?: return null
        // 判断索引的规则是最后一个规则
        return if (vIndex + 1 == regs.size) {
            // 新建容器
            val info = arrayListOf<String>()
            for (groupIndex in 0 until match.groups.size) {
                // groups[n]!! 对齐 Matcher.group(n)!!：未参与分组同样抛 NPE
                info.add(match.groups[groupIndex]!!.value)
            }
            info
        } else {
            val result = StringBuilder()
            var m: MatchResult? = match
            while (m != null) {
                result.append(m.value)
                m = m.next()
            }
            getElement(result.toString(), regs, ++vIndex)
        }
    }

    fun getElements(res: String, regs: Array<String>, index: Int = 0): List<List<String>> {
        var vIndex = index
        val first = Regex(regs[vIndex]).find(res) ?: return arrayListOf()
        // 判断索引的规则是最后一个规则
        if (vIndex + 1 == regs.size) {
            // 创建书息缓存数组
            val books = ArrayList<List<String>>()
            // 提取列表
            var m: MatchResult? = first
            while (m != null) {
                // 新建容器
                val info = arrayListOf<String>()
                for (groupIndex in 0 until m.groups.size) {
                    // groups[n]?.value 对齐 Matcher.group(n)：未参与分组落空串
                    info.add(m.groups[groupIndex]?.value ?: "")
                }
                books.add(info)
                m = m.next()
            }
            return books
        } else {
            val result = StringBuilder()
            var m: MatchResult? = first
            while (m != null) {
                result.append(m.value)
                m = m.next()
            }
            return getElements(result.toString(), regs, ++vIndex)
        }
    }
}
