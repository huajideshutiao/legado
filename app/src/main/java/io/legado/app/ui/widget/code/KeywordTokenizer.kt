package io.legado.app.ui.widget.code

import android.widget.MultiAutoCompleteTextView

class KeywordTokenizer : MultiAutoCompleteTextView.Tokenizer {

    override fun findTokenStart(charSequence: CharSequence, cursor: Int): Int {
        var i = cursor - 1
        while (i >= 0) {
            if (isDelimiter(charSequence[i])) {
                return i + 1
            }
            i--
        }
        return 0
    }

    override fun findTokenEnd(charSequence: CharSequence, cursor: Int): Int {
        return charSequence.length
    }

    override fun terminateToken(charSequence: CharSequence): CharSequence {
        return charSequence
    }

    companion object {
        private val delimiterMap = BooleanArray(128).apply {
            " \n(){}[]<>;=+-*/!&|?:,".forEach { this[it.code] = true }
        }

        private fun isDelimiter(c: Char): Boolean {
            return c.code < 128 && delimiterMap[c.code]
        }
    }
}