@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.help.crypto

import io.legado.app.nativecrypto.mbedtls.MBEDTLS_GCM_ENCRYPT
import io.legado.app.nativecrypto.mbedtls.lg_cipher_set_padding_none
import io.legado.app.nativecrypto.mbedtls.lg_cipher_setkey
import io.legado.app.nativecrypto.mbedtls.lg_gcm_setkey_aes
import io.legado.app.nativecrypto.mbedtls.mbedtls_cipher_context_t
import io.legado.app.nativecrypto.mbedtls.mbedtls_cipher_crypt
import io.legado.app.nativecrypto.mbedtls.mbedtls_cipher_free
import io.legado.app.nativecrypto.mbedtls.mbedtls_cipher_info_from_string
import io.legado.app.nativecrypto.mbedtls.mbedtls_cipher_init
import io.legado.app.nativecrypto.mbedtls.mbedtls_cipher_setup
import io.legado.app.nativecrypto.mbedtls.mbedtls_cipher_update
import io.legado.app.nativecrypto.mbedtls.mbedtls_gcm_auth_decrypt
import io.legado.app.nativecrypto.mbedtls.mbedtls_gcm_context
import io.legado.app.nativecrypto.mbedtls.mbedtls_gcm_crypt_and_tag
import io.legado.app.nativecrypto.mbedtls.mbedtls_gcm_free
import io.legado.app.nativecrypto.mbedtls.mbedtls_gcm_init
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * mbedTLS cipher/gcm 层对称加解密 (iOS/鸿蒙主实现)。
 *
 * - AES: ECB/CBC/CFB(=CFB128, 对齐 JCA)/OFB/CTR 走 cipher 通用层; GCM 走 gcm.h 专用 API,
 *   密文 = cipher || tag(16B), 对齐 JCA AES/GCM/NoPadding; PCBC 无 mbedTLS 原生实现, 点名抛异常
 *   由 actual 回落 (iOS krypto 支持 PCBC)。
 * - DES/DESede: 仅 ECB/CBC (cipher_wrap 仅注册这两种); DESede 24B 密钥→DES-EDE3, 16B→DES-EDE。
 * - padding 统一 Kotlin 层实现 (mbedTLS cipher 层 padding 仅支持 CBC 且无 ISO10126),
 *   语义对齐 krypto/JCA/hutool: PKCS7 逐字节校验, ZERO 仅在不对齐时补零、解密剥全部尾零。
 */
internal object MbedTlsCipher {

    private const val GCM_TAG_LEN = 16

    fun encrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray {
        if (mode == "GCM") return gcmCrypt(true, key, iv, data, padding)
        val input = pad(padding, data, blockSize(algorithm), mode)
        return coreCrypt(algorithm, mode, key, iv, encrypt = true, input = input)
    }

    fun decrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray {
        if (mode == "GCM") return gcmCrypt(false, key, iv, data, padding)
        val plain = coreCrypt(algorithm, mode, key, iv, encrypt = false, input = data)
        return unpad(padding, plain, blockSize(algorithm))
    }

    // ===== 通用 cipher 层 =====

