package io.legado.app.help.glide.progress

/**
 * 跨平台图片加载进度 provider 容器。
 *
 * 通过 expect/actual 在各平台注入 [ImageProgressListener] 实例，
 * commonMain 代码用 [progressListener] 调用图片加载进度能力，
 * 而不直接依赖具体平台实现（Glide / OkHttp 等）。
 *
 * - jvmAndAndroidMain：actual 实现返回 [ProgressManager] 单例
 * - iOS / HarmonyOS：各自 actual 实现返回本平台的 [ImageProgressListener] 实例
 */
expect object ImageProgressProviders {

    /** 由各平台 actual 注入的图片加载进度监听（管理）实例 */
    val progressListener: ImageProgressListener
}
