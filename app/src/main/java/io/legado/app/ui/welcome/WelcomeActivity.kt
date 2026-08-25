package io.legado.app.ui.welcome

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.Image
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
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.resolveImagePath
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.bakedImagePath
import io.legado.app.model.ensureBakedImage
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.edgeToEdge
import io.legado.app.utils.realScreenSize
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动闪屏（迁 activity_welcome → Compose，静态零状态组合保持超轻量）。
 * 跳转逻辑/全屏 flag/欢迎图背景原样；文字块与书本图标照原 XML 位置（vertical bias 0.4 / 底距 80dp）。
 */
open class WelcomeActivity : BaseComposeActivity() {

    /** 欢迎图背景（解码结果交 Compose 层绘制），null 表示未设置自定义欢迎图。 */
    private val bgImageState = mutableStateOf<ImageBitmap?>(null)

    @Composable
    override fun Content() {
        val accent = AppTheme.colors.accent
        // 白天/夜间分别使用各自的开关 (还原原版行为)
        val isDark = ThemeConfig.getTheme() == Theme.Dark
        val showText = if (isDark) AppConfig.welcomeShowTextDark else AppConfig.welcomeShowText
        val showIcon = if (isDark) AppConfig.welcomeShowIconDark else AppConfig.welcomeShowIcon
        Box(Modifier.fillMaxSize()) {
            bgImageState.value?.let { bg ->
                // 有意偏离原版：原版拉伸铺满会变形，改为中心裁切保持宽高比
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(0.4f))
                if (showText) {
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
                if (showIcon) {
                    Icon(
                        painter = painterResource(R.drawable.icon_read_book),
                        contentDescription = rememberString("welcome"),
                        tint = accent,
                        modifier = Modifier.size(120.dp),
                    )
                }
                Spacer(Modifier.height(80.dp))
            }
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
        edgeToEdge()
        setStatusBarColorAuto(backgroundColor, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        kotlin.runCatching {
            // pref 存原图裸文件名引用 (<字节数>.<ext>), 解析为绝对路径 (旧数据绝对路径兼容)
            val path = resolveImagePath(when (ThemeConfig.getTheme()) {
                Theme.Dark -> AppConfig.welcomeImageDark
                else -> AppConfig.welcomeImage
            }) ?: return
            val screen = windowManager.realScreenSize()
            val bakedPath = bakedImagePath(path)
            if (FileUtils.exist(bakedPath)) {
                // 常态: 产物在, 直接采样解码 (与原实现同为主线程同步, 零行为差)
                BitmapUtils.decodeBitmap(bakedPath, screen.x).let {
                    it?.let { bmp -> bgImageState.value = bmp.asImageBitmap() }
                    return
                }
            } else {
                // 冷路径 (缓存被清/旧数据首启): IO 现场重烘焙后回主线程设图, 不卡启动
                lifecycleScope.launch(Dispatchers.IO) {
                    val baked = ensureBakedImage(path, screen.x, screen.y) ?: path
                    val bmp = BitmapUtils.decodeBitmap(baked, screen.x)
                    withContext(Dispatchers.Main) {
                        bmp?.let { bgImageState.value = it.asImageBitmap() }
                    }
                }
                return
            }
        }
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
