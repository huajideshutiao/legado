package io.legado.app.help.config

import android.content.Context
import io.legado.app.lib.theme.MonetColorExtract

/**
 * 安卓端 [ManualThemeColorChangeProvider]: 用户手动改色即拆掉"跟随壁纸"机关。
 *
 * 跟随壁纸时系统挂着壁纸颜色监听, 壁纸一变就重写日/夜八个颜色 pref; 手动改色不拆机关的话,
 * 用户刚定的颜色会在下一次壁纸变化时被拽回壁纸色 (对照 legadoT WallpaperSeed 的护栏)。
 */
private class AndroidManualThemeColorChangeProvider(
    private val context: Context,
) : ManualThemeColorChangeProvider {

    override fun onManualColorChange() {
        MonetColorExtract.abandonFollowIfActive(context)
    }
}

/**
 * 安卓宿主启动早期注册 [ManualThemeColorChangeProvider] (任何主题定制对话框之前)。
 *
 * 调用时机: App.onCreate, 与 [MonetColorExtract.restoreListenerIfNeeded] 同段 (跟随机制的
 * 初始化处); 持有 applicationContext, 不随 Activity 生命周期变化。
 */
fun registerAndroidManualThemeColorChangeProvider(context: Context) {
    ManualThemeColorChangeProviders.register(
        AndroidManualThemeColorChangeProvider(context.applicationContext)
    )
}
