import cryptoFramework from '@ohos.security.cryptoFramework';
import { BusinessError } from '@ohos.base';
import { BridgeRequest, BridgeResponse, dispatchBridgeRequest } from './BridgeDispatch';
import { base64ToUint8, uint8ToBase64 } from './BridgeCodec';

/**
 * ArkTS 侧加解密/签名/验签/摘要/HMAC/对称处理器 (cryptoFramework napi 桥; 2026-10 起 native 不带
 * mbedTLS, 本桥是鸿蒙端加解密的主路径)。
 *
 * 接收 Kotlin OhosNativeBridge.invokeCryptoSync 通过 tsfn dispatch 过来的 crypto 请求,
 * 调用 @ohos.security.cryptoFramework (createCipher/createSign/createVerify/createMd/createMac) 执行真实运算,
 * 完成后通过 legado.cryptoCallback(requestId, resultJson) 回送结果给 Kotlin。
 *
 * # JSON 协议 (与 NativeAsymmetricCryptoOps.ohos.kt / NativeSignOps.ohos.kt / OhosNativeBridge.kt 对齐)
 *
 * ## 请求 (Kotlin → ArkTS, 由 OhosNativeBridge.CryptoBridgeRequest 序列化)
 * {
 *   "requestId": <number>,
 *   "action": "encrypt" | "decrypt" | "sign" | "verify",
 *   "payload": "<JSON string, action-specific>"
 * }
 *
 * ## encrypt/decrypt payload (Kotlin 归一层 AsymCipherSpec 输出的规范形态)
 * {
 *   "padding": "<归一 padding token>",            // PKCS1 / OAEP_SHA1|224|256|384|512 / NOPADDING / SM2
 *   "keyBits": <number>,                          // RSA 模长位数 (Kotlin 侧从 DER 模长算出; SM2 为 0)
 *   "usePublicKey": <boolean>,                    // true→用公钥加密/解密, false→用私钥
 *   "privateKey": "<base64 PKCS#8 DER>?",         // usePublicKey=false 时取此
 *   "publicKey": "<base64 X.509 DER>?",           // usePublicKey=true 时取此
 *   "chunk": <number>,                            // 分块大小 (Kotlin 侧按 hutool 语义算好; SM2 为 0)
 *   "data": "<base64>"
 * }
 *
 * ## sign payload
 * {
 *   "algorithm": "<JCA Signature alg>",           // 如 "SHA256withRSA" / "SHA256withRSA/PSS" / "SHA256withECDSA"
 *   "privateKey": "<base64 PKCS#8 DER>",
 *   "data": "<base64>"
 * }
 *
 * ## verify payload
 * {
 *   "algorithm": "<JCA Signature alg>",
 *   "publicKey": "<base64 X.509 DER>",
 *   "data": "<base64>",
 *   "signature": "<base64>"
 * }
 *
 * ## symEncrypt/symDecrypt payload
{
  "algorithm": "AES" | "SM4" | "DESEDE" | "DES",
  "mode": "ECB" | "CBC" | "CFB" | "OFB" | "CTR",
  "key": "<base64>",
  "iv": "<base64>?",
  "data": "<base64>"
}
// NoPadding 裸原语: padding 由 Kotlin 侧 SymPadding 处理, 桥侧恒 NoPadding

## 响应 (ArkTS → Kotlin, 由 legado.cryptoCallback 推送)
 * - encrypt/decrypt/sign 成功: `{ ok: true, data: "<base64>" }`
 * - verify 成功: `{ ok: true, result: true/false }` (result 表示验签是否通过, 不抛异常)
 * - 失败: `{ ok: false, error: "<string>" }`
 *
 * # 算法映射 (JCA → cryptoFramework spec, 对齐 jvmAndAndroid hutool + iOS Security.framework)
 *
 * ## 单一来源说明
 * 本桥是鸿蒙端加解密的唯一 cryptoFramework 映射源; 摘要/HMAC 的 SM3/RIPEMD160/SHA3 系与
 * AES/GCM 不走桥 (Kotlin 纯 Kotlin 侧车 GcmNative/KotlinDigestNative 双端同源), 其余算法桥内映射:
 *
 * ## 对称 (symEncrypt/symDecrypt)
 * - AES: `AES<keybits>|<mode>|NoPadding` (ECB/CBC/CFB/OFB/CTR, key 16/24/32B)
 * - SM4: `SM4_128|<mode>|NoPadding` (SM4 CFB → CFB128 对齐 JCA)
 * - DESede: `3DES192|<mode>|NoPadding` (仅 24 字节密钥; 16 字节 2-key EDE 无规格, 点名报错)
 * - DES: `DES64|<mode>|NoPadding` (系统侧 API 20+, 低版本设备报错透传)
 *
 * - RSA encrypt/decrypt:
 *     "RSA" / "RSA/ECB/PKCS1Padding"                 → `RSA<size>|PKCS1`
 *     "RSA/ECB/OAEPWithSHA-<1|256|384|512>AndMGF1Padding"
 *                                                    → `RSA<size>|PKCS1_OAEP|SHA<...>|MGF1_SHA<...>`
 *     "RSA/ECB/NoPadding"                            → `RSA<size>|NoPadding` (API 9+, 输入须恰为模长字节)
 * - SM2 encrypt/decrypt:
 *     "SM2" / "SM2|SM3" / "SM2/SM3"                  → `SM2_256|SM3` (密文 C1C3C2 GM/T 0009 格式)
 * - RSA sign/verify:
 *     "MD5withRSA" / "SHA<1|256|384|512>withRSA"     → `RSA<size>|PKCS1|<MD5|SHA...>`
 *     "SHA<1|256|384|512>WithRSA/PSS"                → `RSA<size>|PSS|SHA<...>|MGF1_SHA<...>`
 * - SM2 sign/verify:
 *     "SM2WithSM3" / "SM3withSM2"                    → `SM2_256|SM3`
 * - ECDSA sign/verify:
 *     "SHA<1|256|384|512>withECDSA"                  → `ECC<size>|SHA<...>`
 *
 * # 密钥大小探测
 * cryptoFramework spec 必须包含 keysize (如 RSA2048/ECC256), 但 JCA transformation 不含 keysize。
 * 用 AsyKeyGenerator.convertKey 尝试常见 keysize (RSA: 1024/2048/3072/4096/8192; ECC: 256/384/521),
 * 首个成功 convertKey 的 keysize 即为密钥实际大小 (DER 字节隐含 keysize)。
 */

