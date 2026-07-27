@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import io.legado.app.nativecrypto.mbedtls.lg_ctr_drbg_seed
import io.legado.app.nativecrypto.mbedtls.mbedtls_ctr_drbg_context
import io.legado.app.nativecrypto.mbedtls.mbedtls_ctr_drbg_init
import io.legado.app.nativecrypto.mbedtls.mbedtls_ctr_drbg_random
import io.legado.app.nativecrypto.mbedtls.mbedtls_entropy_context
import io.legado.app.nativecrypto.mbedtls.mbedtls_entropy_init
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

/** mbedTLS 调用返回非 0 的统一异常, message 携带 -0xXXXX 形式错误码 (ERROR_C 未编译, 不做文案映射)。 */
internal class MbedTlsException(op: String, val code: Int) :
    RuntimeException("mbedTLS $op failed: -0x${(-code).toString(16)}")

/** ret != 0 即抛 [MbedTlsException]。 */
internal fun mbedCheck(op: String, ret: Int) {
    if (ret != 0) throw MbedTlsException(op, ret)
}

/** mbedTLS 主实现优先, 任意异常回落 [fallback]; 双失败时抛主实现异常并 suppress 回落异常。 */
internal inline fun <T> mbedTlsOrFallback(primary: () -> T, fallback: () -> T): T = try {
    primary()
} catch (primaryError: Throwable) {
    try {
        fallback()
    } catch (fallbackError: Throwable) {
        primaryError.addSuppressed(fallbackError)
        throw primaryError
    }
}

/** usePinned 禁止对空数组取址: 空输入用 1 字节占位指针 + len=0 传给 C (与 iOS ccSha224 先例一致)。 */
internal inline fun <T> ByteArray.usePinnedInput(block: (CPointer<UByteVar>, ULong) -> T): T {
    val buf = if (isEmpty()) ByteArray(1) else this
    return buf.usePinned { pin -> block(pin.addressOf(0).reinterpret(), size.convert()) }
}

/**
 * 进程级 mbedTLS RNG 单例: entropy(/dev/urandom) + CTR_DRBG。
 * THREADING_C 未开, DRBG context 非线程安全: 取随机与所有携 DRBG 的 RSA 私钥操作都必须经
 * [withDrbg] 持锁 (atomicfu SynchronizedObject, Kotlin 层保证互斥); 懒初始化, seed 失败置 sticky
 * 不可用, 之后每次调用直接抛异常由调用方回落各平台既有实现。
 */
internal object MbedTlsRng {

    private val lock = SynchronizedObject()

    /** 0=未初始化, 1=可用, -1=seed 失败 (仅持锁读写) */
    private var state = 0
    private val entropy = nativeHeap.alloc<mbedtls_entropy_context>()
    private val drbg = nativeHeap.alloc<mbedtls_ctr_drbg_context>()

    /** 持锁执行 [block]; DRBG 指针仅限 block 内使用, RNG 不可用时抛异常。 */
    fun <T> withDrbg(block: (CPointer<mbedtls_ctr_drbg_context>) -> T): T = synchronized(lock) {
        ensureReady()
        block(drbg.ptr)
    }

    /** CTR_DRBG 生成 [size] 字节随机数 (密钥级)。 */
    fun random(size: Int): ByteArray {
        require(size >= 0) { "invalid random size: $size" }
        val out = ByteArray(size)
        if (size == 0) return out
        withDrbg { d ->
            out.usePinned { pin ->
                mbedCheck(
                    "ctr_drbg_random",
                    mbedtls_ctr_drbg_random(d, pin.addressOf(0).reinterpret(), size.convert())
                )
            }
        }
        return out
    }

    private fun ensureReady() {
        when (state) {
            1 -> return
            -1 -> throw IllegalStateException("mbedTLS RNG unavailable (ctr_drbg seed failed earlier)")
            else -> {
                mbedtls_entropy_init(entropy.ptr)
                mbedtls_ctr_drbg_init(drbg.ptr)
                val pers = "legado-mbedtls".encodeToByteArray()
                val ret = pers.usePinned { pin ->
                    lg_ctr_drbg_seed(drbg.ptr, entropy.ptr, pin.addressOf(0).reinterpret(), pers.size.convert())
                }
                if (ret != 0) {
                    state = -1
                    throw MbedTlsException("ctr_drbg_seed", ret)
                }
                state = 1
            }
        }
    }
}
