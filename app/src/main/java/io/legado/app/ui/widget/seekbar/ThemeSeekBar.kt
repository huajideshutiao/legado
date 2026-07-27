package io.legado.app.ui.widget.seekbar

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import io.legado.app.lib.theme.applyTheme

/**
 * @author Aidan Follestad (afollestad)
 */
class ThemeSeekBar(context: Context, attrs: AttributeSet? = null) :
    AppCompatSeekBar(context, attrs) {

    init {
        if (!isInEditMode) {
            applyTheme()
        }
    }
}
