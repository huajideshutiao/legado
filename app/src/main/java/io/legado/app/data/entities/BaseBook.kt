package io.legado.app.data.entities

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank

interface BaseBook : RuleDataInterface {
    var name: String
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
            variable = GSON.toJson(variableMap)
        }
        return true
    }

    fun putCustomVariable(value: String?) {
        putVariable("custom", value)
    }

    fun getCustomVariable(): String {
        return getVariable("custom")
    }

    fun showBookVariableDialog(activity: AppCompatActivity, source: BaseSource?) {
        val sourceComment = when (source) {
            is BookSource -> source.variableComment
            else -> return
        }
        val defaultComment = """书籍变量可在js中通过book.getVariable("custom")获取"""
        val comment = if (sourceComment.isNullOrBlank()) {
            defaultComment
        } else {
            "${sourceComment}\n$defaultComment"
        }
        val variable = getCustomVariable()
        activity.showDialogFragment(
            VariableDialog(
                activity.getString(R.string.set_book_variable),
                bookUrl,
                variable,
                comment
            ) { _, v -> putCustomVariable(v) }
        )
    }

    override fun putBigVariable(key: String, value: String?) {
        RuleBigDataHelp.putBookVariable(bookUrl, key, value)
    }

    override fun getBigVariable(key: String): String? {
        return RuleBigDataHelp.getBookVariable(bookUrl, key)
    }

    fun getKindList(): List<String> {
        val kindList = arrayListOf<String>()
        wordCount?.let {
            if (it.isNotBlank()) kindList.add(it)
        }
        kind?.let {
            val kinds = it.splitNotBlank(",", "\n")
            kindList.addAll(kinds)
        }
        return kindList
    }
}