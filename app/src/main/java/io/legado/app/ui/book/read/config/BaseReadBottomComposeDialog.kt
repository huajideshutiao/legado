package io.legado.app.ui.book.read.config

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.base.IBottomDialog
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.utils.setupAsBottomDialog

/**
 * 阅读页底部弹窗 Compose 宿主：底栏色背景 + Gravity.BOTTOM + bottomDialog 计数，
 * 契约对齐 BaseBottomDialogFragment / P3 迁的 MoreConfigDialog。
 */
abstract class BaseReadBottomComposeDialog : BasePrefDialogFragment() {

    /** 弹窗高度（px），默认 WRAP_CONTENT */
    protected open val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    protected open val dismissWhenOtherBottomDialogShowing: Boolean = false
    private var counterIncremented = false

    @Composable
    abstract fun Content()

    override fun onStart() {
        super.onStart()
        dialog?.window?.setupAsBottomDialog(dialogHeight)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setBackgroundColor(requireContext().bottomBackground)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            // 注入 Android actual Provider，供 commonMain AppTheme 通过 LocalXxx 取依赖
            val themeStoreProvider = remember { AndroidThemeStoreProvider() }
            val appConfigProvider = remember { AndroidAppConfigProvider() }
            val eventBusProvider = remember { AndroidEventBusProvider() }
            val preferenceStoreProvider = remember { AndroidPreferenceStoreProvider() }
            CompositionLocalProvider(
                LocalThemeStoreProvider provides themeStoreProvider,
                LocalAppConfigProvider provides appConfigProvider,
                LocalEventBusProvider provides eventBusProvider,
                LocalPreferenceStoreProvider provides preferenceStoreProvider,
            ) {
                AppTheme {
                    this@BaseReadBottomComposeDialog.Content()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = activity as? IBottomDialog
        if (dismissWhenOtherBottomDialogShowing && (host?.bottomDialog ?: 0) > 0) {
            dismiss()
            return
        }
        host?.let {
            it.bottomDialog++
            counterIncremented = true
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (counterIncremented) {
            (activity as? IBottomDialog)?.let { it.bottomDialog-- }
            counterIncremented = false
        }
    }
}

/** 底部弹窗图标按钮：图标在上 + 12sp 文字（复刻 drawableTop 的 TextView 按钮） */
@Composable
fun ReadMenuIconButton(
    iconKey: String,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .width(60.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = text,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(text, color = tint, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

/** 阅读菜单主题色 Compose 版：底栏色 + 由其亮度反推的文字色（对齐 createReadMenuTheme） */
@Immutable
data class ReadMenuColors(
    val bg: Color,
    val text: Color,
    val secondaryText: Color,
)

fun readMenuColors(context: Context): ReadMenuColors {
    val theme = createReadMenuTheme(context)
    return ReadMenuColors(
        bg = Color(theme.bgColor),
        text = Color(theme.textColor),
        secondaryText = Color(theme.secondaryTextColor),
    )
}

@Composable
fun rememberReadMenuColors(): ReadMenuColors {
    val context = LocalContext.current
    return remember { readMenuColors(context) }
}