// ===== 请求/响应接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface CryptoResponse extends BridgeResponse {
  data?: string;
  result?: boolean;
}

interface AsyCryptoPayload {
  padding: string;                   // 归一 padding token (Kotlin AsymCipherSpec): PKCS1 / OAEP_SHA* / NOPADDING / SM2
  keyBits: number;                   // RSA 模长位数 (Kotlin 侧从 DER 模长算出; SM2 为 0)
  usePublicKey: boolean;
  privateKey?: string;
  publicKey?: string;
  chunk: number;                     // 分块大小 (Kotlin 侧按 hutool 语义算好; SM2 为 0)
  data: string;
}

interface SignPayload {
  algorithm: string;
  privateKey: string;
  data: string;
}

interface VerifyPayload {
  algorithm: string;
  publicKey: string;
  data: string;
  signature: string;
}

// digest 请求 payload (Kotlin NativeDigestOps.digest → action="digest")
interface DigestPayload {
  algorithm: string;                   // MD5/SHA-1/SHA1/SHA-256/SHA256/SHA-512/SHA512
  data: string;                        // base64 编码的待摘要数据
}

// hmac 请求 payload (Kotlin NativeHmacOps.hmac → action="hmac")
/** 对称算法映射结果 (ArkTS 禁止用对象字面量作类型, 显式声明)。 */
interface SymCipherSpec {
  cipherSpec: string;
  keyGenSpec: string;
  mode: string;
}

interface HmacPayload {
  algorithm: string;                   // HmacMD5/HmacSHA256/HMAC-SHA512 等 (ArkTS 侧归一化)
  key: string;                         // base64 编码的 HMAC 密钥
  data: string;                        // base64 编码的待认证数据
}

