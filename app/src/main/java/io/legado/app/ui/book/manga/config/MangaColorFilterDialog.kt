package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDetailSeekBar
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

class MangaColorFilterDialog : BaseComposeDialogFragment() {

    private val mConfig =
        GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
            ?: MangaColorFilterConfig()
    private val callback get() = activity as? Callback

    private var ct by mutableStateOf(mConfig.ct)
    private var r by mutableStateOf(mConfig.r)
    private var g by mutableStateOf(mConfig.g)
    private var b by mutableStateOf(mConfig.b)
    private var gray by mutableStateOf(AppConfig.enableMangaGray)

    override fun onStart() {
        super.onStart()
        // 不压暗背景，便于实时预览滤镜效果
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = stringResource(R.string.manga_color_filter),
                onBack = { dismissAllowingStateLoss() },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                AppDetailSeekBar(
                    title = stringResource(R.string.contrast),
                    value = ct + 50,
                    max = 100,
                    valueFormat = { "${it - 50}" },
                    onChanged = {
                        ct = it - 50
                        mConfig.ct = ct
                        callback?.updateColorFilter(mConfig)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AppDetailSeekBar(
                    title = "R",
                    value = r,
                    max = 255,
                    onChanged = {
                        r = it
                        mConfig.r = it
                        callback?.updateColorFilter(mConfig)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AppDetailSeekBar(
                    title = "G",
                    value = g,
                    max = 255,
                    onChanged = {
                        g = it
                        mConfig.g = it
                        callback?.updateColorFilter(mConfig)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AppDetailSeekBar(
                    title = "B",
                    value = b,
                    max = 255,
                    onChanged = {
                        b = it
                        mConfig.b = it
                        callback?.updateColorFilter(mConfig)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { upGray(!gray) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(checked = gray, onCheckedChange = { upGray(it) })
                    Text(stringResource(R.string.enable_manga_gray), color = colors.primaryText)
                }
            }
        }
    }

    private fun upGray(enable: Boolean) {
        gray = enable
        AppConfig.enableMangaGray = enable
        callback?.updateGray(enable)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaColorFilter = mConfig.toJson()
    }

    interface Callback {
        fun updateColorFilter(config: MangaColorFilterConfig)
        fun updateGray(enable: Boolean)
    }

}
