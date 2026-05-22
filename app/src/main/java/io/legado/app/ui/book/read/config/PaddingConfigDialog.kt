package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadPaddingBinding
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.postEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding

class PaddingConfigDialog : BaseDialogFragment(R.layout.dialog_read_padding) {

    private val binding by viewBinding(DialogReadPaddingBinding::bind)

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = it.attributes
            attr.dimAmount = 0.0f
            it.attributes = attr
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    private fun initData() = binding.run {
        //正文
        dsbPaddingTop.progress = ReadBookConfig.paddingTop
        dsbPaddingBottom.progress = ReadBookConfig.paddingBottom
        dsbPaddingLeft.progress = ReadBookConfig.paddingLeft
        dsbPaddingRight.progress = ReadBookConfig.paddingRight
        //页眉
        dsbHeaderPaddingTop.progress = ReadBookConfig.headerPaddingTop
        dsbHeaderPaddingBottom.progress = ReadBookConfig.headerPaddingBottom
        dsbHeaderPaddingLeft.progress = ReadBookConfig.headerPaddingLeft
        dsbHeaderPaddingRight.progress = ReadBookConfig.headerPaddingRight
        //页脚
        dsbFooterPaddingTop.progress = ReadBookConfig.footerPaddingTop
        dsbFooterPaddingBottom.progress = ReadBookConfig.footerPaddingBottom
        dsbFooterPaddingLeft.progress = ReadBookConfig.footerPaddingLeft
        dsbFooterPaddingRight.progress = ReadBookConfig.footerPaddingRight
        cbShowTopLine.isChecked = ReadBookConfig.showHeaderLine
        cbShowBottomLine.isChecked = ReadBookConfig.showFooterLine
    }

    private fun initView() = binding.run {
        //正文
        bindSeekBarConfigs(
            listOf(
                SeekBarConfigBinding(
                    dsbPaddingTop,
                    { ReadBookConfig.paddingTop = it },
                    listOf(10, 5)
                ),
                SeekBarConfigBinding(
                    dsbPaddingBottom,
                    { ReadBookConfig.paddingBottom = it },
                    listOf(10, 5)
                ),
                SeekBarConfigBinding(
                    dsbPaddingLeft,
                    { ReadBookConfig.paddingLeft = it },
                    listOf(10, 5)
                ),
                SeekBarConfigBinding(
                    dsbPaddingRight,
                    { ReadBookConfig.paddingRight = it },
                    listOf(10, 5)
                ),
                //页眉
                SeekBarConfigBinding(
                    dsbHeaderPaddingTop,
                    { ReadBookConfig.headerPaddingTop = it },
                    listOf(2)
                ),
                SeekBarConfigBinding(
                    dsbHeaderPaddingBottom,
                    { ReadBookConfig.headerPaddingBottom = it },
                    listOf(2)
                ),
                SeekBarConfigBinding(
                    dsbHeaderPaddingLeft,
                    { ReadBookConfig.headerPaddingLeft = it },
                    listOf(2)
                ),
                SeekBarConfigBinding(
                    dsbHeaderPaddingRight,
                    { ReadBookConfig.headerPaddingRight = it },
                    listOf(2)
                ),
                //页脚
                SeekBarConfigBinding(
                    dsbFooterPaddingTop,
                    { ReadBookConfig.footerPaddingTop = it },
                    listOf(2)
                ),
                SeekBarConfigBinding(
                    dsbFooterPaddingBottom,
                    { ReadBookConfig.footerPaddingBottom = it },
                    listOf(2)
                ),
                SeekBarConfigBinding(
                    dsbFooterPaddingLeft,
                    { ReadBookConfig.footerPaddingLeft = it },
                    listOf(2)
                ),
                SeekBarConfigBinding(
                    dsbFooterPaddingRight,
                    { ReadBookConfig.footerPaddingRight = it },
                    listOf(2)
                )
            )
        )
        cbShowTopLine.onCheckedChangeListener = { _, isChecked ->
            ReadBookConfig.showHeaderLine = isChecked
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
        cbShowBottomLine.onCheckedChangeListener = { _, isChecked ->
            ReadBookConfig.showFooterLine = isChecked
            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
        }
    }

}