// symEncrypt/symDecrypt 请求 payload (Kotlin PlatformCipherOps → action="symEncrypt"/"symDecrypt")
// NoPadding 裸原语: padding 由 Kotlin 侧 SymPadding 处理, 桥侧恒 NoPadding
interface SymPayload {
  algorithm: string;                   // AES / SM4 / DESEDE / DES
  mode: string;                        // ECB / CBC / CFB / OFB / CTR (GCM/PCBC 由 Kotlin 侧拦载)
  key: string;                         // base64 编码的密钥 (AES 16/24/32B, SM4 16B, DESede 24B, DES 8B)
  iv?: string;                         // base64 编码的 IV (非 ECB 必传)
  data: string;                        // base64 编码的明文/密文
}

// 由 EntryAbility 注入的结果回送函数类型 (内部调 legado.cryptoCallback)
export type CryptoCallback = (requestId: number, resultJson: string) => void;

/**
 * 处理来自 Kotlin 的 crypto 操作请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param requestJson 请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送结果的函数 (EntryAbility 注入 legado.cryptoCallback)
 */
export function handleCryptoRequest(requestJson: string, callback: CryptoCallback): void {
  dispatchBridgeRequest(requestJson, 'CryptoBridge', handleAction, callback);
}

// 根据 action 分发到具体处理函数
async function handleAction(req: BridgeRequest): Promise<CryptoResponse> {
  switch (req.action) {
    case 'encrypt': return await handleEncryptDecrypt(req.payload ?? '', true);
    case 'decrypt': return await handleEncryptDecrypt(req.payload ?? '', false);
    case 'sign': return await handleSign(req.payload ?? '');
    case 'verify': return await handleVerify(req.payload ?? '');
    case 'digest': return await handleDigest(req.payload ?? '');
    case 'hmac': return await handleHmac(req.payload ?? '');
    case 'symEncrypt': return await handleSymEncryptDecrypt(req.payload ?? '', true);
    case 'symDecrypt': return await handleSymEncryptDecrypt(req.payload ?? '', false);
    default: return { ok: false, error: 'unknown action: ' + req.action };
  }
}

