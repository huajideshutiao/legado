package io.legado.app.ui.compose.component.code

import androidx.compose.ui.text.TextRange

/**
 * 轻量自动补全 (对齐 app 端 `ui/widget/code/AutoCompleteAdapter` + `KeywordTokenizer`):
 * 词表/fuzzy 匹配/词边界 token 识别/插入逻辑均为纯函数, 不依赖 View。
 * 集成在 [CodeTextField] 内: 聚焦输入时按光标处 token 弹候选, 点击插入。
 */

/** 补全词表, 1:1 移植 `AutoCompleteAdapter.defaultCompletions` (去掉末尾空串) */
internal val CodeCompletions: Map<String, List<String>?> = mapOf(
    "*." to listOf(
        "map()",
        "forEach()",
        "toString()",
        "test()",
        "forEach(i=>{#in})",
        "entries()",
        "keys()",
        "values()",
        "length",
        "join()",
        "push()",
        "charAt()",
        "charCodeAt()",
        "endsWith()",
        "includes()",
        "indexOf()",
        "lastIndexOf()",
        "match()",
        "repeat()",
        "replace()",
        "replaceAll()",
        "slice()",
        "split()",
        "startsWith()",
        "trim()",
        "parseInt()",
    ),
    "JSON." to listOf("parse()", "stringify()"),
    "Date." to listOf("now()", "parse()"),
    "java." to listOf(
        // Network
        "ajax()",
        "ajaxAll()",
        "get()",
        "post()",
        "connect()",
        // Browser
        "startBrowser()",
        "startBrowserAwait()",
        "openUrl()",
        "webView()",
        "webViewGetSource()",
        "webViewGetOverrideUrl()",
        // User-Agent
        "getWebViewUA()",
        "getVerificationCode()",
        // Cookie
        "getCookie()",
        // Cache / File (2026-08-06 补: 对照 JsExtensionsCommon 真实 API)
        "cacheFile()",
        "getFile()",
        "readFile()",
        "readTxtFile()",
        "deleteFile()",
        "getTxtInFolder()",
        "importScript()",
        "downloadFile()",
        // Archive
        "unzipFile()",
        "unrarFile()",
        "un7zFile()",
        "unArchiveFile()",
        "getZipByteArrayContent()",
        "getZipStringContent()",
        "getRarByteArrayContent()",
        "getRarStringContent()",
        "get7zByteArrayContent()",
        "get7zStringContent()",
        // Font 反混淆
        "queryTTF()",
        "replaceFont()",
        // Encoding
        "encodeURI()",
        "base64Decode()",
        "base64DecodeToByteArray()",
        "base64Encode()",
        "hexDecodeToByteArray()",
        "hexDecodeToString()",
        "hexEncodeToString()",
        // Crypto
        "createSymmetricCrypto()",
        "createAsymmetricCrypto()",
        "createSign()",
        "digestHex()",
        "digestBase64Str()",
        "md5Encode()",
        "md5Encode16()",
        "HMacHex()",
        "HMacBase64()",
        // ByteArray
        "strToBytes()",
        "bytesToStr()",
        // Chinese
        "t2s()",
        "s2t()",
        // Time
        "timeFormatUTC()",
        "timeFormat()",
        "log()",
        "logType()",
        "toast()",
        "longToast()",
        // Misc (2026-08-06 补)
        "refreshUi()",
        "randomUUID()",
        "toURL()",
        "htmlFormat()",
        "toNumChapter()",
        "androidId()",
        // AnalyzRule
        "getString()",
        "getStringList()",
        "getElement()",
        "getElements()",
        "setContent()",
        "refreshTocUrl()",
        "put()",
        // AnalyzeUrl ()
        "initUrl()",
        "getHeaderMap()",
        "getStrResponse()",
        "getResponse()",
    ),
    "source." to listOf(
        "getKey()",
        "variable",
        "loginHeader",
        "getLoginHeaderMap()",
        "putLoginHeader()",
        "removeLoginHeader()",
        "loginInfo",
        "loginInfoMap",
        "removeLoginInfo()",
    ),
    "book." to listOf(
        "bookUrl",
        "tocUrl",
        "origin",
        "originName",
        "name",
        "author",
        "kind",
        "coverUrl",
        "intro",
        "type",
        "group",
        "latestChapterTitle",
        "latestChapterTime",
        "lastCheckTime",
        "lastCheckCount",
        "totalChapterNum",
        "durChapterTitle",
        "durChapterIndex",
        "durChapterPos",
        "durChapterTime",
        "canUpdate",
        "order",
        "originOrder",
        "variable",
        "save()",
        "delete()",
    ),
    "chapter." to listOf(
        "url",
        "title",
        "baseUrl",
        "bookUrl",
        "index",
        "resourceUrl",
        "tag",
        "start",
        "end",
        "variable",
    ),
    "cookie." to listOf(
        "getCookie()", "getKey()", "setCookie()", "replaceCookie()", "removeCookie()",
    ),
    "cache." to listOf(
        "put()",
        "get()",
        "delete()",
        "putFile()",
        "getFile()",
        "deleteFile()",
        "putMemory()",
        "getFromMemory()",
        "deleteMemory()",
    ),
    "result" to null,
    "baseUrl" to null,
    "src" to null,
)

/** 词边界识别, 1:1 移植 `KeywordTokenizer`: 这些字符截断 token */
private val tokenDelimiters = " \n(){}[]<>;=+-*/!&|?:,"

internal fun isTokenDelimiter(c: Char): Boolean = c.code < 128 && tokenDelimiters.indexOf(c) >= 0

