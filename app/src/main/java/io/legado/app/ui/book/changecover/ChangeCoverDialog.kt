package io.legado.app.ui.book.changecover

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.throttleLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 换封面
 */
class ChangeCoverDialog() : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    constructor(name: String, author: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
        }
    }

    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeCoverViewModel by viewModels()
    private val itemsFlow = MutableStateFlow<List<SearchBook>>(emptyList())

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        val items by itemsFlow.collectAsStateWithLifecycle()
        var searching by remember { mutableStateOf(false) }
        androidx.compose.runtime.DisposableEffect(Unit) {
            val observer = androidx.lifecycle.Observer<Boolean> { searching = it }
            viewModel.searchStateData.observe(this@ChangeCoverDialog, observer)
            onDispose { viewModel.searchStateData.removeObserver(observer) }
        }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            viewModel.initData(arguments)
            lifecycle.currentStateFlow.first { it.isAtLeast(STARTED) }
            viewModel.dataFlow.throttleLatest(1_000).collect { itemsFlow.value = it }
        }

        Column(Modifier.fillMaxSize()) {
            DialogTitleBar(
                title = stringResource(R.string.change_cover_source),
                onBack = { dismissAllowingStateLoss() }
            ) {
                IconButton(onClick = { viewModel.startOrStopSearch() }) {
                    Icon(
                        painter = painterResource(
                            if (searching) R.drawable.ic_stop_black_24dp
                            else R.drawable.ic_refresh_black_24dp
                        ),
                        contentDescription = stringResource(
                            if (searching) R.string.stop else R.string.refresh
                        ),
                        tint = colors.primaryText
                    )
                }
            }
            if (searching) {
                LinearProgressIndicator(
                    color = colors.accent,
                    backgroundColor = colors.bottomBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(items, key = { it.bookUrl }) { item ->
                    CoverItem(item) { callBack?.coverChangeTo(item.coverUrl ?: ""); dismissAllowingStateLoss() }
                }
            }
        }
    }

    @Composable
    private fun CoverItem(item: SearchBook, onClick: () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .clickable(onClick = onClick)
        ) {
            AndroidView(
                factory = { ctx -> CoverImageView(ctx) },
                update = { it.loadCover(item.coverUrl, item.name, item.author, false, item.origin) },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.66f)
            )
            Text(
                text = item.originName,
                color = AppTheme.colors.primaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }

    interface CallBack {
        fun coverChangeTo(coverUrl: String)
    }
}