// ===== encrypt/decrypt: cryptoFramework.createCipher + init + doFinal =====
// padding token + keyBits + chunk 均为 Kotlin 归一层 (AsymCipherSpec) 输出的规范形态,
// 本桥不解析 JCA transformation、不探测 keysize; 密钥转换整次请求一次, 分块循环在桥侧。
async function handleEncryptDecrypt(payload: string, isEncrypt: boolean): Promise<CryptoResponse> {
  try {
    const p = JSON.parse(payload) as AsyCryptoPayload;
    // 公钥加密场景需 publicKey; 私钥解密场景需 privateKey
    const keyB64 = p.usePublicKey ? p.publicKey : p.privateKey;
    if (keyB64 == null) {
      return { ok: false, error: (p.usePublicKey ? 'publicKey' : 'privateKey') + ' not set' };
    }
    const keyDer = base64ToUint8(keyB64);
    const dataBytes = base64ToUint8(p.data);

    if (p.padding === 'SM2') {
      // SM2: 规格 SM2_256|SM3, 密钥 PKCS#8/SPKI 由 Kotlin 侧透传 (不分块)
      const cipher = cryptoFramework.createCipher('SM2_256|SM3');
      const keyPair = await convertKey('SM2_256', keyDer, !p.usePublicKey);
      const key = p.usePublicKey ? keyPair.pubKey : keyPair.priKey;
      await initAsyCipher(cipher, isEncrypt, key);
      const resultBytes = await doFinalAsyCipher(cipher, dataBytes);
      return { ok: true, data: uint8ToBase64(resultBytes) };
    }

    // RSA: 密钥转换一次, doFinal 按 chunk 分块 (每块新建 Cipher, RSA Cipher 不支持重复 init)
    const spec = mapRsaCipherSpec(p.padding, p.keyBits);
    const keyPair = await convertKey('RSA' + p.keyBits, keyDer, !p.usePublicKey);
    const key = p.usePublicKey ? keyPair.pubKey : keyPair.priKey;
    const out: Uint8Array[] = [];
    for (let off = 0; off < dataBytes.length; off += p.chunk) {
      const len = Math.min(p.chunk, dataBytes.length - off);
      const cipher = cryptoFramework.createCipher(spec);
      await initAsyCipher(cipher, isEncrypt, key);
      out.push(await doFinalAsyCipher(cipher, dataBytes.subarray(off, off + len)));
    }
    let total = 0;
    for (const b of out) {
      total += b.length;
    }
    const result = new Uint8Array(total);
    let pos = 0;
    for (const b of out) {
      result.set(b, pos);
      pos += b.length;
    }
    return { ok: true, data: uint8ToBase64(result) };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}

// padding token (Kotlin 归一层 AsymCipherSpec 输出) → cryptoFramework RSA Cipher spec
// spec 格式参考 OHOS 官方文档: RSA2048|PKCS1 / RSA2048|PKCS1_OAEP|SHA256|MGF1_SHA256 / RSA2048|NoPadding
function mapRsaCipherSpec(padding: string, keyBits: number): string {
  if (padding === 'PKCS1') {
    return `RSA${keyBits}|PKCS1`;
  }
  const oaepMatch = padding.match(/^OAEP_SHA(1|224|256|384|512)$/);
  if (oaepMatch) {
    const md = 'SHA' + oaepMatch[1];
    return `RSA${keyBits}|PKCS1_OAEP|${md}|MGF1_${md}`;
  }
  if (padding === 'NOPADDING') {
    return `RSA${keyBits}|NoPadding`;
  }
  throw new Error('unsupported asymmetric padding token: ' + padding);
}

// ===== sign: cryptoFramework.createSign + init + sign =====
async function handleSign(payload: string): Promise<CryptoResponse> {
  try {
    const p = JSON.parse(payload) as SignPayload;
    const keyDer = base64ToUint8(p.privateKey);
    const dataBytes = base64ToUint8(p.data);

    const algUpper = p.algorithm.toUpperCase();
    let spec: string;
    let keyPair: cryptoFramework.KeyPair;
    if (algUpper.includes('SM2')) {
      // SM2: SM2_256|SM3, 密钥由 Kotlin 侧透传 (PKCS#8)
      spec = mapSignSpec(p.algorithm, 0, false);
      keyPair = await convertKey('SM2_256', keyDer, true);
    } else if (algUpper.includes('ECDSA')) {
      const keySize = await detectEccKeySize(keyDer, true);
      spec = mapSignSpec(p.algorithm, keySize, true);
      keyPair = await convertKey('ECC' + keySize, keyDer, true);
    } else {
      const keySize = await detectRsaKeySize(keyDer, true);
      spec = mapSignSpec(p.algorithm, keySize, false);
      keyPair = await convertKey('RSA' + keySize, keyDer, true);
    }

    const signer = cryptoFramework.createSign(spec);
    await initSign(signer, keyPair.priKey);
    const sigBytes = await signData(signer, dataBytes);
    return { ok: true, data: uint8ToBase64(sigBytes) };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}

// ===== verify: cryptoFramework.createVerify + init + verify =====
async function handleVerify(payload: string): Promise<CryptoResponse> {
  try {
    const p = JSON.parse(payload) as VerifyPayload;
    const keyDer = base64ToUint8(p.publicKey);
    const dataBytes = base64ToUint8(p.data);
    const sigBytes = base64ToUint8(p.signature);

    const algUpper = p.algorithm.toUpperCase();
    let spec: string;
    let keyPair: cryptoFramework.KeyPair;
    if (algUpper.includes('SM2')) {
      spec = mapSignSpec(p.algorithm, 0, false);
      keyPair = await convertKey('SM2_256', keyDer, false);
    } else if (algUpper.includes('ECDSA')) {
      const keySize = await detectEccKeySize(keyDer, false);
      spec = mapSignSpec(p.algorithm, keySize, true);
      keyPair = await convertKey('ECC' + keySize, keyDer, false);
    } else {
      const keySize = await detectRsaKeySize(keyDer, false);
      spec = mapSignSpec(p.algorithm, keySize, false);
      keyPair = await convertKey('RSA' + keySize, keyDer, false);
    }

    const verifier = cryptoFramework.createVerify(spec);
    await initVerify(verifier, keyPair.pubKey);
    // 验签返回 boolean: true→签名匹配, false→签名不匹配 (不抛异常, 对齐 JCA Signature.verify)
    const isVerified = await verifyData(verifier, dataBytes, sigBytes);
    return { ok: true, result: isVerified };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}

// ===== digest: cryptoFramework.createMd + update + digest =====
// KMP NativeDigestOps.digest → action="digest"
async function handleDigest(payload: string): Promise<CryptoResponse> {
  try {
    const p = JSON.parse(payload) as DigestPayload;
    const dataBytes = base64ToUint8(p.data);

    // 归一化算法名: MD5/SHA-1/SHA1/SHA-256/SHA256 → MD5/SHA1/SHA256/SHA512 (OHOS createMd 无连字符)
    const algName = normalizeDigestAlg(p.algorithm);
    const md = cryptoFramework.createMd(algName);
    await updateMd(md, dataBytes);
    const resultBytes = await digestMd(md);
    return { ok: true, data: uint8ToBase64(resultBytes) };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}

// ===== hmac: cryptoFramework.createMac + SymKeyGenerator.convertKey + init + update + doFinal =====
// KMP NativeHmacOps.hmac → action="hmac"
async function handleHmac(payload: string): Promise<CryptoResponse> {
  try {
    const p = JSON.parse(payload) as HmacPayload;
    const keyBytes = base64ToUint8(p.key);
    const dataBytes = base64ToUint8(p.data);

    // 归一化算法名: HmacMD5/HmacSHA256/HMAC-SHA512 → SHA-256 等 (OHOS createMac 用连字符格式)
    const algName = normalizeHmacAlg(p.algorithm);
    const mac = cryptoFramework.createMac(algName);
    // HMAC 密钥: SymKeyGenerator("HMAC").convertKey(DataBlob) → SymKey
    const symKey = await convertSymKey('HMAC', keyBytes);
    await initMac(mac, symKey);
    await updateMac(mac, dataBytes);
    const resultBytes = await doFinalMac(mac);
    return { ok: true, data: uint8ToBase64(resultBytes) };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}

// ===== symEncrypt/symDecrypt: cryptoFramework.createCipher + SymKeyGenerator.convertKey + init + doFinal =====
// NoPadding 裸原语: 输入已在 Kotlin 侧按 padding 补齐到块对齐, 桥侧恒 NoPadding;
// ECB 无 IV (params=null), CBC/CFB/OFB/CTR 传 IvParamsSpec
async function handleSymEncryptDecrypt(payload: string, isEncrypt: boolean): Promise<CryptoResponse> {
  try {
    const p = JSON.parse(payload) as SymPayload;
    const keyBytes = base64ToUint8(p.key);
    const dataBytes = base64ToUint8(p.data);
    const ivBytes = p.iv != null ? base64ToUint8(p.iv) : null;

    const spec = mapSymSpec(p.algorithm, p.mode, keyBytes.length);
    const cipher = cryptoFramework.createCipher(spec.cipherSpec);
    const symKey = await convertSymKey(spec.keyGenSpec, keyBytes);
    // ECB 模式无 IV, params 传 null; 其余模式传 IvParamsSpec
    const params = spec.mode === 'ECB' ? null : ivParams(ivBytes);
    await initCipher(cipher, isEncrypt, symKey, params);
    const resultBytes = await doFinalCipher(cipher, dataBytes);
    return { ok: true, data: uint8ToBase64(resultBytes) };
  } catch (e) {
    const err = e as BusinessError;
    return { ok: false, error: err.message ?? String(e) };
  }
}

/** 对称算法规格映射 (JCA token → cryptoFramework 字符串参数)。 */
function mapSymSpec(algorithm: string, mode: string, keyByteLength: number): SymCipherSpec {
  let keyGenSpec: string;
  switch (algorithm.toUpperCase()) {
    case 'AES':
      keyGenSpec = 'AES' + keyByteLength * 8;
      break;
    case 'SM4':
      // SM4 仅 128 位密钥, 规格名带下划线 (SM4_128)
      if (keyByteLength !== 16) {
        throw new Error('invalid SM4 key length: ' + keyByteLength + ' (must be 16)');
      }
      keyGenSpec = 'SM4_128';
      break;
    case 'DESEDE':
      // ohos 仅 3DES192 (24 字节密钥); 16 字节 2-key EDE 无规格, 点名报错
      if (keyByteLength !== 24) {
        throw new Error('invalid DESede key length: ' + keyByteLength + ' (ohos only supports 24-byte 3DES192)');
      }
      keyGenSpec = '3DES192';
      break;
    case 'DES':
      // DES64 系统侧 API 20+, 低版本设备由 createCipher 报错透传
      if (keyByteLength !== 8) {
        throw new Error('invalid DES key length: ' + keyByteLength + ' (must be 8)');
      }
      keyGenSpec = 'DES64';
      break;
    default:
      throw new Error('unsupported symmetric algorithm: ' + algorithm);
  }
  // JCA "CFB" = CFB128; ohos AES/3DES/DES 的 "CFB" 即 CFB128, SM4 需显式 "CFB128"
  const ohosMode = algorithm.toUpperCase() === 'SM4' && mode.toUpperCase() === 'CFB' ? 'CFB128' : mode.toUpperCase();
  const spec: SymCipherSpec = {
    cipherSpec: `${keyGenSpec}|${ohosMode}|NoPadding`,
    keyGenSpec,
    mode: mode.toUpperCase()
  };
  return spec;
}

/** 非 ECB 模式的 IvParamsSpec (iv 长度由算法决定: AES/SM4 16B, DES 系 8B, 由桥接调用方保证)。 */
function ivParams(ivBytes: Uint8Array | null): cryptoFramework.IvParamsSpec {
  if (ivBytes == null || ivBytes.length === 0) {
    throw new Error('IV is required for non-ECB mode');
  }
  return { algName: 'IvParamsSpec', iv: { data: ivBytes } } as cryptoFramework.IvParamsSpec;
}

// ===== 算法名归一化 (KMP → OHOS cryptoFramework) =====

// digest: MD5/SHA-1/SHA1/SHA-256/SHA256/SHA-512/SHA512 → MD5/SHA1/SHA256/SHA512 (OHOS createMd 无连字符)
function normalizeDigestAlg(algorithm: string): string {
  const alg = algorithm.toUpperCase().replace(/-/g, '');
  switch (alg) {
    case 'MD5': return 'MD5';
    case 'SHA1': return 'SHA1';
    case 'SHA224': return 'SHA224';
    case 'SHA256': return 'SHA256';
    case 'SHA384': return 'SHA384';
    case 'SHA512': return 'SHA512';
    default: throw new Error('unsupported digest algorithm: ' + algorithm);
  }
}

// hmac: HmacMD5/HmacSHA256/HMAC-SHA512 等 → MD5/SHA1/SHA256/SHA512 (OHOS createMac algName 与 createMd 同为无连字符格式)
function normalizeHmacAlg(algorithm: string): string {
  let alg = algorithm.toUpperCase();
  // 去掉 HMAC/Hmac 前缀 (含连字符变体), 与 iOS krypto actual 归一化逻辑对齐
  if (alg.startsWith('HMAC-')) {
    alg = alg.substring(5);
  } else if (alg.startsWith('HMAC')) {
    alg = alg.substring(4);
  }
  // OHOS createMac algName 无连字符 (与 createMd 一致: MD5/SHA1/SHA256/SHA384/SHA512)
  switch (alg) {
    case 'MD5': return 'MD5';
    case 'SHA1': return 'SHA1';
    case 'SHA224': return 'SHA224';
    case 'SHA256': return 'SHA256';
    case 'SHA384': return 'SHA384';
    case 'SHA512': return 'SHA512';
    default: throw new Error('unsupported HMAC algorithm: ' + algorithm);
  }
}

// AES key size 探测已随 symEncrypt/symDecrypt 通用化移除 (keyGenSpec 由 key 字节长度直接拼接)

// ===== cryptoFramework 对称密钥/摘要/HMAC/Cipher 异步回调包装为 Promise =====

// 对称密钥转换 (AES/HMAC): SymKeyGenerator.convertKey(DataBlob) → SymKey
// 注意: 与非对称 convertKey(pubKey, priKey) 双槽不同, 对称只有单个 key 参数
function convertSymKey(generatorSpec: string, keyBytes: Uint8Array): Promise<cryptoFramework.SymKey> {
  return new Promise((resolve, reject) => {
    const gen = cryptoFramework.createSymKeyGenerator(generatorSpec);
    const keyBlob: cryptoFramework.DataBlob = { data: keyBytes };
    gen.convertKey(keyBlob, (err: BusinessError, symKey: cryptoFramework.SymKey) => {
      if (err) {
        reject(err);
      } else {
        resolve(symKey);
      }
    });
  });
}

// Md: update(data) + digest() (流式 API, 收尾方法名是 digest 不是 doFinal)
function updateMd(md: cryptoFramework.Md, data: Uint8Array): Promise<void> {
  return new Promise((resolve, reject) => {
    md.update({ data: data }, (err: BusinessError) => {
      if (err) {
        reject(err);
      } else {
        resolve();
      }
    });
  });
}

function digestMd(md: cryptoFramework.Md): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    md.digest((err: BusinessError, dataBlob: cryptoFramework.DataBlob) => {
      if (err) {
        reject(err);
      } else {
        resolve(dataBlob.data);
      }
    });
  });
}

// Mac: init(key) + update(data) + doFinal()
function initMac(mac: cryptoFramework.Mac, key: cryptoFramework.SymKey): Promise<void> {
  return new Promise((resolve, reject) => {
    mac.init(key, (err: BusinessError) => {
      if (err) {
        reject(err);
      } else {
        resolve();
      }
    });
  });
}

function updateMac(mac: cryptoFramework.Mac, data: Uint8Array): Promise<void> {
  return new Promise((resolve, reject) => {
    mac.update({ data: data }, (err: BusinessError) => {
      if (err) {
        reject(err);
      } else {
        resolve();
      }
    });
  });
}

function doFinalMac(mac: cryptoFramework.Mac): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    mac.doFinal((err: BusinessError, dataBlob: cryptoFramework.DataBlob) => {
      if (err) {
        reject(err);
      } else {
        resolve(dataBlob.data);
      }
    });
  });
}

