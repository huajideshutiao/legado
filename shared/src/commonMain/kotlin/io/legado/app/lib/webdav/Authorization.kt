package io.legado.app.lib.webdav

import io.legado.app.data.entities.Server.WebDavConfig

/**
 * WebDav 认证 expect 声明。
 *
 * - jvmAndAndroidMain actual: 基于 okhttp3.Credentials.basic 的原实现, 行为零变化
 * - nativeMain actual (iosMain/ohosMain 共用): kotlin.io.encoding.Base64 实现
 *   (UTF-8 编码, 与 jvm 端 ISO_8859_1 在 ASCII 场景一致)
 *
 * **KMP 化说明**:
 * - 原 `data class Authorization` 改为 `expect class` (KMP 限制: expect 不能是 data),
 *   调用方未依赖 data class 的 equals/hashCode/copy, 行为不受影响。
 * - 原 primary constructor 第三参数 `charset: java.nio.charset.Charset` 移至 jvmAndAndroidMain
 *   actual 的 private 成员 (commonMain 不暴露 `Charset` 类型, 与项目 PlatformEncoding.kt
 *   "commonMain 无 kotlin.text.Charset" 约定一致)。原 3 参数构造函数无调用方, 移除安全。
 * - expect class primary constructor 参数不能用 `val`/`var` (KMP 限制:
 *   "Expected class constructor cannot have a property parameter"), 故 `username`/`password`
 *   在 body 中以 `val` 声明, actual class 用 `actual val` 在 primary constructor 实现之。
 */
expect class Authorization(
    username: String,
    password: String
) {

    val username: String

    val password: String

    /**
     * HTTP 认证头名称, 固定为 "Authorization"
     */
    var name: String
        private set

    /**
     * HTTP 认证头数据, 格式 "Basic <base64(username:password)>"
     */
    var data: String
        private set

    /**
     * 由 serverID 从数据库读取 WebDav 配置构造
     */
    constructor(serverID: Long)

    /**
     * 由 [WebDavConfig] 构造
     */
    constructor(webDavConfig: WebDavConfig)
}
