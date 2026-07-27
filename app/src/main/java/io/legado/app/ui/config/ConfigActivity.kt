package io.legado.app.ui.config

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

/**
 * 设置宿主 Activity：按 intent extra "configTag" 直接路由到对应 Compose 页面。
 * 原 Fragment 壳的残留逻辑（launcher/菜单/prefs 监听）上浮到各 ConfigHost 状态类。
 * 启动契约不变：callers 仍 putExtra("configTag", ConfigTag.XXX)。
 */
class ConfigActivity : BaseComposeActivity() {

    val viewModel by viewModels<ConfigViewModel>()

    private var host: ConfigHost? = null

    private fun obtainHost(): ConfigHost? = host ?: when (intent.getStringExtra("configTag")) {
        ConfigTag.OTHER_CONFIG -> OtherConfigHost(this)
        ConfigTag.THEME_CONFIG -> ThemeConfigHost(this)
        ConfigTag.BACKUP_CONFIG -> BackupConfigHost(this)
        ConfigTag.COVER_CONFIG -> CoverConfigHost(this)
        ConfigTag.WELCOME_CONFIG -> WelcomeConfigHost(this)
        else -> null
    }?.also { host = it }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (obtainHost() == null) finish()
    }

    @Composable
    override fun Content() {
        obtainHost()?.Content()
    }

    override fun onDestroy() {
        super.onDestroy()
        host?.onDestroy()
    }
}

/** 单个设置页宿主：持有状态/launcher/prefs 监听，[Content] 输出整页（含标题栏）。 */
abstract class ConfigHost(protected val activity: ConfigActivity) {

    @Composable
    abstract fun Content()

    open fun onDestroy() {}
}
