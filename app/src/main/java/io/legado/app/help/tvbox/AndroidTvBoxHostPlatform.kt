package io.legado.app.help.tvbox

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

/**
 * [TvBoxPlatform] 的安卓实现 (App.onCreate 注册, 供 data 层 TVBox 宿主编排取用):
 * - [appContext] 为应用上下文 (jar 契约里的 Init.set / spider.init 入参);
 * - [newJarClassLoader] 走 DexClassLoader (jar 即时 dex 化, odex 落 cacheDir/tvbox/odex,
 *   父加载器为宿主 —— jar 内引用的壳类 com.github.catvod.* 由宿主 classpath 提供)。
 *
 * 桌面端对应物为 desktop-core 的 DesktopTvBoxHostPlatform (URLClassLoader)。
 */
class AndroidTvBoxHostPlatform(context: Context) : TvBoxPlatform {

    override val appContext: Context = context.applicationContext

    override fun newJarClassLoader(jarFile: File): ClassLoader {
        // odex 目录 (FongMi JarLoader 同款): DexClassLoader 的优化输出落 cacheDir/tvbox/odex
        val odex = File(appContext.cacheDir, "tvbox/odex").apply { mkdirs() }
        return DexClassLoader(
            jarFile.absolutePath,
            odex.absolutePath,
            odex.absolutePath,
            appContext.classLoader,
        )
    }
}
