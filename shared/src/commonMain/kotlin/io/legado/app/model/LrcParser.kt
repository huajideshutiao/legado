package io.legado.app.model

/** 一行歌词; [timeMs] 为 -1 表示无时间戳行 (排序后位于最前)。[words] 为空表示这行没有逐字时间。 */
data class LrcLine(val timeMs: Int, val text: String, val words: List<LrcWord> = emptyList())

/** 逐字进度采样点: [timeMs] 时该行唱到正文的第 [charIndex] 个字符。 */
data class LrcWord(val timeMs: Int, val charIndex: Int)

/**
 * 一份歌词的时间轴。
 *
 * 当前高亮行是 (歌词, 播放位置) 的纯函数, 由 [indexAt] 唯一实现: 歌词界面按帧求值,
 * 对外发布按行唤醒求值, 两个消费者共用同一份判定, 不存在第二套扫描逻辑。
 * 逐字高亮是行内的第二级插值, 只看 [LrcLine.words], 不需要再定位一次。
 */
class Lrc(val lines: List<LrcLine>, val raw: String? = null) {

    /** 末行时间戳仍为 -1 说明全篇无时间轴 (-1 排在最前), 不参与推进。 */
    val hasTimeline: Boolean get() = (lines.lastOrNull()?.timeMs ?: -1) >= 0

    /** [positionMs] 处应高亮的行下标; 首行时间戳还没到时为 -1。 */
    fun indexAt(positionMs: Int): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /** [index] 之后一行的时间戳; 已是末行返回 null。 */
    fun timeAfter(index: Int): Int? = lines.getOrNull(index + 1)?.timeMs

    /**
     * 交给系统侧渲染的歌词文本 —— 就是书源原文, 不重造。
     *
     * 只做两处兼容归一 (小米 / 华为锁屏歌词的解析器决定):
     * - 换行统一成 CRLF: 小米 ThemeManager 的 MusicLyricParser 按 `"\r\n"` 切行, 只给 `"\n"`
     *   会把整篇当成一行, 每个时间戳都配到末行正文上
     * - 剥掉 Enhanced LRC 的 `<mm:ss.fff>` 字标签: 它只剥 `<mm:ss:ms>` 冒号形式, 点号形式会被当正文显示
     *
     * 时间戳、`[ti:]`/`[ar:]` 头、`[offset:]` 一律原样保留 —— 它自己会读会算, 重复加工只会二次平移。
     */
    val systemLrcText: String? by lazy {
        val text = raw ?: return@lazy null
        WORD_TAG.replace(text, "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "\r\n")
    }

    companion object {
        /** 歌词同步补偿偏移 (毫秒)。 */
        const val OFFSET_MS = 60

        /** Enhanced LRC 的逐字时间标签 `<mm:ss.fff>` 与 `<mm:ss:fff>`。 */
        private val WORD_TAG = Regex("<\\d{1,2}:\\d{1,2}[.:]\\d{1,3}>")
    }
}

/**
 * 解析书源 lrc 规则返回的歌词数据。
 *
 * 输入是一个 NativeArray<String>,每个元素本身是一段多行 LRC 文本:
 * - 第 0 个元素:每个时间戳行作为新条目加入 (time -> text), 无时间戳的行作为 (-1, line) 加入(显示在最前)
 * - 后续元素:按时间戳匹配并把文本以换行附加到已有条目上(用于多语种叠加,常见于网易云双语歌词)
 *
 * 时间戳支持 [mm:ss] 与 [mm:ss.fff], 行首可以有多个时间戳共用同一句 (`[00:12.00][01:30.00]副歌`);
 * 行内 `<mm:ss.fff>` 字标签 (Enhanced LRC) 拆进 [LrcLine.words], 正文里的标签一并剥掉;
 * `[offset:+500]` 平移其后各行的时间(正值表示提前), 其余 `[ti:]` `[ar:]` 等标签一律丢弃。
 */
object LrcParser {

    private const val OFFSET_TAG = "offset:"

    fun parse(content: List<*>): Lrc {
        if (content.isEmpty()) return Lrc(emptyList())
        val lines = mutableListOf<LrcLine>()
        // 首份歌词建立时间轴; 部分歌词源时间戳乱序(如网易云), 加完统一排序
        forEachEntry(content[0] as String) { timeMs, text, words ->
            lines.add(LrcLine(timeMs, text, words))
        }
        lines.sortBy { it.timeMs }
        // 后续歌词只把文本叠加到已有条目上(多语种), 不引入新时间戳
        for (i in 1 until content.size) {
            forEachEntry(content[i] as String) { timeMs, text, _ ->
                if (timeMs >= 0 && text.isNotBlank()) lines.mergeAt(timeMs, text)
            }
        }
        // 原文只留首份: 系统侧整段吃一份就够, 叠加的多语种是同一时间戳的重复行
        return Lrc(lines, content[0] as String)
    }

    /** 把 [text] 以换行追加到同时间戳的条目上; 找不到就丢弃, 只有首份歌词能引入新条目。 */
    private fun MutableList<LrcLine>.mergeAt(timeMs: Int, text: String) {
        val index = binarySearch { it.timeMs.compareTo(timeMs) }
        if (index < 0) return
        val line = this[index]
        this[index] = line.copy(text = "${line.text}\n$text")
    }

