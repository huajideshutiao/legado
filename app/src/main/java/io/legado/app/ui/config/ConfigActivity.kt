package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.ui.widget.TitleBar

class ConfigActivity : VMBaseActivity<ConfigActivity.Binding, ConfigViewModel>() {

    companion object {
        private val CONFIG_FRAME_ID = View.generateViewId()
    }

    class Binding(private val root: View) : ViewBinding {
        override fun getRoot() = root
        lateinit var titleBar: TitleBar
        lateinit var configFrameLayout: LinearLayout
    }

    override val viewModel by viewModels<ConfigViewModel>()

    public override val binding by lazy {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        val titleBar = TitleBar(ctx).apply {
            id = R.id.title_bar
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(titleBar)
        val configFrameLayout = LinearLayout(ctx).apply {
            id = CONFIG_FRAME_ID
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                height = 0
                weight = 1f
            }
            orientation = LinearLayout.VERTICAL
        }
        root.addView(configFrameLayout)
        Binding(root).also {
            it.titleBar = titleBar
            it.configFrameLayout = configFrameLayout
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        when (val configTag = intent.getStringExtra("configTag")) {
            ConfigTag.OTHER_CONFIG -> replaceFragment<OtherConfigFragment>(configTag)
            ConfigTag.THEME_CONFIG -> replaceFragment<ThemeConfigFragment>(configTag)
            ConfigTag.BACKUP_CONFIG -> replaceFragment<BackupConfigFragment>(configTag)
            ConfigTag.COVER_CONFIG -> replaceFragment<CoverConfigFragment>(configTag)
            ConfigTag.WELCOME_CONFIG -> replaceFragment<WelcomeConfigFragment>(configTag)
            else -> finish()
        }
    }

    override fun setTitle(resId: Int) {
        super.setTitle(resId)
        binding.titleBar.setTitle(resId)
    }

    inline fun <reified T : Fragment> replaceFragment(configTag: String) {
        intent.putExtra("configTag", configTag)
        @Suppress("DEPRECATION")
        val configFragment = supportFragmentManager.findFragmentByTag(configTag)
            ?: T::class.java.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(binding.configFrameLayout.id, configFragment, configTag)
            .commit()
    }
}
