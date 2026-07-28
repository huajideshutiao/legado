package io.legado.app.ui.widget.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.FastScrollLazyColumn
import io.legado.app.ui.compose.linkifyText
import io.legado.app.ui.compose.theme.AppTheme

@Suppress("unused")
class TextListDialog() : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    constructor(title: String, values: ArrayList<String>) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putStringArrayList("values", values)
        }
    }

    @Composable
    override fun Content() {
        val values = arguments?.getStringArrayList("values") ?: arrayListOf()
        val colors = AppTheme.colors
        Column(Modifier.fillMaxSize()) {
            DialogTitleBar(
                title = arguments?.getString("title") ?: "",
                onBack = { dismissAllowingStateLoss() }
            )
            // 逐项 SelectionContainer + Text(LinkAnnotation.Url)
            // Text 内置 LinkAnnotation.Url 自动打开链接；逐项 SelectionContainer 负责长按选择
            FastScrollLazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(values) { item ->
                    val annotated = remember(item) { linkifyText(item, colors.accent) }
                    SelectionContainer {
                        Text(
                            text = annotated,
                            color = colors.primaryText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
