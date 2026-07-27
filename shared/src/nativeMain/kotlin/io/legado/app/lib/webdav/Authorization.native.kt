package io.legado.app.lib.webdav

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Server.WebDavConfig
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * WebDav 认证 nativeMain actual 实现。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉, 原 iosMain/ohosMain actual 完全一致,
 * 仅注释语境差异, 统一为 nativeMain)。
 *
 * 详见 commonMain/kotlin/io/legado/app/lib/webdav/Authorization.kt expect 注释。
 *
 * - 用 [Base64] (kotlin.io.encoding 标准库, KMP 可用) 实现 Basic 认证
 * - 与 jvmAndAndroidMain 的差异: 编码 charset 不同
 *   - jvmAndAndroidMain: okhttp3.Credentials.basic(username, password, StandardCharsets.ISO_8859_1)
 *   - nativeMain: "$username:$password".encodeToByteArray() (UTF-8)
 *   - ASCII 用户名/密码场景两者完全一致; 非 ASCII 场景可能与服务端期望的 ISO_8859_1 解码不一致
 *     (功能降级, 与 nativeMain commonMain 无 ISO_8859_1 charset 限制一致, 见 PlatformEncoding.kt)
 * - `Authorization(serverID)` 走 AppDbProviders (commonMain 已可用, iOS/鸿蒙端数据库驱动由宿主注册)
 *
 * 注: nativeMain 不依赖任何平台专属 API (iOS Foundation / 鸿蒙 napi), 纯 KMP 标准库实现。
 */
@OptIn(ExperimentalEncodingApi::class)
actual class Authorization actual constructor(
    actual val username: String,
    actual val password: String
) {

    actual var name: String = "Authorization"
        private set

    // Basic 认证头: "Basic <base64(username:password)>"
    // 注: UTF-8 编码, 与 jvmAndAndroidMain 的 ISO_8859_1 在 ASCII 场景一致; 非 ASCII 场景为已知降级
    actual var data: String = "Basic " + Base64.encode("$username:$password".encodeToByteArray())
        private set

    actual constructor(serverID: Long) : this(
        AppDbProviders.get().serverDao.get(serverID)?.getWebDavConfig()
            ?: throw WebDavException("Unexpected WebDav Authorization")
    )

    actual constructor(webDavConfig: WebDavConfig) : this(webDavConfig.username, webDavConfig.password)

    override fun toString(): String {
        return "$username:$password"
    }
}
