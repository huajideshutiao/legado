package io.legado.app.ui.widget.text

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore

class TextInputLayout(context: Context, attrs: AttributeSet?) : TextInputLayout(context, attrs) {

    init {
        if (!isInEditMode) {
            val accent = ThemeStore.accentColor
            defaultHintTextColor =
                Selector.colorBuild().setDefaultColor(accent).create()
            boxStrokeColor = accent
            hintTextColor = Selector.colorBuild().setDefaultColor(accent).create()
        }
    }

}
