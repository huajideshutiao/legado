@file:JvmName("BackupAESAndroid")

package io.legado.app.help.storage

import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.PasswordProvider
import io.legado.app.help.config.PasswordProviders

/**
 * 安卓宿主启动早期注册 [PasswordProvider]。
 *
 * BackupAES 主类已下沉 shared jvmAndAndroidMain, 无参构造经 [PasswordProviders]
 * 反向获取 `LocalConfig.password` (SharedPreferences)。调用时机: App.onCreate,
 * 在 Backup/Restore 任何 `BackupAES()` 无参构造之前。
 *
 * 模式参考 `registerAndroidWebBookProviders` / `registerAndroidAppStringProvider`。
 */
fun registerAndroidPasswordProvider() {
    PasswordProviders.register(object : PasswordProvider {
        override fun password(): String? = LocalConfig.password
    })
}