// Cipher: init(mode, key, params) + doFinal(data); params 为 null (ECB/RSA) 或 IvParamsSpec
function initCipher(cipher: cryptoFramework.Cipher, isEncrypt: boolean, key: cryptoFramework.SymKey,
  params: cryptoFramework.IvParamsSpec | null): Promise<void> {
  return new Promise((resolve, reject) => {
    const mode = isEncrypt ? cryptoFramework.CryptoMode.ENCRYPT_MODE : cryptoFramework.CryptoMode.DECRYPT_MODE;
    cipher.init(mode, key, params, (err: BusinessError) => {
      if (err) {
        reject(err);
      } else {
        resolve();
      }
    });
  });
}

function doFinalCipher(cipher: cryptoFramework.Cipher, data: Uint8Array): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    cipher.doFinal({ data: data }, (err: BusinessError, dataBlob: cryptoFramework.DataBlob) => {
      if (err) {
        reject(err);
      } else {
        resolve(dataBlob.data);
      }
    });
  });
}

// ===== 密钥大小探测 (sign/verify 用; cipher 路径的 keyBits 由 Kotlin 侧从 DER 模长算出, 不走探测) =====

async function detectRsaKeySize(der: Uint8Array, isPrivate: boolean): Promise<number> {
  const sizes = [1024, 2048, 3072, 4096, 8192];
  for (const size of sizes) {
    try {
      await convertKey('RSA' + size, der, isPrivate);
      return size;
    } catch (e) {
      // keysize 不匹配, 继续尝试下一个
    }
  }
  throw new Error('unsupported RSA key size (tried ' + sizes.join('/') + ')');
}

