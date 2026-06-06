package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogMangaColorFilterBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.viewbindingdelegate.viewBinding

class MangaColorFilterDialog : BaseDialogFragment(R.layout.dialog_manga_color_filter) {
    private val binding by viewBinding(DialogMangaColorFilterBinding::bind)
    private val mConfig =
        GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
            ?: MangaColorFilterConfig()
    private val callback get() = activity as? Callback

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    private fun initData() {
        binding.run {
            dsbBrightness.progress = mConfig.l
            dsbContrast.progress = mConfig.ct + 50
            dsbR.progress = mConfig.r
            dsbG.progress = mConfig.g
            dsbB.progress = mConfig.b
            cbGray.isChecked = AppConfig.enableMangaGray
        }
    }

    private fun initView() {
        binding.run {
            dsbContrast.valueFormat = { "${it - 50}" }
            dsbBrightness.onChanged = {
                mConfig.l = it
                callback?.updateColorFilter(mConfig)
            }
            dsbContrast.onChanged = {
                mConfig.ct = it - 50
                callback?.updateColorFilter(mConfig)
            }
            dsbR.onChanged = {
                mConfig.r = it
                callback?.updateColorFilter(mConfig)
            }
            dsbG.onChanged = {
                mConfig.g = it
                callback?.updateColorFilter(mConfig)
            }
            dsbB.onChanged = {
                mConfig.b = it
                callback?.updateColorFilter(mConfig)
            }
            cbGray.setOnCheckedChangeListener { _, isChecked ->
                AppConfig.enableMangaGray = isChecked
                callback?.updateGray(isChecked)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaColorFilter = mConfig.toJson()
    }

    interface Callback {
        fun updateColorFilter(config: MangaColorFilterConfig)
        fun updateGray(enable: Boolean)
    }

}
