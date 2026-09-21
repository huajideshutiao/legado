package io.legado.app.utils

/**
 * MD5Utils 的 Native (iOS/ohos) actual 实现。
 *
 * 详见 commonMain/utils/MD5Utils.kt expect 注释。
 * 委托 commonMain 的 MD5UtilsCore (纯 Kotlin MD5 实现), 与 jvmAndAndroidMain 字节级一致
 * (RFC 1321 标准, jvmTest MD5UtilsTest 向量对照)。
 *
 * 注: jvmAndAndroidMain 附加的 md5Encode(InputStream) 重载在 Native 不暴露
 * (commonMain 中 InputStream 是 expect abstract class, 流式 API 与 JVM 行为不同);
 * Native 若需流式 MD5, 应自行读取到 ByteArray 后调用 md5Encode(String) 或直接用 Md5Digest。
 */
actual object MD5Utils {

    actual fun md5Encode(str: String?): String = MD5UtilsCore.md5Encode(str)

    /** 字节数组全量 MD5 (commonMain expect 成员, 委托共享 Md5Digest) */
    actual fun md5Encode(bytes: ByteArray): String = MD5UtilsCore.md5Encode(bytes)

    actual fun md5Encode16(str: String): String = MD5UtilsCore.md5Encode16(str)
}