async function detectEccKeySize(der: Uint8Array, isPrivate: boolean): Promise<number> {
  const sizes = [256, 384, 521];
  for (const size of sizes) {
    try {
      await convertKey('ECC' + size, der, isPrivate);
      return size;
    } catch (e) {
      // 曲线不匹配, 继续尝试下一个
    }
  }
  throw new Error('unsupported ECC key size (tried ' + sizes.join('/') + ')');
}

// ===== cryptoFramework 异步回调包装为 Promise =====

function convertKey(generatorSpec: string, der: Uint8Array, isPrivate: boolean): Promise<cryptoFramework.KeyPair> {
  return new Promise((resolve, reject) => {
    const gen = cryptoFramework.createAsyKeyGenerator(generatorSpec);
    // convertKey(pubKey, priKey, cb): DER 放哪个槽即决定格式 (pub=X.509, pri=PKCS#8), 另一槽传 null
    const blob: cryptoFramework.DataBlob = { data: der };
    const pubKey: cryptoFramework.DataBlob | null = isPrivate ? null : blob;
    const priKey: cryptoFramework.DataBlob | null = isPrivate ? blob : null;
    gen.convertKey(pubKey, priKey, (err: BusinessError, keyPair: cryptoFramework.KeyPair) => {
      if (err) {
        reject(err);
      } else {
        resolve(keyPair);
      }
    });
  });
}

