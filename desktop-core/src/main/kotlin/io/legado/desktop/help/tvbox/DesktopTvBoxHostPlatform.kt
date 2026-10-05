package io.legado.desktop.help.tvbox

import android.content.Context
import io.legado.app.help.tvbox.TvBoxPlatform
import io.legado.desktop.desktopAppClassLoader
import io.legado.desktop.help.dex.DexJarConverter
import java.io.File
import java.net.URLClassLoader

/**
 * [TvBoxPlatform] 的桌面 JVM 实现 (DesktopCore.registerDesktopTvBoxProviders 注册):
 * - [appContext] 为 data jvmMain 的 android.content.Context stub 实例 (jar 契约里的
 *   Init.set / spider.init 入参; getSharedPreferences 为内存态, getCacheDir/getFilesDir
 *   归一到 AppFilesDirs) —— jar 内未用到 Context 的 spider 全链可用;
 * - [readHostAsset] 读 classpath 资源 (data/src/jvmMain/resources/tvbox/ 的 JS 引导脚本,
 *   与 Android assets 同路径布局);
 * - [newJarClassLoader] 先经 DexJarConverter 把 dex 容器 (TVBox jar 生态标准形态) 转成 JVM
 *   jar (含 CtorSiteFixer 接收者构造还原), 再走 URLClassLoader; 父加载器为应用类加载器 ——
 *   jar 内引用的壳类 com.github.catvod.* 由宿主 classpath 提供。assets 带自身解密 so 的
 *   加密壳 (如 fan.txt) 转换能过但运行期依赖 dalvik/DexClassLoader 与 bionic so, 桌面端
 *   不可用, 在 spider 调用点如实报错。
 *
 * Android 端对应物为 app 模块 AndroidTvBoxHostPlatform (DexClassLoader + assets)。
 */
object DesktopTvBoxHostPlatform : TvBoxPlatform {

    override val appContext: Context = Context()

    override fun readHostAsset(path: String): String? =
        desktopAppClassLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    override fun newJarClassLoader(jarFile: File): ClassLoader =
        URLClassLoader(arrayOf(DexJarConverter.jvmJarFor(jarFile).toURI().toURL()), desktopAppClassLoader)
}
