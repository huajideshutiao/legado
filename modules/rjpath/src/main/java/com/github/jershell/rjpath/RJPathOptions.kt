package com.github.jershell.rjpath

class RJPathOptions(
    val regexMatchMode: RegexMatchMode
) {
    companion object {
        val Default  = RJPathOptions(
            regexMatchMode = RegexMatchMode.CONTAINS
        )
    }
}
