package io.legado.app.utils

import android.content.Context
import io.legado.app.utils.TransType
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.compose.dialogs.alert

/**
 * ChineseUtils 的安卓泄漏面(留 app): 简繁选择对话框 + tc 缓存定位器注册。
 * 转换主体已下沉 shared(jvmAndAndroidMain)。
 */
fun ChineseUtils.showConverterSelector(
    context: Context,
    onChanged: ((Int) -> Unit)? = null
) {
    context.alert(titleResource = R.string.chinese_converter) {
        items(context.resources.getStringArray(R.array.chinese_mode).toList()) { _, i ->
            if (AppConfig.chineseConverterType != i) {
                AppConfig.chineseConverterType = i
                onChanged?.invoke(i)
                if (i > 0) {
                    when (i) {
                        1 -> loadDict(TransType.TRADITIONAL_TO_SIMPLE)
                        2 -> loadDict(TransType.SIMPLE_TO_TRADITIONAL)
                    }
                }
            }
        }
    }
}

/**
 * 宿主启动早期注册一次(App.onCreate 时机, 经 registerAndroidJsEngines 同位置调起)。
 * 缺失缓存即后台拉取——原 loadDict else 分支的下载副作用内置于此 lambda,
 * 保持 RemoteAssetsUtils/Coroutine 全留 app, shared 侧仅 getPath 定位。
 */
fun registerAndroidChineseUtils() {
    ChineseUtils.pathProvider = TcDictCachePathProvider { fileName ->
        RemoteAssetsUtils.getTcCachePath(fileName).also { file ->
            if (!file.exists() || file.length() == 0L) {
                Coroutine.async { RemoteAssetsUtils.downloadTcIfNeeded(fileName) }
            }
        }
    }
}