// RSA Cipher.init: ECB / PKCS1 / OAEP 均无 IV, params 传 null (与对称 initCipher 同模式)
function initAsyCipher(cipher: cryptoFramework.Cipher, isEncrypt: boolean, key: cryptoFramework.Key): Promise<void> {
  return new Promise((resolve, reject) => {
    const mode = isEncrypt ? cryptoFramework.CryptoMode.ENCRYPT_MODE : cryptoFramework.CryptoMode.DECRYPT_MODE;
    cipher.init(mode, key, null, (err: BusinessError) => {
      if (err) {
        reject(err);
      } else {
        resolve();
      }
    });
  });
}

function doFinalAsyCipher(cipher: cryptoFramework.Cipher, data: Uint8Array): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    cipher.doFinal({ data: data }, (err: BusinessError, dataBlob: cryptoFramework.DataBlob) => {
      if (err) {
        reject(err);
      } else {
        resolve(dataBlob.data);
      }
    });
  });
}

function initSign(signer: cryptoFramework.Sign, key: cryptoFramework.PriKey): Promise<void> {
  return new Promise((resolve, reject) => {
    signer.init(key, (err: BusinessError) => {
      if (err) {
        reject(err);
      } else {
        resolve();
      }
    });
  });
}

function signData(signer: cryptoFramework.Sign, data: Uint8Array): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    signer.sign({ data: data }, (err: BusinessError, dataBlob: cryptoFramework.DataBlob) => {
      if (err) {
        reject(err);
      } else {
        resolve(dataBlob.data);
      }
    });
  });
}

