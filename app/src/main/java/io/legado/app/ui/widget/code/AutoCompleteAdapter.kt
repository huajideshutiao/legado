package io.legado.app.ui.widget.code

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import io.legado.app.R

class AutoCompleteAdapter(
    context: Context,
    completions: Map<String, List<String>> = mapOf(
        "" to listOf("java.log()"),
        "java." to listOf(
            // Network
            "ajax()",
            "ajaxAll()",
            "connect()",
            "get()",
            "post()",
            "head()",
            // WebView
            "webView()",
            // Browser
            "startBrowser()",
            "startBrowserAwait()",
            // URL
            "openUrl()",
            // Activity
            "startJsActivity()",
            // User-Agent
            "getWebViewUA()",
            // Verification
            "getVerificationCode()",
            // Cookie
            "getCookie()",
            // Cache
            "cacheFile()",
            // Encoding
            "encodeURI()",
            "base64Decode()",
            "base64DecodeToByteArray()",
            "base64Encode()",
            "hexDecodeToByteArray()",
            "hexDecodeToString()",
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
            // ID
            "randomUUID()",
            "androidId()",
            // Chinese
            "t2s()",
            "s2t()",
            // Time
            "timeFormatUTC()",
            "timeFormat()",
            // HTML
            "htmlFormat()",
            // Debug
            "log()",
            "logType()",
            // Toast
            "toast()",
            "longToast()",
            // AnalyzRule
            "getString()",
            "getStringList()",
            "getElement()",
            "getElements()",
            "setContent()",
            "reGetBook()",
            "refreshTocUrl()",
            "put()",
            // AnalyzeUrl ()
            "initUrl()",
            "getHeaderMap()",
            "getStrResponse()",
            "getResponse()"
        ),
        "source." to listOf(
            "getKey()",
            "setVariable()",
            "getVariable()",
            "getLoginHeader()",
            "getLoginHeaderMap()",
            "putLoginHeader()",
            "removeLoginHeader()",
            "getLoginInfo()",
            "getLoginInfoMap()",
            "removeLoginInfo()"
        ),
        "book." to listOf(
            "bookUrl",
            "tocUrl",
            "origin",
            "originName",
            "name",
            "author",
            "kind",
            "customTag",
            "coverUrl",
            "customCoverUrl",
            "intro",
            "customIntro",
            "charset",
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
            "variable"
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
            "variable"
        ),
        "cookie." to listOf(
            "getCookie()", "getKey()", "setCookie()", "replaceCookie()", "removeCookie()"
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
            "deleteMemory()"
        ),
        "result" to listOf(),
        "baseUrl" to listOf(),
        "title" to listOf(),
        "src" to listOf(),
        "nextChapterUrl" to listOf()
    )
) : BaseAdapter(), Filterable {

    private val inflater = LayoutInflater.from(context)
    var completions: Map<String, List<String>> = emptyMap()
    private var filteredResults: List<String> = emptyList()
    private var originalInput: String = ""
    private val filter = CompletionFilter()

    init {
        this.completions = completions
    }

    override fun getCount(): Int = filteredResults.size

    override fun getItem(position: Int): Any = filteredResults[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val viewHolder: ViewHolder
        if (convertView == null) {
            view = inflater.inflate(R.layout.item_1line_text_and_del, parent, false)
            viewHolder = ViewHolder()
            viewHolder.textView = view.findViewById(R.id.text_view)
            view.tag = viewHolder
        } else {
            view = convertView
            viewHolder = view.tag as ViewHolder
        }
        viewHolder.textView?.text = filteredResults[position]
        return view
    }

    private class ViewHolder {
        var textView: TextView? = null
    }

    override fun getFilter(): Filter = filter

    fun getOriginalInput(): String = originalInput

    private inner class CompletionFilter : Filter() {
        override fun convertResultToString(resultValue: Any?): CharSequence {
            return resultValue?.toString() ?: ""
        }

        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val results = FilterResults()
            originalInput = constraint?.toString() ?: ""

            val scoredMatches = mutableListOf<Pair<String, Int>>()
            val input = originalInput

            if (input.isEmpty()) {
                results.values = emptyList<String>()
                results.count = 0
                return results
            }

            val addedItems = HashSet<String>()

            if (input.contains(".")) {
                val dotIndex = input.lastIndexOf(".")
                val prefix = input.take(dotIndex + 1)
                val suffix = input.substring(dotIndex + 1)

                if (suffix.isNotEmpty()) {
                    val subCompletions = completions[prefix] ?: completions[input.take(dotIndex)]
                    subCompletions?.let {
                        for (item in it) {
                            val score = fuzzyMatchScore(suffix, item)
                            if (score > 0) {
                                if (addedItems.add(item)) {
                                    scoredMatches.add(Pair(item, score))
                                }
                            }
                        }
                    }
                }
            } else {
                val items = completions.keys + (completions[""] ?: emptyList())
                for (item in items) {
                    val score = fuzzyMatchScore(input, item)
                    if (score > 0 && addedItems.add(item)) {
                        scoredMatches.add(Pair(item, score))
                    }
                }
            }

            scoredMatches.sortByDescending { it.second }
            results.values = scoredMatches.map { it.first }
            results.count = scoredMatches.size
            return results
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            filteredResults = results.values as? List<String> ?: emptyList()
            if (results.count > 0) {
                notifyDataSetChanged()
            } else {
                notifyDataSetInvalidated()
            }
        }
    }

    companion object {
        fun fuzzyMatchScore(pattern: String, target: String): Int {
            if (pattern.isEmpty()) return 0
            if (pattern.length > target.length) return 0

            if (target.startsWith(pattern, ignoreCase = true)) {
                return when {
                    target.equals(pattern, ignoreCase = true) -> 100
                    target.length == pattern.length + 2 && target.endsWith("()") -> 95
                    else -> 90
                }
            }

            if (target.contains(pattern, ignoreCase = true)) {
                return 70
            }

            var patternIndex = 0
            val patternLength = pattern.length
            for (i in target.indices) {
                if (patternIndex < patternLength && target[i].equals(pattern[patternIndex], ignoreCase = true)) {
                    patternIndex++
                }
            }
            return if (patternIndex == patternLength) 50 else 0
        }
    }
}
