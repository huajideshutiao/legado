package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.fragment.app.commit
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.Theme
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppRoute
import io.legado.app.utils.startActivity
import splitties.init.appCtx

class AssociationActivity :
    BaseComposeActivity(
        theme = Theme.Transparent,
        imageBg = false
    ) {

    /** 透明壳 Activity：无可见内容，UI 由 headless Fragment 弹出的对话框承载 */
    @Composable
    override fun Content() = Unit

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        intent.data?.let { uri ->
            supportFragmentManager.commit {
                add(FileAssociationFragment(uri), "FileAssociationFragment")
            }
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
            AppNavigatorProviders.getOrNull()?.push(AppRoute.Search(key = text))
        }
        finish()
    }
}
