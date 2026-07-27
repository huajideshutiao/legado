package io.legado.desktop.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.source.SourceVerificationHelpShared
import io.legado.app.help.source.VerificationUiProvider
import io.legado.app.help.source.VerificationUiProviders
import io.legado.app.help.ui.ToastProviders
import io.legado.app.utils.browseUrl
import okhttp3.Request
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * [VerificationUiProvider] 桌面端实现。
 *
 * app 端走 VerificationCodeDialog + WebViewActivity, 桌面端二者均无对应下沉实现:
 * - 图片验证码: 用 Swing 模态输入框 (内嵌验证码图片) 采集, 直接回填
 *   [SourceVerificationHelpShared.setResult], 等待方随即取到结果;
 * - 网页验证 (需回传网页源码, `saveResult == true`): 桌面端无内置 WebView 取不到源码,
 *   给明确文案后抛 [NoStackTraceException], 避免未注册时的 IllegalStateException 裸抛;
 * - 纯打开链接 (`saveResult != true`): 走系统默认浏览器, 与 app 端"仅打开"语义一致。
 */
object DesktopVerificationUiProvider : VerificationUiProvider {

    override fun showVerificationCodeDialog(url: String, source: BaseSource) {
        val icon = runCatching { loadCaptchaIcon(url) }.getOrNull()
        val message: Array<Any> = if (icon != null) {
            arrayOf(JLabel(icon), "请输入验证码")
        } else {
            arrayOf("无法加载验证码图片, 请手动打开:", url, "请输入验证码")
        }
        val input = runCatching {
            var answer: String? = null
            SwingUtilities.invokeAndWait {
                answer = JOptionPane.showInputDialog(
                    null,
                    message,
                    "${source.getTag()} 验证码",
                    JOptionPane.QUESTION_MESSAGE
                )
            }
            answer
        }.getOrNull()
        // 结果先落缓存: 调用方 registerWaitingThread 后的轮询会立即取到, 取消则回空串走"验证结果为空"
        SourceVerificationHelpShared.setResult(source.getKey(), input ?: "")
    }

    override fun startBrowser(
        source: BaseSource,
        url: String,
        title: String,
        saveResult: Boolean?,
        refetchAfterSuccess: Boolean?
    ) {
        if (saveResult == true) {
            val msg = "桌面端暂不支持该验证方式(需内置浏览器回传网页源码): $title"
            runCatching { ToastProviders.get().showToast(msg, long = true) }
            throw NoStackTraceException(msg)
        }
        browseUrl(url)
    }

    /** 用宿主共享 OkHttpClient 拉验证码图片, 继承 CookieJar/UA, 否则多数站点会下发新的验证码。 */
    private fun loadCaptchaIcon(url: String): ImageIcon? {
        val client = OkHttpClientProviders.get().okHttpClient
        val bytes = client.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) return null
            it.body.bytes()
        }
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return ImageIcon(image)
    }
}

/** 桌面端 main 入口注册一次, 任何书源验证流程之前。 */
fun registerDesktopVerificationUiProvider() {
    VerificationUiProviders.register(DesktopVerificationUiProvider)
}
