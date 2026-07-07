package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseBottomDialogFragment
import io.legado.app.databinding.DialogAutoReadBinding
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.BaseReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Locale


class AutoReadDialog : BaseBottomDialogFragment(R.layout.dialog_auto_read) {

    override val dismissWhenOtherBottomDialogShowing = true

    private val binding by viewBinding(DialogAutoReadBinding::bind)
    private val callBack: CallBack? get() = activity as? CallBack

    override fun onBottomDialogCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        val theme = createReadMenuTheme(requireContext())
        root.applyMenuTheme(theme)
        tvReadSpeedTitle.applyMenuThemeTextColor(theme)
        tvReadSpeed.applyMenuThemeTextColor(theme)
        tvCatalog.applyMenuThemeTextColor(theme)
        tvCatalog.applyMenuThemeCompoundDrawableTint(theme)
        tvMainMenu.applyMenuThemeTextColor(theme)
        tvMainMenu.applyMenuThemeCompoundDrawableTint(theme)
        tvAutoPageStop.applyMenuThemeTextColor(theme)
        tvAutoPageStop.applyMenuThemeCompoundDrawableTint(theme)
        tvSetting.applyMenuThemeTextColor(theme)
        tvSetting.applyMenuThemeCompoundDrawableTint(theme)
        initOnChange()
        initData()
        initEvent()
    }

    private fun initData() {
        val speed = if (ReadBookConfig.autoReadSpeed < 1) 1 else ReadBookConfig.autoReadSpeed
        binding.tvReadSpeed.text = String.format(Locale.ROOT, "%ds", speed)
        binding.seekAutoRead.progress = speed
    }

    private fun initOnChange() {
        binding.seekAutoRead.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val speed = if (progress < 1) 1 else progress
                binding.tvReadSpeed.text = String.format(Locale.ROOT, "%ds", speed)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                ReadBookConfig.autoReadSpeed =
                    if (binding.seekAutoRead.progress < 1) 1 else binding.seekAutoRead.progress
                upTtsSpeechRate()
            }
        })
    }

    private fun initEvent() {
        binding.tvMainMenu.setOnClickListener {
            callBack?.showMenuBar()
            dismissAllowingStateLoss()
        }
        binding.tvSetting.setOnClickListener {
            (activity as BaseReadBookActivity).showPageAnimConfig {
                (activity as ReadBookActivity).upPageAnim()
                ReadBook.loadContent(false)
            }
        }
        binding.tvCatalog.setOnClickListener { callBack?.openChapterList() }
        binding.tvAutoPageStop.setOnClickListener {
            callBack?.autoPageStop()
            binding.tvAutoPageStop.post {
                dismissAllowingStateLoss()
            }
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun autoPageStop()
    }
}