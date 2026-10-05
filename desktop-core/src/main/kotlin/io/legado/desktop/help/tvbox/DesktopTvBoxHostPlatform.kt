package io.legado.desktop.help.tvbox

import android.content.Context
import io.legado.app.help.tvbox.TvBoxPlatform
import io.legado.desktop.desktopAppClassLoader
import java.io.File
import java.net.URLClassLoader

/**
 * [TvBoxPlatform] 的桌面 JVM 实现 (DesktopCore.registerDesktopTvBoxProviders 注册):
 * - [appContext] 为 data jvmMain 的 android.content.Context stub 实例 (jar 契约里的
 *   Init.set / spider.init 入参; getSharedPreferences 为内存态, getCacheDir/getFilesDir
 *   归一到 AppFilesDirs) —— jar 内未用到 Context 的 spider 全链可用;
 * - [readHostAsset] 读 classpath 资源 (data/src/jvmMain/resources/tvbox/ 的 JS 引导脚本,
 *   与 Android assets 同路径布局);
 * - [newJarClassLoader] 走 URLClassLoader (TVBox jar 无需 dex2jar, 直接加载; 父加载器为
 *   应用类加载器 —— jar 内引用的壳类 com.github.catvod.* 由宿主 classpath 提供)。
 *
 * Android 端对应物为 app 模块 AndroidTvBoxHostPlatform (DexClassLoader + assets)。
 */
object DesktopTvBoxHostPlatform : TvBoxPlatform {

    override val appContext: Context = Context()

    override fun readHostAsset(path: String): String? =
        desktopAppClassLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    override fun newJarClassLoader(jarFile: File): ClassLoader =
        URLClassLoader(arrayOf(jarFile.toURI().toURL()), desktopAppClassLoader)
}
