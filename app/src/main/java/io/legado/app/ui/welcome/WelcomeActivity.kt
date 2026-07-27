package io.legado.app.ui.welcome

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.windowSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动闪屏（迁 activity_welcome → Compose，静态零状态组合保持超轻量）。
 * 跳转逻辑/全屏 flag/欢迎图背景原样；文字块与书本图标照原 XML 位置（vertical bias 0.4 / 底距 80dp）。
 */
open class WelcomeActivity : BaseComposeActivity() {

    @Composable
    override fun Content() {
        val accent = AppTheme.colors.accent
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(0.4f))
            if (AppConfig.welcomeShowText) {
                Row {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .width(6.dp)
                                .fillMaxHeight()
                                .background(accent)
                        )
                        // ems=1 竖排「阅读」49sp
                        Text(
                            text = "阅\n读",
                            color = accent,
                            fontSize = 49.sp,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        text = "享\n受\n美\n好\n时\n光",
                        color = accent,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 60.dp),
                    )
                }
            }
            Box(Modifier.weight(0.6f))
            if (AppConfig.welcomeShowIcon) {
                Icon(
                    painter = painterResource(R.drawable.icon_read_book),
                    contentDescription = stringResource(R.string.welcome),
                    tint = accent,
                    modifier = Modifier.size(120.dp),
                )
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // 避免从桌面启动程序后，会重新实例化入口类的activity
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            finish()
        } else if (!AppConfig.enableWelcome) {
            startMainActivity()
        } else {
            val delayMs = AppConfig.welcomeShowTime.toLong()
            if (delayMs > 0) {
                lifecycleScope.launch {
                    delay(delayMs)
                    startMainActivity()
                }
            } else {
                startMainActivity()
            }
        }
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        kotlin.runCatching {
            val path = when (ThemeConfig.getTheme()) {
                Theme.Dark -> AppConfig.welcomeImageDark
                else -> AppConfig.welcomeImage
            } ?: return
            val size = windowManager.windowSize
            // 只传入宽度，保持图片原始宽高比
            BitmapUtils.decodeBitmap(path, size.widthPixels).let {
                it?.let { it1 -> window.decorView.background = it1.toDrawable(resources) }
                return
            }
        }
        super.upBackgroundImage()
    }

    private fun startMainActivity() {
        startActivity<MainActivity>()
        finish()
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
