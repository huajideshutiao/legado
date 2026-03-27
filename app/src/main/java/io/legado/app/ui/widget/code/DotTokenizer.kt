package io.legado.app.ui.widget.code

import android.widget.MultiAutoCompleteTextView
import kotlin.math.max

class DotTokenizer : MultiAutoCompleteTextView.Tokenizer {
    override fun findTokenStart(charSequence: CharSequence, cursor: Int): Int {
        val sequenceStr = charSequence.toString().take(cursor)

        val delimiters = charArrayOf(' ', '\n', '(', ')', '{', '}', '[', ']', ',', ';', '=', '+', '-', '*', '/', '<', '>', '!', '&', '|', '?', ':')
        
        var lastIndex = -1
        for (delimiter in delimiters) {
            val index = sequenceStr.lastIndexOf(delimiter)
            lastIndex = max(lastIndex, index)
        }

        val startIndex = if (lastIndex >= 0) lastIndex + 1 else 0
        return max(0, startIndex)
    }

    override fun findTokenEnd(charSequence: CharSequence, cursor: Int): Int {
        return charSequence.length
    }

    override fun terminateToken(charSequence: CharSequence): CharSequence {
        return charSequence
    }
}
