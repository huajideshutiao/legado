/**
 * legado 裁剪版 mbedTLS 3.6.7 配置 (经 -DMBEDTLS_CONFIG_FILE 注入, 整体替换默认 mbedtls_config.h)。
 * 仅保留书源加解密所需 crypto: 摘要/HMAC + AES/DES + RSA/PK/PEM + RNG, 不含 TLS/X509/PSA。
 * 合法性由 build_info.h 自动包含的 check_config.h 校验。
 */
#ifndef LEGADO_MBEDTLS_CONFIG_H
#define LEGADO_MBEDTLS_CONFIG_H

/* ==== 平台基础 ==== */
/* mbedtls_calloc/free/printf 平台抽象层 (platform.c), 默认映射 libc */
#define MBEDTLS_PLATFORM_C
/* bn_mul.h 含 aarch64 内联汇编, iOS/ohos 三 target 均为 arm64 + clang, 开启加速 bignum */
#define MBEDTLS_HAVE_ASM
/* 平台熵源设备文件: 默认 "/dev/random" 在老内核可能阻塞, 统一改 urandom;
 * iOS 沙箱与 ohos 应用沙箱均允许读该设备 (entropy_poll.c 直接 fopen, 不依赖 MBEDTLS_FS_IO)。
 * ohos(musl) 无 __GLIBC__ 不走 getrandom syscall 分支, 直接落此文件; iOS(__APPLE__) 同 */
#define MBEDTLS_PLATFORM_DEV_RANDOM "/dev/urandom"
/* 不开 MBEDTLS_HAVE_TIME/TIMING_C: 无 TLS 会话, crypto 路径不需要时间源 */
/* 不开 MBEDTLS_FS_IO: 仅 pk_parse_keyfile/entropy seed file 等文件 API 需要, Kotlin 层传内存 buffer */
/* 不开 MBEDTLS_THREADING_C: context 生命周期由 Kotlin actual 管理, 不跨线程共享单个 context */
/* 不开 MBEDTLS_SELF_TEST: 不编译自测试代码, 减体积 */
/* 不开 MBEDTLS_ERROR_C/VERSION_C: 错误码在 Kotlin 层映射, 免带 error.c/version*.c */

/* ==== 摘要 (MD5withRSA/SHA*withRSA 及 JsExtensions digest/HMac 需要全套) ==== */
#define MBEDTLS_MD_C            /* md 通用分发层 + HMAC (mbedtls_md_hmac_*) */
#define MBEDTLS_MD5_C
#define MBEDTLS_SHA1_C
#define MBEDTLS_SHA224_C
#define MBEDTLS_SHA256_C
#define MBEDTLS_SHA384_C
#define MBEDTLS_SHA512_C
#define MBEDTLS_RIPEMD160_C     /* hutool 端支持, 保持对拍能力, 成本一个 .c */

/* ==== 对称加解密 ==== */
#define MBEDTLS_AES_C
#define MBEDTLS_DES_C           /* DES/DESede(3DES), 书源历史兼容需要 (算法已过时, 仅兼容用途) */
#define MBEDTLS_CIPHER_C        /* cipher 通用层: 模式/padding 分发, GCM 走此层复用 AES */
#define MBEDTLS_CIPHER_MODE_CBC
#define MBEDTLS_CIPHER_MODE_CFB
#define MBEDTLS_CIPHER_MODE_CTR
#define MBEDTLS_CIPHER_MODE_OFB
#define MBEDTLS_GCM_C           /* AES/GCM/NoPadding */
#define MBEDTLS_CCM_C           /* AES/CCM, 与 GCM 同族顺带保留 */
#define MBEDTLS_CIPHER_PADDING_PKCS7          /* javax.crypto PKCS5Padding 等价 */
#define MBEDTLS_CIPHER_PADDING_ONE_AND_ZEROS  /* ISO7816-4 */
#define MBEDTLS_CIPHER_PADDING_ZEROS_AND_LEN  /* ANSI X.923 */
#define MBEDTLS_CIPHER_PADDING_ZEROS          /* ZeroPadding */
/* 不开 AESNI_C/AESCE_C/PADLOCK_C: 三 target 全是 arm64, AESNI/PADLOCK 是 x86 专属;
 * AESCE 需 getauxval 运行时探测且对应源文件未 vendor, 纯 C AES 对书源解密量级足够 */
/* 不开 XTS/CAMELLIA/ARIA/CHACHA20/POLY1305/NIST_KW/CMAC: 书源无此需求 */

/* ==== RSA / PK (全向: 公钥加密/私钥解密/私钥加密(签名原语)/公钥解密(验签原语)) ==== */
#define MBEDTLS_BIGNUM_C
#define MBEDTLS_RSA_C
#define MBEDTLS_PKCS1_V15       /* RSA/ECB/PKCS1Padding + MD5withRSA/SHA*withRSA 签名验签 */
#define MBEDTLS_PKCS1_V21       /* OAEP/PSS, 少数书源用到时不再回退 */
#define MBEDTLS_PK_C            /* pk 抽象层: pk_parse 出的 keypair 统一入口 */
#define MBEDTLS_PK_PARSE_C      /* PEM/DER 解析 PKCS#1/PKCS#8/X.509 SubjectPublicKeyInfo */
#define MBEDTLS_PK_WRITE_C      /* 密钥重编码 (PKCS#1<->PKCS#8 互转), 依赖 ASN1_WRITE */
#define MBEDTLS_OID_C           /* AlgorithmIdentifier OID 表, RSA/PK_PARSE 硬性依赖 */

/* ==== 编码 ==== */
#define MBEDTLS_ASN1_PARSE_C
#define MBEDTLS_ASN1_WRITE_C    /* pkwrite + PKCS1_V15 签名 DigestInfo 编码需要 */
#define MBEDTLS_BASE64_C        /* PEM 依赖 */
#define MBEDTLS_PEM_PARSE_C     /* "-----BEGIN ...-----" 键文本解析 */
#define MBEDTLS_PEM_WRITE_C     /* 密钥导出为 PEM 文本 */

/* ==== RNG (RSA blinding/OAEP/PKCS1 加密 padding 必须提供 f_rng) ====
 * 方案: entropy(平台熵源 fopen /dev/urandom, 见上) + CTR_DRBG(AES);
 * iOS 沙箱与 ohos 均可用, 无需 getrandom/getentropy 也无需 FS_IO */
#define MBEDTLS_ENTROPY_C       /* check_config: 需 SHA256 或 SHA512, 已满足 */
#define MBEDTLS_CTR_DRBG_C      /* check_config: 需 AES_C, 已满足 */
/* 不开 HMAC_DRBG_C: 仅确定性 ECDSA 需要 (hmac_drbg.c 已 vendor, 未来要开只加本行) */

/* ==== 显式关闭说明 (未 define 即关, 列出高频误开项) ====
 * ECP/ECDSA/ECDH: 书源无 EC 需求, 省 bignum_mod / ecp 一大片源码
 * X509 / SSL / TLS / NET_C / DEBUG_C: 只做 crypto, 不做证书与传输
 * PSA_CRYPTO_C/USE_PSA_CRYPTO: 3.6 legacy pk/rsa 路径不依赖 PSA (pk_wrap.c 的 psa 分支全在
 *   MBEDTLS_USE_PSA_CRYPTO/PSA_CRYPTO_CLIENT 守护内), psa 头文件仅作类型声明被动包含 */

#endif /* LEGADO_MBEDTLS_CONFIG_H */
