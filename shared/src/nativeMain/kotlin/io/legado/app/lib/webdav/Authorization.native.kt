package io.legado.app.lib.webdav

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Server.WebDavConfig
import io.legado.app.utils.textCharsetCodec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.runBlocking

/**
 * WebDav 认证 nativeMain actual 实现。
 *
 * 由 iosMain / ohosMain 共用 (nativeMain 中间源集下沉, 原 iosMain/ohosMain actual 完全一致,
 * 仅注释语境差异, 统一为 nativeMain)。
 *
 * 详见 commonMain/kotlin/io/legado/app/lib/webdav/Authorization.kt expect 注释。
 *
 * - 用 [Base64] (kotlin.io.encoding 标准库, KMP 可用) 实现 Basic 认证
 * - 与 jvmAndAndroidMain 完全一致: okhttp3.Credentials.basic(username, password, ISO_8859_1)
 *   ↔ 本端 [textCharsetCodec]("ISO-8859-1").encode("$username:$password") 后 Base64
 *   (Latin1Codec 与 JVM REPLACE 模式一致: 不可映射字符替换为 '?')
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

    // Basic 认证头: "Basic <base64(ISO_8859_1(username:password))>", 与 jvm 端 ISO_8859_1 字节一致
    // (Kotlin/Native 无 Charset 类型, 复用 TextCharsetCodec 的 Latin1Codec: 逐字符取低 8 位, 超范围替换 '?')
    actual var data: String = "Basic " + Base64.encode(
        textCharsetCodec("ISO-8859-1").encode("$username:$password")
    )
        private set

    actual constructor(serverID: Long) : this(
        // serverDao.get 现已 suspend (Room KMP 强制), 构造函数不能 suspend, 用 runBlocking 同步等待
        runBlocking { AppDbProviders.get().serverDao.get(serverID) }?.getWebDavConfig()
            ?: throw WebDavException("Unexpected WebDav Authorization")
    )

    actual constructor(webDavConfig: WebDavConfig) : this(webDavConfig.username, webDavConfig.password)

    override fun toString(): String {
        return "$username:$password"
    }
}
