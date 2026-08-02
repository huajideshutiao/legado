package io.legado.app.ui.config

import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.model.BookCover
import io.legado.app.model.CoverRatio
import io.legado.app.model.DefaultCoverEntry
import io.legado.app.model.bakedPath
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.rememberResponsiveColumns
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.FlowBus
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 默认封面图集管理 -- 网格列表展示已选封面,末尾 + 按钮添加。
 * arguments 传 isNight 切换日/夜偏好；封面为烘焙 3:4 webp/.9.png/生成式默认图，
 * 经 Drawable 直绘（.9 保 chunk 拉伸），与原 CoverImageView 呈现一致。
 */
class DefaultCoverGalleryDialog() : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    constructor(isNight: Boolean) : this() {
        arguments = Bundle().apply { putBoolean("isNight", isNight) }
    }

    private val prefKey: String
        get() = if (arguments?.getBoolean("isNight") == true) {
            PreferKey.defaultCoverDark
        } else {
            PreferKey.defaultCover
        }

    // 数据版本号：增删后自增触发重组重取列表
    private var dataVersion by mutableIntStateOf(0)

    private val pickImage by lazy {
        registerHandleFile { result ->
            val uri = result.uri ?: return@registerHandleFile
            var fileName = "cover"
            var bytes: ByteArray? = null
            runCatching {
                readUri(uri) { fileDoc, inputStream ->
                    fileName = fileDoc.name
                    bytes = inputStream.readBytes()
                }
            }
            val safeBytes = bytes ?: run {
                appCtx.toastOnUi(R.string.error_read_file)
                return@registerHandleFile
            }
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        BookCover.addDefaultCover(prefKey, safeBytes, fileName)
                    }
                }.onFailure { appCtx.toastOnUi(it.localizedMessage) }
                dataVersion++
                // 通知封面配置页刷新 summary (对照 app 端 CoverConfigFragment prefs 监听)
                FlowBus.with(EventBus.DEFAULT_COVER_CHANGED).tryEmit(prefKey)
            }
        }
    }

    @Composable
    override fun Content() {
        dataVersion
        val entries = BookCover.listDefaultCovers(prefKey)
        Column(Modifier.fillMaxSize()) {
            DialogTitleBar(
                title = stringResource(R.string.default_cover),
                subtitle = stringResource(
                    if (prefKey == PreferKey.defaultCoverDark) R.string.night else R.string.day
                ),
                onBack = { dismissAllowingStateLoss() },
            )
            LazyVerticalGrid(
                columns = rememberResponsiveColumns(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    CoverTile(entry) { onCoverClick(entry) }
                }
                item(key = "__add__") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .aspectRatio(3f / 4f)
                            .clickable { onAddClick() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = rememberPainter("ic_add"),
                            contentDescription = stringResource(R.string.add),
                            tint = AppTheme.colors.primaryText,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun CoverTile(entry: DefaultCoverEntry, onClick: () -> Unit) {
        val drawable = remember(entry) { loadThumb(entry) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .aspectRatio(3f / 4f)
                .clickable(onClick = onClick),
        ) {
            drawable ?: return@Canvas
            drawIntoCanvas { canvas ->
                drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                drawable.draw(canvas.nativeCanvas)
            }
        }
    }

    /**
     * .9.png 走 createFromPath 保留 chunk;普通图 decodeFile,失败回落到默认封面。
     */
    private fun loadThumb(entry: DefaultCoverEntry): Drawable? {
        val path = entry.bakedPath(CoverRatio.NOVEL)
        if (!File(path).exists()) {
            return BookCover.newDefaultDrawable(CoverRatio.NOVEL, entry.id)
        }
        return if (entry.ninePatch) {
            Drawable.createFromPath(path)
        } else {
            BitmapFactory.decodeFile(path)?.toDrawable(resources)
        }
    }

    private fun onCoverClick(entry: DefaultCoverEntry) {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        BookCover.removeDefaultCover(prefKey, entry.id)
                    }
                    dataVersion++
                    FlowBus.with(EventBus.DEFAULT_COVER_CHANGED).tryEmit(prefKey)
                }
            }
            noButton()
        }
    }

    private fun onAddClick() {
        pickImage.launch { mode = HandleFileContract.IMAGE }
    }

}
