package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.getPrefString

class ThemeBottomNavigationView(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    init {
        val bg =
            context.getPrefString(if (AppConfig.isNightTheme) PreferKey.bgImageN else PreferKey.bgImage)
        val bgColor = if (bg.isNullOrBlank()) context.bottomBackground else Color.TRANSPARENT
        setBackgroundColor(bgColor)
        val textIsDark =
            ColorUtils.isColorLight(if (bg.isNullOrBlank()) bgColor else context.backgroundColor)
        val textColor = context.getSecondaryTextColor(textIsDark)
        val colorStateList = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(ThemeStore.accentColor).create()
        itemIconTintList = colorStateList
        itemTextColor = colorStateList

        if (AppConfig.isEInkMode) {
            isItemHorizontalTranslationEnabled = false
            itemBackground = Color.TRANSPARENT.toDrawable()
        }

        applyUserPrefs()

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    private fun applyUserPrefs() {
        itemIconSize = dp(AppConfig.bottomBarIconSize)
        labelVisibilityMode = when (AppConfig.bottomBarLabelMode) {
            1 -> NavigationBarView.LABEL_VISIBILITY_LABELED
            2 -> NavigationBarView.LABEL_VISIBILITY_SELECTED
            3 -> NavigationBarView.LABEL_VISIBILITY_AUTO
            else -> NavigationBarView.LABEL_VISIBILITY_UNLABELED
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val targetHeight = dp(AppConfig.bottomBarHeight)
        if (layoutParams != null && layoutParams.height != targetHeight) {
            layoutParams = layoutParams.also { it.height = targetHeight }
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

}
