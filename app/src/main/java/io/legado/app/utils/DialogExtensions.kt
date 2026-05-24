package io.legado.app.utils

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.forEach
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.filletBackground

fun AlertDialog.applyTint(): AlertDialog {
    val context = context
    window?.apply {
        decorView.setPadding(0, 0, 0, 0)
        if (AppConfig.isEInkMode) {
            val attr = attributes
            attr.dimAmount = 0f
            attr.windowAnimations = 0
            attributes = attr
            setBackgroundDrawableResource(R.drawable.bg_eink_border_dialog)
        } else {
            val attr = attributes
            attr.windowAnimations = R.style.Animation_Dialog
            attributes = attr
            // 强制不透明背景色，防止主题设置了透明背景导致对话框看不见
            val colorBackground = ColorUtils.stripAlpha(ThemeStore.backgroundColor(context))
            val bg = context.filletBackground.apply {
                setColor(colorBackground)
                alpha = 255
            }
            // 直接设置 Window 背景为圆角矩形，保持和 BaseDialogFragment 宽度一致
            setBackgroundDrawable(bg)
        }

        // 统一宽度为屏幕对应边的 90%，并设置上限
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.9).toInt().coerceAtMost((600 * dm.density).toInt())
        val maxHeight = (dm.heightPixels * 0.8).toInt()

        val h = findViewById<View>(androidx.appcompat.R.id.parentPanel)?.let { v ->
            v.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST)
            )
            if (v.measuredHeight > maxHeight) maxHeight else WindowManager.LayoutParams.WRAP_CONTENT
        } ?: WindowManager.LayoutParams.WRAP_CONTENT

        setLayout(width, h)
    }

    // 清除内部各组件背景，确保 Window 背景完整透出
    val decorView = window?.decorView as? ViewGroup
    decorView?.let { root ->
        val viewIds = intArrayOf(
            androidx.appcompat.R.id.parentPanel,
            androidx.appcompat.R.id.topPanel,
            androidx.appcompat.R.id.customPanel,
            androidx.appcompat.R.id.buttonPanel,
            androidx.appcompat.R.id.title_template,
            androidx.appcompat.R.id.alertTitle,
            android.R.id.custom
        )
        for (id in viewIds) {
            root.findViewById<View>(id)?.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    // 统一按钮颜色
    val colorStateList = Selector.colorBuild()
        .setDefaultColor(ThemeStore.accentColor(context))
        .setPressedColor(ColorUtils.darkenColor(ThemeStore.accentColor(context)))
        .create()
    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(colorStateList)
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(colorStateList)
    getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(colorStateList)

    // 统一文本颜色
    findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(
        ThemeStore.textColorPrimary(
            context
        )
    )
    findViewById<TextView>(android.R.id.message)?.setTextColor(ThemeStore.textColorSecondary(context))

    window?.decorView?.post {
        listView?.forEach {
            it.applyTint(context.accentColor)
        }
    }
    return this
}

fun AlertDialog.requestInputMethod() {
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
}

fun android.view.Window.setupAsBottomDialog(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setBackgroundDrawableResource(R.color.background)
    decorView.setPadding(0, 0, 0, 0)
    val attr = attributes
    attr.dimAmount = 0.0f
    attr.gravity = Gravity.BOTTOM
    attributes = attr
    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
}