    /**
     * 逐行扫描一段 LRC 文本, 对每个 (时间戳, 正文, 逐字采样) 调用 [action]; 无时间戳的行给出 -1。
     * 行首有多个时间戳共用同一句正文时逐个给出。
     */
    private inline fun forEachEntry(
        content: String,
        action: (timeMs: Int, text: String, words: List<LrcWord>) -> Unit
    ) {
        var offsetMs = 0
        val buf = mutableListOf<LrcWord>()
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.length < 3) continue
            val close = line.indexOf(']')
            if (close == -1) {
                action(-1, line, emptyList())
                continue
            }
            // 时间戳组必须以 "[数字" 开头 —— 下面拿 close 当第一组的收尾, 行首不是 '[' 这前提就不成立
            if (line[0] != '[') continue
            if (line[1] !in '0'..'9') {
                if (line.startsWith(OFFSET_TAG, 1, ignoreCase = true)) {
                    offsetMs = parseOffset(line, 1 + OFFSET_TAG.length, close)
                }
                continue
            }
            // 行首可以有多个时间戳共用一句正文, 逐个剥离到最后一个时间戳为止
            var lastClose = close
            while (lastClose + 2 < line.length &&
                line[lastClose + 1] == '[' && line[lastClose + 2] in '0'..'9'
            ) {
                val next = line.indexOf(']', lastClose + 2)
                if (next == -1) break
                lastClose = next
            }
            buf.clear()
            val firstTime = (parseTime(line, 1, close) - offsetMs).coerceAtLeast(0)
            val text = parseWords(line.substring(lastClose + 1), firstTime, offsetMs, buf)
            val words = buf.toList()
            var from = 1
            while (true) {
                val groupEnd = line.indexOf(']', from)
                val rawTime = parseTime(line, from, groupEnd)
                if (rawTime >= 0) action((rawTime - offsetMs).coerceAtLeast(0), text, words)
                if (groupEnd >= lastClose) break
                from = groupEnd + 2
            }
        }
    }

    /**
     * 剥掉 Enhanced LRC 的 `<mm:ss.fff>` 字标签并返回正文, 每个标签在 [words] 里留一个采样点;
     * 首个标签之前的正文归 [baseTimeMs]。没有字标签就原样返回, 一个采样都不产生。
     */
    private fun parseWords(
        text: String,
        baseTimeMs: Int,
        offsetMs: Int,
        words: MutableList<LrcWord>
    ): String {
        if (text.indexOf('<') == -1) return text
        val plain = StringBuilder(text.length)
        var p = 0
        while (p < text.length) {
            val end = if (text[p] == '<') text.indexOf('>', p + 1) else -1
            val rawTime = if (end == -1) -1 else parseTime(text, p + 1, end)
            // 读不出时间就不是字标签, 当正文留着 (`I <3 you >`, `<i>斜体</i>`)
            if (rawTime < 0) {
                plain.append(text[p])
                p++
                continue
            }
            // 首个标签之前还有正文, 它归行自己的时间戳
            if (words.isEmpty() && plain.isNotEmpty()) words.add(LrcWord(baseTimeMs, 0))
            words.add(LrcWord((rawTime - offsetMs).coerceAtLeast(0), plain.length))
            p = end + 1
        }
        return plain.toString()
    }

    /** 读 [line] 中 [from] 到 [close] 之间的 `mm:ss` / `mm:ss.fff`; 不是时间戳返回 -1。 */
    private fun parseTime(line: String, from: Int, close: Int): Int {
        var min = -1
        var sec = -1
        var p = from
        while (p < close) {
            val c = line[p]
            // 部分网易云歌词把 ms 字段写成负数, 负号之后的数字一律丢弃
            if (c == '-') break
            if (c !in '0'..'9') {
                p++
                continue
            }
            var value = 0
            var digits = 0
            while (p < close && line[p] in '0'..'9') {
                value = value * 10 + (line[p] - '0')
                digits++
                p++
            }
            when {
                min < 0 -> min = value
                sec < 0 -> sec = value
                else -> {
                    // ms 不足 3 位按左对齐补零: "5" -> 500, "50" -> 500
                    var ms = value
                    while (digits < 3) {
                        ms *= 10
                        digits++
                    }
                    return min * 60_000 + sec * 1000 + ms
                }
            }
        }
        if (sec < 0) return -1
        return min * 60_000 + sec * 1000
    }

    /** 读 `[offset:+500]` 的毫秒值, [from] 为 `offset:` 之后的下标。 */
    private fun parseOffset(line: String, from: Int, close: Int): Int {
        var p = from
        while (p < close && line[p].isWhitespace()) p++
        val negative = p < close && line[p] == '-'
        if (p < close && (negative || line[p] == '+')) p++
        var value = 0
        while (p < close && line[p] in '0'..'9') {
            value = value * 10 + (line[p] - '0')
            p++
        }
        return if (negative) -value else value
    }
}
