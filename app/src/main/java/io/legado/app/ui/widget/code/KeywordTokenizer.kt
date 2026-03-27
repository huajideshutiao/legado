package io.legado.app.ui.widget.code

class KeywordTokenizer : android.widget.MultiAutoCompleteTextView.Tokenizer {
    private val delimiters = charArrayOf(' ', '\n', '(', ')', '{', '}', '[', ']', ',', ';', '=', '+', '-', '*', '/', '<', '>', '!', '&', '|', '?', ':')

    override fun findTokenStart(charSequence: CharSequence, cursor: Int): Int {
        var i = cursor - 1
        while (i >= 0) {
            if (delimiters.contains(charSequence[i])) {
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
}