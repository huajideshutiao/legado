package org.jsoup.internal

import io.legado.app.help.http.KmpHttpClient
import io.legado.app.help.http.KmpRequestBody
import okio.IOException
import org.jsoup.Connection

/**
 * jsoup 兼容层平台门面 (expect/actual)。
 *
 * 下沉 commonMain 后 JVM 专属 API (URLEncoder / java.util.zip / javax.net.ssl / multipart
 * 流式 body) 收敛到本文件各 expect 函数:
 * - jvmAndAndroidMain actual: 委托原 JDK/OkHttp 实现, 行为与原实现逐行等价 (零 diff);
 * - iOS: Ktor CIO 层已透明解压 / 系统信任库 / 无 unsafe SSL, 见各 actual 注释;
 * - 鸿蒙: @ohos.net.http 不透明解压, 用 okio 手动解压 (与 ios KmpHttpTypes 同款算法)。
 */
internal expect fun urlEncodeForm(value: String, charset: String): String

/**
 * 按 Content-Encoding 解压 body 字节 (encoding 已 lowercase)。
 * jvm: java.util.zip; ios: 原样返回 (KmpResponse 构造时已解压); ohos: okio 解压。
 */
internal expect fun decompressBody(bytes: ByteArray, contentEncoding: String?): ByteArray

/** 构造请求 client (jvm 复刻原 buildOkClient: 共享 client newBuilder 派生 + SSL 配置)。 */
internal expect fun buildPlatformClient(
    request: HttpConnectionRequest,
    shared: KmpHttpClient?
): KmpHttpClient

/** 构造 multipart/form-data body (jvm: okhttp3.MultipartBody; native: 手动拼字节)。 */
internal expect fun buildMultipartBody(
    data: Collection<Connection.KeyVal>,
    boundary: String
): KmpRequestBody

/** 宿主共享 client 回退 (jvm: null 保持裸建语义; native: OkHttpClientProviders 共享客户端)。 */
internal expect fun defaultSharedHttpClient(): KmpHttpClient?

/** IOException 包装 (jvm: UncheckedIOException, 与原实现一致; native: 原样抛出)。 */
internal expect fun uncheckedIoException(e: IOException): Throwable
