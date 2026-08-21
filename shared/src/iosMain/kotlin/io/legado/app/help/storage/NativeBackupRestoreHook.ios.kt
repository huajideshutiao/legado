package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.IosPlatformCapabilities

/** iOS: 恢复 launcherIcon 偏好后经 setAlternateIconName 同步切换桌面图标。 */
internal actual fun applyRestoredLauncherIcon() {
    runCatching {
        val icon = PreferenceProviders.get().getString(PreferKey.launcherIcon, "ic_launcher")
        IosPlatformCapabilities.changeLauncherIcon(icon)
    }
}
