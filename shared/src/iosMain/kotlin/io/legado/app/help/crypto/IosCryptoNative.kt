@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeEC
import platform.Security.kSecAttrKeyTypeRSA

/**
 * iOS Security.framework / CoreFoundation 桥接辅助 (供 [NativeAsymmetricCryptoOps] / [NativeSignOps] 复用)。
 *
 * CFData/CFDictionary/CFError 走纯 C 路径 (CFDataCreate/CFDictionaryCreate, 不依赖 ObjC toll-free bridging)。
 * 所有 CF 对象 (CFData/CFDictionary/CFError) 遵循 Create/Copy 规则: 持有者用完 CFRelease。
 *
 * 注: 本文件涉及大量 cinterop 类型, Windows 无法编译验证, 需在 macOS 跑
 * `:shared:compileKotlinIosArm64` 对拍修正。
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosCryptoNative {

    /** ByteArray → CFDataRef (调用方持有, 用完 CFRelease)。 */
    fun cfData(bytes: ByteArray): CFDataRef? = bytes.usePinned { pinned ->
        CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.toLong())
    }

    /** CFDataRef → ByteArray。 */
    fun cfDataToBytes(data: CFDataRef?): ByteArray {
        requireNotNull(data) { "iOS crypto: CFData is null" }
        val len = CFDataGetLength(data).toInt()
        if (len <= 0) return ByteArray(0)
        val ptr = CFDataGetBytePtr(data) ?: return ByteArray(0)
        return ptr.readBytes(len)
    }

    /**
     * DER → SecKeyRef (SecKeyCreateWithData)。
     *
     * @param der 私钥 PKCS#8 或公钥 X.509 DER 字节
     * @param isPrivate true=私钥 (kSecAttrKeyClassPrivate), false=公钥 (kSecAttrKeyClassPublic)
     * @param keyType kSecAttrKeyTypeRSA 或 kSecAttrKeyTypeEC
     */
    fun secKeyFromDer(der: ByteArray, isPrivate: Boolean, keyType: CFStringRef?): SecKeyRef? {
        val keyData = cfData(der) ?: throw UnsupportedOperationException("iOS crypto: CFDataCreate returned null")
        return try {
            val keys: Array<CFStringRef?> = arrayOf(kSecAttrKeyType, kSecAttrKeyClass)
            val values: Array<CFStringRef?> = arrayOf(
                keyType,
                if (isPrivate) kSecAttrKeyClassPrivate else kSecAttrKeyClassPublic
            )
            memScoped {
                val attrs = CFDictionaryCreate(
                    kCFAllocatorDefault,
                    cValuesOf(*keys),
                    cValuesOf(*values),
                    keys.size.toLong(),
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr
                )
                val err = allocCFError()
                val secKey = SecKeyCreateWithData(keyData, attrs, err)
                attrs?.let { CFRelease(it) }
                if (secKey == null) {
                    throwIosCryptoError("SecKeyCreateWithData", err.pointed?.value)
                }
                secKey
            }
        } finally {
            CFRelease(keyData)
        }
    }

    /** memScoped 内 alloc 一个 CFErrorRef 输出槽 (CPointer<CFErrorRefVar>), 直接传给 SecKey* 的 error 参数, 用 .pointed.value 读 CFErrorRef。 */
    fun MemScope.allocCFError(): kotlinx.cinterop.CPointer<CFErrorRefVar> = alloc()

    /** 读 CFError code 抛异常 (CFErrorGetCode); CFError 由系统在 err 槽内分配, 此处 CFRelease。 */
    fun throwIosCryptoError(op: String, err: CFErrorRef?): Nothing {
        val code = if (err != null) CFErrorGetCode(err).toString() else "null"
        err?.let { CFRelease(it) }
        throw UnsupportedOperationException("iOS crypto $op failed (errSecCode=$code)")
    }
}
