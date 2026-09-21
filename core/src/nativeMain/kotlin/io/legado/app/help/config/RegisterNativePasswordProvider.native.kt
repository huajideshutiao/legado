package io.legado.app.help.config

/**
 * native (iOS/鸿蒙) [PasswordProvider]: 从 [PreferenceProviders] 读 "password" key
 * (与 app 端 LocalConfig.password / desktop registerDesktopPasswordProvider 同名 key)。
 *
 * 未注册时 BackupAES 无参构造恒用空密码派生 key, 加密备份跨端不兼容。
 * 语义对齐: 未设置密码时返回 null (LocalConfig.password getString 默认 null)。
 */
private val nativePasswordProvider = object : PasswordProvider {
    override fun password(): String? =
        PreferenceProviders.get().getString("password", "")
            .takeIf { it.isNotEmpty() }
}

/** iOS/鸿蒙宿主启动早期注册一次 (PreferenceProviders 之后、任何 BackupAES 无参构造之前)。 */
fun registerNativePasswordProvider() {
    PasswordProviders.register(nativePasswordProvider)
}
