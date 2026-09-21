package io.legado.app.help.config

import kotlin.concurrent.Volatile

/**
 * 备份密码 Provider 接口。
 *
 * BackupAES 主类下沉 shared jvmAndAndroidMain 后, 不能直接引用 app 端 [LocalConfig.password]
 * (SharedPreferences 依赖 Android Context)。宿主启动早期通过 [PasswordProviders.register]
 * 注入实现, 通常委托 `LocalConfig.password`。
 *
 * 模式参考 IntentDataProviders / UserAgentProviders。
 */
interface PasswordProvider {

    /** 返回本地备份密码, 无设置时返回 null (与 `LocalConfig.password` 默认行为一致)。 */
    fun password(): String?
}

/**
 * PasswordProvider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点 (BackupAES 无参构造) 用
 * `PasswordProviders.get()?.password() ?: ""` 替代
 * 原 `LocalConfig.password ?: ""`, 行为完全一致, 仅多一层 provider 间接。
 */
object PasswordProviders {

    @Volatile
    private var impl: PasswordProvider? = null

    /** 宿主启动早期注册一次 (任何 BackupAES 无参构造之前)。 */
    fun register(impl: PasswordProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册返回 null (等价 LocalConfig.password 默认 null 行为)。 */
    fun get(): PasswordProvider? = impl
}