/** 光标处 token 起点 (对齐 `KeywordTokenizer.findTokenStart` 从光标向左扫到分隔符) */
internal fun findTokenStart(text: CharSequence, cursor: Int): Int {
    var i = cursor - 1
    while (i >= 0 && !isTokenDelimiter(text[i])) i--
    return i + 1
}

/** fuzzy 匹配评分, 1:1 移植 `AutoCompleteAdapter.fuzzyMatchScore` */
internal fun fuzzyMatchScore(pattern: String, target: String): Int {
    if (pattern.isEmpty()) return 0
    val patternLength = pattern.length
    if (patternLength > target.length) return 0
    if (target.startsWith(pattern, ignoreCase = true)) {
        return when {
            target.equals(pattern, ignoreCase = true) -> 100
            target.length == patternLength + 2 && target.endsWith("()") -> 95
            else -> 90
        }
    }
    if (target.contains(pattern, ignoreCase = true)) return 70
    var patternIndex = 0
    for (i in target.indices) {
        if (patternIndex < patternLength && target[i].equals(
                pattern[patternIndex],
                ignoreCase = true
            )
        ) {
            patternIndex++
        }
    }
    return if (patternIndex == patternLength) 50 else 0
}

private val wordPattern = Regex("\\b[a-zA-Z_]\\w{0,29}\\b")

/** 当前行已出现的单词也作为候选 (对齐 `addEditorWords`: 编辑器内词联想) */
private fun addEditorWords(
    target: String,
    lineText: String,
    scoredMatches: MutableList<Pair<String, Int>>,
    addedItems: MutableSet<String>,
) {
    if (lineText.isEmpty()) return
    for (m in wordPattern.findAll(lineText)) {
        val word = m.value
        if (word == target) continue
        val score = fuzzyMatchScore(target, word)
        if (score > 0 && addedItems.add(word)) scoredMatches.add(word to score)
    }
}

/**
 * 计算当前输入串的补全候选, 1:1 移植 `CompletionFilter.performFiltering`:
 * 含点走子词表 (前缀取词表 key, 兜底 "*."), 不含点走词表 key + 空 key 列表;
 * 两者都追加编辑器行内词, 按分数降序。
 */
internal fun findCodeCompletions(input: String, lineText: String): List<String> {
    if (input.isEmpty()) return emptyList()
    val scoredMatches = ArrayList<Pair<String, Int>>(32)
    val addedItems = HashSet<String>(32)
    if (input.contains(".")) {
        val dotIndex = input.lastIndexOf(".")
        val prefix = input.take(dotIndex + 1)
        val suffix = input.substring(dotIndex + 1)
        if (suffix.isNotEmpty()) {
            val subCompletions = CodeCompletions[prefix]
                ?: CodeCompletions[input.take(dotIndex)]
                ?: CodeCompletions["*."]
            subCompletions?.forEach { item ->
                if (item == suffix) return@forEach
                val score = fuzzyMatchScore(suffix, item)
                if (score > 0 && addedItems.add(item)) scoredMatches.add(item to score)
            }
            addEditorWords(suffix, lineText, scoredMatches, addedItems)
        }
    } else {
        val items = CodeCompletions.keys + (CodeCompletions[""] ?: emptyList())
        for (item in items) {
            if (item == input) continue
            val score = fuzzyMatchScore(input, item)
            if (score > 0 && addedItems.add(item)) scoredMatches.add(item to score)
        }
        addEditorWords(input, lineText, scoredMatches, addedItems)
    }
    scoredMatches.sortByDescending { it.second }
    return scoredMatches.map { it.first }
}

/**
 * 在 [text] 的 [selection] 处插入补全, 1:1 移植 `CodeView.replaceText`:
 * 保留点前缀 (input 已含 "prefix."), 追加补全文本; 补全以 "()" 结尾时光标落在括号内;
 * 含 "#in" 占位符时剔除并定位到占位符起点 (对齐原版 InputFilter + setSelection)。
 * 返回 (新文本, 新选区)。
 */
internal fun applyCompletion(
    text: String,
    selection: TextRange,
    completion: String,
): Pair<String, TextRange> {
    val tokenStart = findTokenStart(text, selection.start)
    val tokenEnd = selection.end
    val originalInput = text.substring(tokenStart, tokenEnd)
    val prefixBeforeDot = if (originalInput.contains(".")) {
        originalInput.take(originalInput.lastIndexOf(".") + 1)
    } else {
        ""
    }
    val insertText = prefixBeforeDot + completion
    return if (insertText.contains("#in")) {
        val first = insertText.indexOf("#in")
        val cleaned = insertText.replace("#in", "")
        val adjusted = text.substring(0, tokenStart) + cleaned + text.substring(tokenEnd)
        adjusted to TextRange(tokenStart + first, tokenStart + first)
    } else {
        val newText = text.substring(0, tokenStart) + insertText + text.substring(tokenEnd)
        val cursor = if (completion.endsWith("()")) {
            tokenStart + insertText.length - 1
        } else {
            tokenStart + insertText.length
        }
        newText to TextRange(cursor, cursor)
    }
}

/** 光标后紧跟字母/数字/下划线时不应弹候选 (对齐 `performFiltering` 里的 dismiss 条件) */
internal fun shouldSuppressCompletion(text: String, cursor: Int): Boolean {
    if (cursor in 0 until text.length) {
        val c = text[cursor]
        if (c.isLetterOrDigit() || c == '_') return true
    }
    return false
}
