package io.legado.app.help.glide.progress

/**
 * [ImageProgressProviders] 的 jvmAndAndroidMain actual 实现。
 *
 * 将 [ProgressManager]（基于 Glide + OkHttp 拦截器）注入为 commonMain
 * 可见的 [ImageProgressListener] 实例，使 commonMain 代码无需依赖具体平台库
 * 即可调用图片加载进度能力。
 *
 * iOS / HarmonyOS 端应各自提供同名 actual object，返回本平台的实现。
 */
actual object ImageProgressProviders {

    actual val progressListener: ImageProgressListener = ProgressManager
}
