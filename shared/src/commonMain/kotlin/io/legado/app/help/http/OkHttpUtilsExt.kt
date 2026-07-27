package io.legado.app.help.http

/**
 * OkHttp 工具扩展 expect 声明 (commonMain)。
 *
 * 原实现留 jvmAndAndroidMain (依赖 java.nio.charset.Charset / java.io.File / ZipInputStream 等),
 * AnalyzeUrlCore 下沉 commonMain 后需调用 [newCallStrResponse] 和 [postMultipart],
 * 故提升为 expect/actual; actual 实现见 jvmAndAndroidMain/OkHttpUtilsExt.jvmAndAndroid.kt。
 *
 * - [newCallStrResponse]: 依赖 [text] (Charset), 故 text 也提升为 expect
 * - [postMultipart]: 依赖 java.io.File + asRequestBody, 整体留 actual
 * - [decompressed]: 依赖 ZipInputStream + RealResponseBody, 整体留 actual (消费方在 jvmAndAndroidMain/app)
 *
 * KP4 OkHttp 跨平台修复: 原签名引用 okhttp3.OkHttpClient / okhttp3.Request / okhttp3.ResponseBody,
 * 但 OkHttp 5.3.2 不发布 iosArm64/linuxArm64 变体, iOS/鸿蒙 target 编译会失败。
 * 现改用 [KmpHttpClient] / [KmpRequestBuilder] / [KmpResponseBody] 跨平台抽象:
 * - jvmAndAndroidMain actual 经 typealias 等价 okhttp3.*, 行为与原实现完全一致 (零 diff, 无 cast);
 * - iOS/鸿蒙 actual 抛 UnsupportedOperationException (OkHttp 在这些平台不可用, 永不执行)。
 *
 * 注: iOS/鸿蒙端 AnalyzeUrlCore 的实际运行需后续重构为 HttpClient 抽象 (已有 Ktor 实现),
 * 不在本次 KP4 修复范围内。
 */
expect suspend fun KmpHttpClient.newCallStrResponse(
    retry: Int = 0,
    builder: KmpRequestBuilder.() -> Unit
): StrResponse

expect fun KmpResponseBody.text(encode: String? = null): String

expect fun KmpResponseBody.decompressed(): KmpResponseBody

expect fun KmpRequestBuilder.postMultipart(type: String?, form: Map<String, Any>)
