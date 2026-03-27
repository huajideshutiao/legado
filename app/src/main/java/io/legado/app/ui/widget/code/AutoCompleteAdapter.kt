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
    completions: Map<String, List<String>> = emptyMap()
) : BaseAdapter(), Filterable {

    private val inflater = LayoutInflater.from(context)
    private var completions: Map<String, List<String>> = emptyMap()
    private var filteredResults: List<String> = emptyList()
    private var originalInput: String = ""
    private val filter = CompletionFilter()
    var appendParentheses: Boolean = true

    init {
        this.completions = completions
    }

    fun setCompletions(completions: Map<String, List<String>>) {
        this.completions = completions
    }

    override fun getCount(): Int = filteredResults.size

    override fun getItem(position: Int): Any = filteredResults[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_1line_text_and_del, parent, false)
        val textView = view.findViewById<TextView>(R.id.text_view)
        textView.text = filteredResults[position]
        return view
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

            if (input.contains(".")) {
                val dotIndex = input.lastIndexOf(".")
                val prefix = input.substring(0, dotIndex)
                val suffix = input.substring(dotIndex + 1)

                if (suffix.isNotEmpty()) {
                    completions[prefix]?.let { subCompletions ->
                        for (item in subCompletions) {
                            val score = fuzzyMatchScore(suffix, item)
                            if (score > 0) {
                                val existing = scoredMatches.indexOfFirst { it.first == item }
                                if (existing < 0) {
                                    scoredMatches.add(Pair(item, score))
                                }
                            }
                        }
                    }
                }
            } else {
                for ((key, _) in completions) {
                    val score = fuzzyMatchScore(input, key)
                    if (score > 0) {
                        val existing = scoredMatches.indexOfFirst { it.first == key }
                        if (existing < 0) {
                            scoredMatches.add(Pair(key, score))
                        }
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

            val patternLower = pattern.lowercase()
            val targetLower = target.lowercase()

            if (targetLower.startsWith(patternLower)) {
                return when {
                    targetLower == patternLower -> 100
                    target.length == pattern.length + 2 && targetLower.endsWith("()") -> 95
                    else -> 90
                }
            }

            if (targetLower.contains(patternLower)) {
                return 70
            }

            var patternIndex = 0
            for (char in targetLower) {
                if (patternIndex < patternLower.length && char == patternLower[patternIndex]) {
                    patternIndex++
                }
            }
            return if (patternIndex == patternLower.length) 50 else 0
        }
    }
}
