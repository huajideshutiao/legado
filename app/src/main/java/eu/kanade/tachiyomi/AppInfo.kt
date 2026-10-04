// Copyright The Keiyoushi Contributors. Apache-2.0.
package eu.kanade.tachiyomi

import android.os.Build
import io.legado.app.App

object AppInfo {

    fun getVersionCode(): Int = runCatching {
        val info = App.instance.packageManager.getPackageInfo(App.instance.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)

    fun getVersionName(): String = runCatching {
        App.instance.packageManager.getPackageInfo(App.instance.packageName, 0).versionName
    }.getOrNull().orEmpty()
}
