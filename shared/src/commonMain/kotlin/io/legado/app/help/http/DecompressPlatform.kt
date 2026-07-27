package io.legado.app.help.http

/**
 * DecompressInterceptor 跨平台门面 (expect/actual)。
 *
 * 两个 expect 函数均包装 JVM 专属依赖, 无法在 commonMain 直接引用:
 *
 * - [promisesBody]: okhttp3.internal.http.promisesBody (internal API, 不应在 commonMain 引用)
 * - [decompressBody]: java.util.zip.GZIPInputStream / Inflater / InflaterInputStream (JVM 专属)
 *
 * 仅 [DecompressInterceptor] 内部调用, 不对外暴露。
 *
 * KP4 OkHttp 跨平台修复: 原签名引用 okhttp3.Response / okhttp3.ResponseBody / okio.BufferedSource,
 * 但 OkHttp 5.3.2 不发布 iosArm64/linuxArm64 变体, iOS/鸿蒙 target 编译会失败。
 * 现改用 [Any] 类型擦除:
 * - jvmAndAndroidMain actual 内部 cast 回 okhttp3.* / okio.* 类型, 行为与原实现完全一致;
 * - iOS/鸿蒙 actual 返回默认值 (OkHttp 在这些平台不可用, 永不执行)。
 */
internal expect fun Any.promisesBody(): Boolean

/**
 * 按 Content-Encoding 解压 [body] 字节流。
 *
 * @param body 待解压的响应体 (jvmAndAndroid: okhttp3.ResponseBody; iOS/鸿蒙: 永不调用)
 * @param encoding Content-Encoding 值 (已 lowercase, 可能为 null): "gzip" / "deflate"
 * @return 解压后的源 (jvmAndAndroid: okio.BufferedSource; iOS/鸿蒙: null); encoding 为 null 或不匹配返回 null
 */
internal expect fun decompressBody(body: Any, encoding: String?): Any?
