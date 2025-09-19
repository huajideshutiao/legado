package io.legado.app.ui.book.audio

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.SeekBar
import io.legado.app.databinding.PopupSeekBarBinding
import io.legado.app.model.AudioPlay
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import java.util.Locale

class SpeedSliderPopup(private val context: Context) :
  PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

  private val binding = PopupSeekBarBinding.inflate(LayoutInflater.from(context))

  init {
    contentView = binding.root

    isTouchable = true
    isOutsideTouchable = false
    isFocusable = true

    binding.seekBar.max = 30
    setProcessTextValue(binding.seekBar.progress)
    binding.seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {

      override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        setProcessTextValue(progress)
        if (fromUser) {
          AudioPlay.adjustSpeed(progress/10.0f)
        }
      }

    })
  }

  override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
    super.showAsDropDown(anchor, xoff, yoff, gravity)
    binding.seekBar.progress = (AudioPlayService.playSpeed*10).toInt()
  }

  override fun showAtLocation(parent: View?, gravity: Int, x: Int, y: Int) {
    super.showAtLocation(parent, gravity, x, y)
    binding.seekBar.progress = (AudioPlayService.playSpeed*10).toInt()
  }

  private fun setProcessTextValue(process: Int) {
    binding.tvSeekValue.text = String.format(Locale.ROOT, "%.1fX", process/10.0f)
  }

}