package io.legado.app.lib.webdav

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Server.WebDavConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * WebDav 认证 jvmAndAndroidMain actual 实现。
 *
 * 详见 commonMain/kotlin/io/legado/app/lib/webdav/Authorization.kt expect 注释。
 *
 * - 原逻辑零变化: okhttp3.Credentials.basic + StandardCharsets.ISO_8859_1
 * - `charset` 从原 primary constructor 第三参数下沉为 private 成员
 *   (commonMain expect 不暴露 Charset 类型, 与项目 PlatformEncoding.kt 约定一致)
 * - `serverDao.get` 现已 suspend (Room KMP 强制), 构造函数不能 suspend, 用 runBlocking 包裹
 */
actual class Authorization actual constructor(
    username: String,
    password: String
) {

    actual val username: String = username
    actual val password: String = password

    private val charset: Charset = StandardCharsets.ISO_8859_1

    actual var name: String = "Authorization"
        private set

    actual var data: String = Credentials.basic(username, password, charset)
        private set

    override fun toString(): String {
        return "$username:$password"
    }

    actual constructor(serverID: Long) : this(
        // serverDao.get 现已 suspend (Room KMP 强制), 构造函数不能 suspend, 用 runBlocking 同步等待
        runBlocking { AppDbProviders.get().serverDao.get(serverID) }?.getWebDavConfig()
            ?: throw WebDavException("Unexpected WebDav Authorization")
    )

    actual constructor(webDavConfig: WebDavConfig) : this(webDavConfig.username, webDavConfig.password)

}