    private fun coreCrypt(
        algorithm: String,
        mode: String,
        key: ByteArray,
        iv: ByteArray?,
        encrypt: Boolean,
        input: ByteArray
    ): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val name = cipherName(algorithm, key.size, mode)
        return withCipher(name, key, encrypt) { ctx ->
            if (mode == "ECB") {
                ecbLoop(ctx, name, input, blockSize(algorithm))
            } else {
                oneShot(ctx, name, requireNotNull(iv) { "IV is required for $algorithm/$mode" }, input, mode)
            }
        }
    }

    private inline fun <T> withCipher(
        name: String,
        key: ByteArray,
        encrypt: Boolean,
        block: (CPointer<mbedtls_cipher_context_t>) -> T
    ): T = memScoped {
        val info = mbedtls_cipher_info_from_string(name)
            ?: throw UnsupportedOperationException("MbedTlsCipher: '$name' not in trimmed mbedTLS build")
        val ctx = alloc<mbedtls_cipher_context_t>()
        mbedtls_cipher_init(ctx.ptr)
        try {
            mbedCheck("cipher_setup($name)", mbedtls_cipher_setup(ctx.ptr, info))
            key.usePinned { kp ->
                mbedCheck(
                    "cipher_setkey($name)",
                    lg_cipher_setkey(ctx.ptr, kp.addressOf(0).reinterpret(), key.size * 8, if (encrypt) 1 else 0)
                )
            }
            block(ctx.ptr)
        } finally {
            mbedtls_cipher_free(ctx.ptr)
        }
    }

    /** cipher 层 ECB 每次 update 只吃单块, 逐块循环 (输入已保证块对齐)。 */
    private fun ecbLoop(
        ctx: CPointer<mbedtls_cipher_context_t>,
        name: String,
        input: ByteArray,
        block: Int
    ): ByteArray {
        require(input.size % block == 0) {
            "MbedTlsCipher: input length ${input.size} not a multiple of block size $block (NoPadding?)"
        }
        val out = ByteArray(input.size)
        memScoped {
            val olen = alloc<ULongVar>()
            input.usePinned { ip ->
                out.usePinned { op ->
                    var off = 0
                    while (off < input.size) {
                        mbedCheck(
                            "cipher_update($name)",
                            mbedtls_cipher_update(
                                ctx,
                                ip.addressOf(off).reinterpret(), block.convert(),
                                op.addressOf(off).reinterpret(), olen.ptr
                            )
                        )
                        off += block
                    }
                }
            }
        }
        return out
    }

    /** CBC/CFB/OFB/CTR 单发 cipher_crypt (set_iv+reset+update+finish); CBC 显式关 padding。 */
    private fun oneShot(
        ctx: CPointer<mbedtls_cipher_context_t>,
        name: String,
        iv: ByteArray,
        input: ByteArray,
        mode: String
    ): ByteArray = memScoped {
        if (mode == "CBC") {
            mbedCheck("cipher_set_padding_none($name)", lg_cipher_set_padding_none(ctx))
        }
        val out = ByteArray(input.size + 16)
        val olen = alloc<ULongVar>()
        iv.usePinned { vp ->
            input.usePinned { ip ->
                out.usePinned { op ->
                    mbedCheck(
                        "cipher_crypt($name)",
                        mbedtls_cipher_crypt(
                            ctx,
                            vp.addressOf(0).reinterpret(), iv.size.convert(),
                            ip.addressOf(0).reinterpret(), input.size.convert(),
                            op.addressOf(0).reinterpret(), olen.ptr
                        )
                    )
                }
            }
        }
        out.copyOf(olen.value.toInt())
    }

    // ===== GCM (专用 API, tag 语义对齐 JCA: 密文尾接 16 字节 tag) =====

    private fun gcmCrypt(
        encrypt: Boolean,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
        padding: String
    ): ByteArray {
        require(padding == "NOPADDING") { "AES/GCM only supports NoPadding (got $padding)" }
        val realIv = requireNotNull(iv) { "IV is required for AES/GCM" }
        require(realIv.isNotEmpty()) { "AES/GCM IV must not be empty" }
        return memScoped {
            val ctx = alloc<mbedtls_gcm_context>()
            mbedtls_gcm_init(ctx.ptr)
            try {
                key.usePinned { kp ->
                    mbedCheck(
                        "gcm_setkey",
                        lg_gcm_setkey_aes(ctx.ptr, kp.addressOf(0).reinterpret(), (key.size * 8).convert())
                    )
                }
                if (encrypt) {
                    val out = ByteArray(data.size + GCM_TAG_LEN)
                    realIv.usePinned { vp ->
                        data.usePinnedInput { dp, dlen ->
                            out.usePinned { op ->
                                mbedCheck(
                                    "gcm_crypt_and_tag",
                                    mbedtls_gcm_crypt_and_tag(
                                        ctx.ptr, MBEDTLS_GCM_ENCRYPT, dlen,
                                        vp.addressOf(0).reinterpret(), realIv.size.convert(),
                                        null, 0u.convert(),
                                        dp, op.addressOf(0).reinterpret(),
                                        GCM_TAG_LEN.convert(), op.addressOf(data.size).reinterpret()
                                    )
                                )
                            }
                        }
                    }
                    out
                } else {
                    require(data.size >= GCM_TAG_LEN) { "AES/GCM ciphertext too short: ${data.size}" }
                    val n = data.size - GCM_TAG_LEN
                    val out = ByteArray(maxOf(n, 1))
                    realIv.usePinned { vp ->
                        data.usePinned { dp ->
                            out.usePinned { op ->
                                val ret = mbedtls_gcm_auth_decrypt(
                                    ctx.ptr, n.convert(),
                                    vp.addressOf(0).reinterpret(), realIv.size.convert(),
                                    null, 0u.convert(),
                                    dp.addressOf(n).reinterpret(), GCM_TAG_LEN.convert(),
                                    dp.addressOf(0).reinterpret(), op.addressOf(0).reinterpret()
                                )
                                // AUTH_FAILED 对齐 JCA AEADBadTagException (以异常表达)
                                mbedCheck("gcm_auth_decrypt", ret)
                            }
                        }
                    }
                    out.copyOf(n)
                }
            } finally {
                mbedtls_gcm_free(ctx.ptr)
            }
        }
    }

    // ===== 命名 / padding =====

    private fun blockSize(algorithm: String): Int = if (algorithm == "AES") 16 else 8

    /** 归一化 token → mbedtls cipher_wrap 注册名; 无对应实现的组合点名抛异常。 */
    private fun cipherName(algorithm: String, keySize: Int, mode: String): String = when (algorithm) {
        "AES" -> {
            val m = when (mode) {
                "ECB" -> "ECB"
                "CBC" -> "CBC"
                "CFB" -> "CFB128"
                "OFB" -> "OFB"
                "CTR" -> "CTR"
                else -> throw UnsupportedOperationException(
                    "MbedTlsCipher: AES/$mode has no mbedTLS implementation (PCBC 无原生实现)"
                )
            }
            "AES-${keySize * 8}-$m"
        }
        "DES" -> when (mode) {
            "ECB" -> "DES-ECB"
            "CBC" -> "DES-CBC"
            else -> throw UnsupportedOperationException("MbedTlsCipher: DES/$mode not supported (only ECB/CBC)")
        }
        "DESEDE" -> {
            val prefix = if (keySize == 24) "DES-EDE3" else "DES-EDE"
            when (mode) {
                "ECB" -> "$prefix-ECB"
                "CBC" -> "$prefix-CBC"
                else -> throw UnsupportedOperationException("MbedTlsCipher: DESede/$mode not supported (only ECB/CBC)")
            }
        }
        else -> throw UnsupportedOperationException("MbedTlsCipher: unsupported algorithm '$algorithm'")
    }

    /** 加密向 padding (krypto/JCA 语义: 对齐时 PKCS7/X923/ISO10126 仍补整块, ZERO 仅在需要时补)。 */
    private fun pad(padding: String, data: ByteArray, block: Int, mode: String): ByteArray {
        val n = block - data.size % block
        return when (padding) {
            "NOPADDING" -> {
                if (mode == "ECB" || mode == "CBC" || mode == "PCBC") {
                    require(data.size % block == 0) {
                        "MbedTlsCipher: NoPadding requires block-aligned input (${data.size} % $block != 0)"
                    }
                }
                data
            }
            "PKCS7PADDING" -> data + ByteArray(n) { n.toByte() }
            "ANSIX923PADDING" -> data + ByteArray(n).also { it[n - 1] = n.toByte() }
            "ISO10126PADDING" -> data + MbedTlsRng.random(n).also { it[n - 1] = n.toByte() }
            "ZEROPADDING" -> if (n == block) data else data + ByteArray(n)
            else -> throw UnsupportedOperationException("MbedTlsCipher: unsupported padding '$padding'")
        }
    }

    /** 解密向去 padding; PKCS7 逐字节校验 (对齐 JCA BadPaddingException), X923/ISO10126 仅校验长度字节。 */
    private fun unpad(padding: String, data: ByteArray, block: Int): ByteArray = when (padding) {
        "NOPADDING" -> data
        "PKCS7PADDING" -> {
            val n = padCount(data, block)
            for (i in data.size - n until data.size) {
                if ((data[i].toInt() and 0xFF) != n) throw IllegalStateException("MbedTlsCipher: bad PKCS7 padding")
            }
            data.copyOf(data.size - n)
        }
        "ANSIX923PADDING", "ISO10126PADDING" -> data.copyOf(data.size - padCount(data, block))
        "ZEROPADDING" -> {
            var end = data.size
            while (end > 0 && data[end - 1] == 0.toByte()) end--
            data.copyOf(end)
        }
        else -> throw UnsupportedOperationException("MbedTlsCipher: unsupported padding '$padding'")
    }

    private fun padCount(data: ByteArray, block: Int): Int {
        if (data.isEmpty()) throw IllegalStateException("MbedTlsCipher: empty data, nothing to unpad")
        val n = data.last().toInt() and 0xFF
        if (n < 1 || n > block || n > data.size) throw IllegalStateException("MbedTlsCipher: bad padding length $n")
        return n
    }
}

/**
 * [MbedTlsCipherOps] 的 leaf actual (mbedTLS cipher 实现)。
 *
 * expect 声明在 nativeMain 的 MbedTlsCipherOps.kt, 本 actual 随文件 stage 进 leaf 后配对;
 * iOS/鸿蒙两侧共用同一实现。
 */
internal actual object MbedTlsCipherOps {
    actual fun encrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray = MbedTlsCipher.encrypt(algorithm, mode, padding, key, iv, data)

    actual fun decrypt(
        algorithm: String,
        mode: String,
        padding: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray
    ): ByteArray = MbedTlsCipher.decrypt(algorithm, mode, padding, key, iv, data)
}
