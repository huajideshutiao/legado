package io.legado.app.lib.prefs

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.applyTint

class EditTextPreference(context: Context, attrs: AttributeSet) :
    androidx.preference.EditTextPreference(context, attrs) {

    private val isBottomBackground: Boolean
    private var mOnBindEditTextListener: OnBindEditTextListener? = null
    private val onBindEditTextListener = OnBindEditTextListener { editText ->
        editText.applyTint(context.accentColor)
        mOnBindEditTextListener?.onBindEditText(editText)
    }

    init {
        layoutResource = R.layout.view_preference
        isBottomBackground = attrs.parseIsBottomBackground(context)
        super.setOnBindEditTextListener(onBindEditTextListener)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        Preference.bindView<TextView>(
            context, holder, icon, title, summary,
            isBottomBackground = isBottomBackground
        )
        super.onBindViewHolder(holder)
    }

    override fun setOnBindEditTextListener(onBindEditTextListener: OnBindEditTextListener?) {
        mOnBindEditTextListener = onBindEditTextListener
    }

}
