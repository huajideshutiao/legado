package io.legado.app.ui.book.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.resolveBookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.compose.component.AppSearchField
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.number.showNumberPicker
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn

/**
 * 书源选择
 */
class SourcePickerDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    private var searchKey by mutableStateOf("")
    private var sources by mutableStateOf<List<BookSourcePart>>(emptyList())

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        LaunchedEffect(searchKey) {
            when {
                searchKey.isEmpty() -> appDb.bookSourceDao.flowEnabled()
                else -> appDb.bookSourceDao.flowSearch(searchKey, true)
            }.catch {
                AppLog.put("书源选择界面获取书源数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                sources = it
            }
        }
        Column(Modifier.fillMaxWidth()) {
            // 复刻 TitleBar + contentLayout=view_search：返回箭头 + 标题 + 行内搜索框 + 溢出菜单
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.bottomBackground)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { dismissAllowingStateLoss() }) {
                    Icon(
                        painter = rememberPainter("ic_arrow_back"),
                        contentDescription = null,
                        tint = colors.primaryText,
                    )
                }
                Text(
                    text = "选择书源",
                    color = colors.primaryText,
                    fontSize = 18.sp,
                    maxLines = 1,
                )
                AppSearchField(
                    value = searchKey,
                    onValueChange = { searchKey = it },
                    hint = stringResource(R.string.search_book_source),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                OverflowMenu { dismissMenu ->
                    DropdownMenuItem(
                        onClick = {
                            dismissMenu()
                            showNumberPicker(
                                requireContext(),
                                titleResId = R.string.change_source_delay,
                                max = 9999, min = 0, value = AppConfig.batchChangeSourceDelay
                            ) {
                                AppConfig.batchChangeSourceDelay = it
                            }
                        },
                    ) {
                        Text(
                            stringResource(R.string.change_source_delay),
                            color = colors.primaryText,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn {
                    items(sources, key = { it.bookSourceUrl }) { item ->
                        Text(
                            text = item.getDisPlayNameGroup(),
                            color = colors.primaryText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSourceClick(item) }
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    private fun onSourceClick(item: BookSourcePart) {
        item.resolveBookSource()?.let { source ->
            callback?.sourceOnClick(source)
        }
        dismissAllowingStateLoss()
    }

    private val callback: Callback?
        get() {
            return (parentFragment as? Callback) ?: activity as? Callback
        }

    interface Callback {
        fun sourceOnClick(source: BookSource)
    }

}
