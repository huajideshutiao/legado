package io.legado.app.ui.book.audio

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import io.legado.app.R
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.ui.widget.seekbar.ThemeSeekBar
import io.legado.app.utils.dpToPx

class SliderPopup(
    context: Context,
    private val max: Int,
    private val getProgress: () -> Int,
    private val onProgressChanged: (Int) -> Unit,
    private val formatText: (Int) -> String
) : PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    private val tvValue: TextView
    private val seekBar: ThemeSeekBar

    init {
        val dp8 = 8.dpToPx()
        val root = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.shape_card_view)
            setPadding(dp8, dp8, dp8, dp8)
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        tvValue = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(context.getColor(R.color.secondaryText))
        }
        seekBar = ThemeSeekBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            max = this@SliderPopup.max
            setOnSeekBarChangeListener(object : SeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    tvValue.text = formatText(progress)
                    if (fromUser) this@SliderPopup.onProgressChanged(progress)
                }
            })
        }
        root.addView(tvValue)
        root.addView(seekBar)
        contentView = root
        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true
    }

    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        super.showAsDropDown(anchor, xoff, yoff, gravity)
        seekBar.progress = getProgress()
    }

    override fun showAtLocation(parent: View?, gravity: Int, x: Int, y: Int) {
        super.showAtLocation(parent, gravity, x, y)
        seekBar.progress = getProgress()
    }
}
