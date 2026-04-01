@file:Suppress("unused")

package io.legado.app.ui.widget.code

import android.content.Context
import android.widget.ArrayAdapter
import io.legado.app.R
import splitties.init.appCtx
import splitties.resources.color
import java.util.regex.Pattern

val legadoPattern: Pattern = Pattern.compile("\\|\\||&&|%%|@@|@(js|Json|css|XPath):")

// 推荐用法（Kotlin 原生字符串）：
val jsonPattern: Pattern =
    Pattern.compile("""(?<!\\)(["'`])(?:\\.|(?!\1)[^\\\n])*\1|[\[\]{}]""")
val wrapPattern: Pattern = Pattern.compile("\\\\n")
val operationPattern: Pattern =
    Pattern.compile("!=|[:=><%+\\-^&|?*]")
val jsPattern: Pattern =
    Pattern.compile("\\b(?:var|let|const|if|else|for|while|do|switch|case|break|continue|return|new|this|true|false|null|undefined|in|typeof|try|catch|finally|throw|function|class)\\b")

fun CodeView.addLegadoPattern() {
    addSyntaxPattern(legadoPattern, appCtx.color(R.color.md_orange_900))
}

fun CodeView.addJsonPattern() {
    addSyntaxPattern(jsonPattern, appCtx.color(R.color.md_blue_800))
}

fun CodeView.addJsPattern() {
    addSyntaxPattern(wrapPattern, appCtx.color(R.color.md_blue_grey_500))
    addSyntaxPattern(operationPattern, appCtx.color(R.color.md_orange_900))
    addSyntaxPattern(jsPattern, appCtx.color(R.color.md_light_blue_600))
}

fun Context.arrayAdapter(keywords: Array<String>): ArrayAdapter<String> {
    return ArrayAdapter(this, R.layout.item_1line_text_and_del, R.id.text_view, keywords)
}