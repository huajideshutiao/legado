package io.legado.app.help.http

/**
 * DecompressInterceptor actual (iOS / 鸿蒙 stub)。
 *
 * 详见 commonMain/help/http/DecompressPlatform.kt expect 注释。
 *
 * KP4 OkHttp 跨平台修复:
 * 原实现引用 okhttp3.Response / okhttp3.ResponseBody / okio.BufferedSource 类型,
 * 但 OkHttp 5.3.2 不发布 iosArm64/linuxArm64 变体, iOS/鸿蒙 target 编译会失败。
 * 现改为 stub 实现: 接收者/参数 [Any] (与 commonMain expect 签名匹配), 返回默认值。
 *
 * - [promisesBody]: 返回 false (默认无 body, 与原 iOS/鸿蒙 stub 行为对齐)
 * - [decompressBody]: 返回 null (无解压, 与原 iOS/鸿蒙 stub 行为对齐)
 *
 * iOS/鸿蒙 target 上 DecompressInterceptor 本身就引用 okhttp3.Interceptor (commonMain),
 * 在 iOS/鸿蒙 target 编译失败, 这些函数永不执行, 返回默认值仅作为编译期占位。
 */
internal actual fun Any.promisesBody(): Boolean = false

internal actual fun decompressBody(body: Any, encoding: String?): Any? {
    // iOS/鸿蒙 stub: 无 OkHttp 实际 ResponseBody, 不支持解压, 返回 null
    return null
}
