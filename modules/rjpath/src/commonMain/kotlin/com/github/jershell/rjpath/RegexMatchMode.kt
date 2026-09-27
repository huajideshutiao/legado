package com.github.jershell.rjpath

enum class RegexMatchMode {
    /**
     * Entire input must match the regex.
     * Equivalent to Java/Kotlin `String.matches(Regex)`
     */
    ENTIRE {
        override fun matches(regex: Regex, input: String): Boolean {
            return input.matches(regex)
        }
    },

    /**
     * Checks if any part of the input matches the regex.
     * Equivalent to `Regex.containsMatchIn()`
     */
    CONTAINS {
        override fun matches(regex: Regex, input: String): Boolean {
            return regex.containsMatchIn(input)
        }
    };

    abstract fun matches(regex: Regex, input: String): Boolean
}