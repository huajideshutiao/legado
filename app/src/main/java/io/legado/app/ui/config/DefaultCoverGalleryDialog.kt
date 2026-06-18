package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.model.BookCover
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 默认封面图集管理 -- 网格列表展示已选封面,末尾 + 按钮添加。
 * 复用 dialog_recycler_view,arguments 传 isNight 切换日/夜偏好。
 */
class DefaultCoverGalleryDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    DefaultCoverAdapter.CallBack {

    override val isFullHeight: Boolean = true

    constructor(isNight: Boolean) : this() {
        arguments = Bundle().apply { putBoolean("isNight", isNight) }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { DefaultCoverAdapter(requireContext(), this) }

    private val prefKey: String
        get() = if (arguments?.getBoolean("isNight") == true) {
            PreferKey.defaultCoverDark
        } else {
            PreferKey.defaultCover
        }

    private val pickImage by lazy {
        registerHandleFile { result ->
            val uri = result.uri ?: return@registerHandleFile
            var fileName = "cover"
            var bytes: ByteArray? = null
            runCatching {
                readUri(uri) { fileDoc, inputStream ->
                    fileName = fileDoc.name
                    bytes = inputStream.readBytes()
                }
            }
            val safeBytes = bytes ?: run {
                appCtx.toastOnUi(R.string.error_read_file)
                return@registerHandleFile
            }
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        BookCover.addDefaultCover(prefKey, safeBytes, fileName)
                    }
                }.onFailure { appCtx.toastOnUi(it.localizedMessage) }
                refresh()
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setTitle(R.string.default_cover)
        binding.toolBar.subtitle = getString(
            if (prefKey == PreferKey.defaultCoverDark) R.string.night else R.string.day
        )
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter
        refresh()
    }

    private fun refresh() {
        adapter.submit(BookCover.listDefaultCovers(prefKey))
    }

    override fun onCoverClick(entry: BookCover.DefaultCoverEntry) {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        BookCover.removeDefaultCover(prefKey, entry.id)
                    }
                    refresh()
                }
            }
            noButton()
        }
    }

    override fun onAddClick() {
        pickImage.launch { mode = HandleFileContract.IMAGE }
    }

}
