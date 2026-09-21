# mbedTLS vendored crypto (iOS / 鸿蒙 native 加解密统一底座)

## 来源版本与升级方法

- 来源: [Mbed-TLS/mbedtls](https://github.com/Mbed-TLS/mbedtls) tag `mbedtls-3.6.7` (3.6 LTS 线, 双许可 Apache-2.0 OR GPL-2.0-or-later, 本目录随附 LICENSE 为 Apache-2.0 文本)
- 下载: `https://codeload.github.com/Mbed-TLS/mbedtls/tar.gz/refs/tags/mbedtls-3.6.x`
- 升级步骤 (3.6 补丁线内):
  1. 解包新 tag, 覆盖 `include/mbedtls/`、`include/psa/` 全部头文件;
  2. 按下方清单逐个覆盖 `library/` 下 33 个 `.c` + 20 个内部 `.h` (上游新增内部头会在第 3 步暴露);
  3. include 闭包核对: 遍历 `library/*.{c,h}` 的 `#include "..."`, 未落在本目录者必须处于被
     `legado_mbedtls_config.h` 关闭的宏守护内 (目前白名单: aesni/aesce/padlock/block_cipher_internal/psa_util_internal/psa_crypto_core);
  4. 本地自洽验证 (任一带 clang 的环境, 不依赖目标 SDK):
     `clang --target=aarch64-w64-windows-gnu -std=c99 -Wall -I include -I . -DMBEDTLS_CONFIG_FILE='"legado_mbedtls_config.h"' -c library/*.c`;
  5. 对照上游 ChangeLog 检查 `legado_mbedtls_config.h` 涉及开关是否有语义变化 (如 3.6.7 新增的 `MBEDTLS_PLATFORM_DEV_RANDOM`)。

## 裁剪清单与理由

只保留书源加解密所需 crypto 子集 (摘要/HMAC、AES 全模式、DES/3DES、RSA 全向 + PEM/PK 解析、RNG), 不含 TLS/X509/PSA 实现:

- `include/mbedtls/` 全部头 + `include/psa/` 全部头: psa 头是被动依赖 —
  `pk_wrap.c` 无守护地 include `mbedtls/psa_util.h`、`rsa.c` include `md_psa.h`, 二者均拉入 `psa/crypto.h`;
  PSA 相关宏全关, psa 头只贡献类型声明, **没有任何 `psa_*.c` 参与编译**。
- `library/` 33 个 `.c`: 摘要 (md/md5/sha1/sha256/sha512/ripemd160), 对称 (aes/des/cipher/cipher_wrap/gcm/ccm),
  RSA 线 (rsa/rsa_alt_helpers/bignum/bignum_core/pk/pk_wrap/pk_ecc/pkparse/pkwrite/oid),
  编码 (pem/base64/asn1parse/asn1write), RNG (ctr_drbg/entropy/entropy_poll + 备用 hmac_drbg),
  基建 (platform/platform_util/constant_time)。
  `pk_ecc.c` 在 EC 全关时编译为空, 保留以免上游 include 闭包破裂; `hmac_drbg.c` 预留 (config 未开)。
- 刻意不带: `ecp*/bignum_mod*` (无 EC 需求)、`x509*/ssl*/net_sockets/timing/debug` (无 TLS)、
  `psa_crypto*.c` (PSA 关)、`aesni/aesce/padlock.c` (x86 专属 / 需 getauxval 探测, 纯 C AES 足够)、
  `nist_kw/cmac/camellia/aria/chacha*/poly1305/sha3/lms/lmots` (书源无此需求)、`error.c/version*.c` (省体积)。
- 配置注入: 所有编译入口统一 `-DMBEDTLS_CONFIG_FILE="legado_mbedtls_config.h"`, 该文件整体替换默认
  `mbedtls_config.h`, 每个开关的理由见文件内注释; 合法性由 `check_config.h` 编译期校验。
- RNG 方案: `entropy`(平台熵源) + `ctr_drbg`。entropy_poll 在 ohos(musl, 无 `__GLIBC__` 不走 getrandom
  syscall 分支) 与 iOS(`__APPLE__`) 均直接 `fopen("/dev/urandom")` (不依赖 `MBEDTLS_FS_IO`),
  两端沙箱都允许读该设备文件。

## C 编译路径 (bindings 与目标码分离)

`shared/src/cinterop/mbedtls.def` 只负责生成 Kotlin 绑定 (package `io.legado.app.nativecrypto.mbedtls`)
和编译 def 内的 `lg_*` wrapper; `library/*.c` 的目标码各平台单独接线:

- **鸿蒙 (ohosArm64)**: `ohosApp/entry/src/main/cpp/CMakeLists.txt` 把 `library/*.c` (GLOB) 编进
  `liblegado_napi.so`。`liblegado_shared.so` (Kotlin/Native) 中的未定义 `mbedtls_*` 引用在运行时由
  同一 dlopen 组内的 legado_napi 导出符号解析 (linux .so 允许链接期悬空符号)。
- **iOS (iosArm64/iosSimulatorArm64)**: **尚未接线** (与 quickjs 先例同缺口, gradle 侧没有编译 C 源的机制,
  cinterop 不编译 includeDirs 里的 .c)。第二阶段起 Kotlin actual (MbedTls* 五件套) 已无条件引用绑定符号,
  **mac 侧补 .a 是 iOS 出包硬前置**: 缺 .a 是链接期失败, 运行期 fallback 兜不住。
  Mach-O 动态 framework 链接期不允许悬空符号, 需在 mac 侧补:
  按 target 用 xcrun clang 把 `library/*.c` 编成 `libmbedtls.a`
  (真机 `-target arm64-apple-ios14.0`, 模拟器 `-target arm64-apple-ios14.0-simulator`),
  然后在 `shared/build.gradle.kts` 的 mbedtls cinterop 块按 target 追加
  `extraOpts("-staticLibrary", "libmbedtls.a", "-libraryPath", "<对应产物目录>")`,
  或在 framework link task 加 linkerOpts 指向 .a。quickjs-ng 的 4 个 .c 需要同样处理。
