package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import io.legado.app.base.BaseActivity
import io.legado.app.constant.Theme
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx

class AssociationActivity :
    BaseActivity<ActivityTranslucenceBinding>(
        theme = Theme.Transparent,
        imageBg = false
    ) {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        intent.data?.let {
            showDialogFragment(FileAssociationDialog(it))
        } ?: initIntent()
    }

    private fun initIntent() {
        val receivingType = "text/plain"
        when {
            intent.action == Intent.ACTION_SEND && intent.type == receivingType -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                    dispose(it)
                } ?: finish()
            }

            intent.action == Intent.ACTION_PROCESS_TEXT
                    && intent.type == receivingType -> {
                intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.let {
                    dispose(it)
                } ?: finish()
            }

            intent.getStringExtra("action") == "readAloud" -> {
                MediaButtonReceiver.readAloud(appCtx, false)
                finish()
            }

            else -> finish()
        }
    }

    private fun dispose(text: String) {
        if (text.isBlank()) {
            finish()
            return
        }
        val urls = text.split("\\s".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val result = StringBuilder()
        for (url in urls) {
            if (url.matches("http.+".toRegex()))
                result.append("\n").append(url.trim())
        }
        if (result.length > 1) {
            startActivity<MainActivity>()
        } else {
            SearchActivity.start(this, text)
        }
        finish()
    }
}
