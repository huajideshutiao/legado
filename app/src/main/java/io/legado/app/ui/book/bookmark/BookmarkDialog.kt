package io.legado.app.ui.book.bookmark

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class BookmarkDialog() : BaseComposeDialogFragment() {

    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            // 新建书签未入库无主键可传, 存 json 快照(书签行无并发写者, 语义同原 parcel)
            putString("bookmark", GSON.toJson(bookmark))
        }
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        val bookmark = remember {
            arguments?.getString("bookmark")
                ?.let { GSON.fromJsonObject<Bookmark>(it).getOrNull() }
        } ?: let {
            dismiss()
            return
        }
        val editPos = arguments?.getInt("editPos", -1) ?: -1
        var bookText by remember { mutableStateOf(bookmark.bookText) }
        var content by remember { mutableStateOf(bookmark.content) }
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = stringResource(R.string.bookmark),
                onBack = { dismissAllowingStateLoss() }
            )
            Text(
                text = bookmark.chapterName,
                color = colors.primaryText,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                AppOutlinedTextField(
                    value = bookText,
                    onValueChange = { bookText = it },
                    label = stringResource(R.string.content),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                )
                AppOutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = stringResource(R.string.note_content),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                if (editPos >= 0) {
                    AppTextButton(stringResource(R.string.delete)) {
                        execute {
                            appDb.bookmarkDao.delete(bookmark)
                        }.onSuccess { dismiss() }
                    }
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(stringResource(R.string.cancel)) { dismiss() }
                AppTextButton(stringResource(R.string.ok)) {
                    bookmark.bookText = bookText
                    bookmark.content = content
                    execute {
                        appDb.bookmarkDao.insert(bookmark)
                    }.onSuccess { dismiss() }
                }
            }
        }
    }

}
