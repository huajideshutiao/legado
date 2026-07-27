package io.legado.desktop.help.config

import io.legado.app.help.config.PasswordProvider
import io.legado.app.help.config.PasswordProviders
import io.legado.app.help.config.PreferenceProviders

/**
 * 桌面端 [PasswordProvider] 实现: 从 [PreferenceProviders] 读 "password" key。
 *
 * app 端 [io.legado.app.help.config.LocalConfig.password] 是 SharedPreferences-backed
 * 单例 (依赖 appCtx.getSharedPreferences("local", MODE_PRIVATE)), 桌面端无 LocalConfig,
 * 改为从已注册的 [PreferenceProviders] (java.util.prefs.Preferences) 读同名 key。
 *
 * 与 app 端语义对齐:
 * - 未设置密码时返回 null (LocalConfig.password getString 默认 null)
 * - 设置密码后持久化 (桌面端落 ~/.java/.userPrefs, 重启仍可读)
 *
 * 注册时机: desktop main 入口早期, 在 [registerDesktopConfig] 之后 (依赖 PreferenceProviders 已就绪),
 * 任何 BackupAES 无参构造之前。
 * 模式参考 app 端 `registerAndroidPasswordProvider` (BackupAES.kt)。
 */
private val desktopPasswordProvider = object : PasswordProvider {
    override fun password(): String? =
        PreferenceProviders.get().getString("password", "")
            .takeIf { it.isNotEmpty() }
}

/** 桌面端 main 入口早期注册一次。 */
fun registerDesktopPasswordProvider() {
    PasswordProviders.register(desktopPasswordProvider)
}
