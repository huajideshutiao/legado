package io.legado.app.data.entities

import io.legado.app.help.RuleBigDataProviders
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.encodeStringMap
import io.legado.app.utils.pureKindText
import io.legado.app.utils.splitNotBlank

interface BaseBook : RuleDataInterface, BookLike {
    override var name: String
    var author: String
    var bookUrl: String
    var kind: String?
    var wordCount: String?
    var variable: String?

    var infoHtml: String?
    var tocHtml: String?

    var origin: String
    var originName: String
    var type: Int
    var coverUrl: String?
    var intro: String?
    var latestChapterTitle: String?
    var tocUrl: String
    var originOrder: Int

    override fun putVariable(key: String, value: String?): Boolean {
        if (super.putVariable(key, value)) {
            variable = encodeStringMap(variableMap)
        }
        return true
    }

    fun putCustomVariable(value: String?) {
        putVariable("custom", value)
    }

    fun getCustomVariable(): String {
        return getVariable("custom")
    }

    override fun putBigVariable(key: String, value: String?) {
        RuleBigDataProviders.impl?.putBookVariable(bookUrl, key, value)
    }

    override fun getBigVariable(key: String): String? {
        return RuleBigDataProviders.impl?.getBookVariable(bookUrl, key)
    }

    fun getKindList(): List<String> {
        val kindList = arrayListOf<String>()
        wordCount?.let {
            it.splitNotBlank(",", "\n").forEach { raw ->
                if (raw.isNotBlank()) kindList.add(raw)
            }
        }
        kind?.let {
            it.splitNotBlank(",", "\n").forEach { raw ->
                val pure = raw.pureKindText()
                if (pure.isNotBlank()) kindList.add(pure)
            }
        }
        return kindList
    }

    fun getRealAuthor() = author.splitNotBlank("\n").joinToString("\n") { it.split("::")[0] }
}
