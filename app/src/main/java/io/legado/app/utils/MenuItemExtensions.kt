package io.legado.app.utils

import android.view.MenuItem
import android.widget.ImageButton
import androidx.annotation.DrawableRes

fun MenuItem.setIconCompat(@DrawableRes iconRes: Int) {
    setIcon(iconRes)
    (actionView as? ImageButton)?.setImageDrawable(icon)
}