function initVerify(verifier: cryptoFramework.Verify, key: cryptoFramework.PubKey): Promise<void> {
  return new Promise((resolve, reject) => {
    verifier.init(key, (err: BusinessError) => {
      if (err) {
        reject(err);
      } else {
        resolve();
      }
    });
  });
}

function verifyData(verifier: cryptoFramework.Verify, data: Uint8Array, signature: Uint8Array): Promise<boolean> {
  return new Promise((resolve, reject) => {
    verifier.verify({ data: data }, { data: signature }, (err: BusinessError, isVerified: boolean) => {
      if (err) {
        reject(err);
      } else {
        resolve(isVerified);
      }
    });
  });
}

// ===== 算法映射 (JCA → cryptoFramework spec) =====

// sign/verify: JCA Signature algorithm → Sign/Verify algName
function mapSignSpec(jcaAlg: string, keySize: number, isEc: boolean): string {
  const alg = jcaAlg.toUpperCase();
  if (alg.startsWith('SM2')) {
    // "SM2WithSM3" → SM2_256|SM3 (鸿蒙 SM2 签名规格)
    return 'SM2_256|SM3';
  }
  if (isEc) {
    // "SHA<N>withECDSA" → ECC<size>|SHA<N>
    const ecMatch = alg.match(/^SHA(\d+)WITHECDSA$/);
    if (ecMatch) {
      return `ECC${keySize}|SHA${ecMatch[1]}`;
    }
    throw new Error('unsupported ECDSA sign/verify algorithm: ' + jcaAlg);
  }
  // RSA sign/verify
  // "SHA<N>WithRSA/PSS" → RSA<size>|PSS|SHA<N>|MGF1_SHA<N> (OHOS spec 用 MGF1_<md>, 对齐 BC 默认 mgf1 md = pss md)
  const pssMatch = alg.match(/^SHA(\d+)WITHRSA\/PSS$/);
  if (pssMatch) {
    const md = 'SHA' + pssMatch[1];
    return `RSA${keySize}|PSS|${md}|MGF1_${md}`;
  }
  // "MD5withRSA" / "SHA<N>withRSA" → RSA<size>|PKCS1|<MD5|SHA<N>>
  const pkcs1Match = alg.match(/^(MD5|SHA\d+)WITHRSA$/);
  if (pkcs1Match) {
    return `RSA${keySize}|PKCS1|${pkcs1Match[1]}`;
  }
  throw new Error('unsupported RSA sign/verify algorithm: ' + jcaAlg);
}

// ===== base64 / Uint8Array 互转: 已抽公共工具 bridge/BridgeCodec.ets (与 ImageBridgeHandler.ets 同模式) =====

