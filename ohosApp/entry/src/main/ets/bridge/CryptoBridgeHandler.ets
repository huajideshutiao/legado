import cryptoFramework from '@ohos.security.cryptoFramework';
import buffer from '@ohos.buffer';
import { BusinessError } from '@ohos.base';

/**
 * ArkTS 侧非对称加解密 + 签名/验签处理器 (方案 B cryptoFramework napi)。
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
 * ## encrypt/decrypt payload
 * {
 *   "algorithm": "<JCA transformation>",          // 如 "RSA" / "RSA/ECB/PKCS1Padding" / "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
 *   "usePublicKey": <boolean>,                    // true→用公钥加密/解密, false→用私钥
 *   "privateKey": "<base64 PKCS#8 DER>?",         // usePublicKey=false 时取此
 *   "publicKey": "<base64 X.509 DER>?",           // usePublicKey=true 时取此
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
 * ## 响应 (ArkTS → Kotlin, 由 legado.cryptoCallback 推送)
 * - encrypt/decrypt/sign 成功: `{ ok: true, data: "<base64>" }`
 * - verify 成功: `{ ok: true, result: true/false }` (result 表示验签是否通过, 不抛异常)
 * - 失败: `{ ok: false, error: "<string>" }`
 *
 * # 算法映射 (JCA → cryptoFramework spec, 对齐 jvmAndAndroid hutool + iOS Security.framework)
 *
 * ## 单一来源说明 (纯映射下沉评估)
 * 本映射仅用于 cryptoFramework **回落** 路径 (mbedTLS 主实现失败/不支持时):
 * - K/N NativeAsymmetricCryptoOps / NativeSignOps 主实现是 mbedTLS (MbedTlsOps), 直接接受
 *   JCA 字符串 (如 "RSA/ECB/PKCS1Padding" / "SHA256withRSA"), **无** JCA → cryptoFramework spec
 *   映射 (两层实现不同, 非重复维护);
 * - ArkTS 本文件的 mapAsyCodecSpec/mapSignSpec 是 mbedTLS 无法覆盖场景 (ECDSA 等) 的
 *   cryptoFramework 回落所需, 与 mbedTLS 处理互斥, 不构成双份。
 * 故不迁移: 保持 ArkTS 侧为 cryptoFramework 的**唯一** spec 映射源。
 *
 * - RSA encrypt/decrypt:
 *     "RSA" / "RSA/ECB/PKCS1Padding"                 → `RSA<size>|PKCS1`
 *     "RSA/ECB/OAEPWithSHA-<1|256|384|512>AndMGF1Padding"
 *                                                    → `RSA<size>|PKCS1_OAEP|SHA<...>|MGF1_SHA<...>`
 *     "RSA/ECB/NoPadding"                            → `RSA<size>|NoPadding` (cryptoFramework 可能不支持, 错误透传)
 * - RSA sign/verify:
 *     "MD5withRSA" / "SHA<1|256|384|512>withRSA"     → `RSA<size>|PKCS1|<MD5|SHA...>`
 *     "SHA<1|256|384|512>WithRSA/PSS"                → `RSA<size>|PSS|SHA<...>|MGF1_SHA<...>`
 * - ECDSA sign/verify:
 *     "SHA<1|256|384|512>withECDSA"                  → `ECC<size>|SHA<...>`
 *
 * # 密钥大小探测
 * cryptoFramework spec 必须包含 keysize (如 RSA2048/ECC256), 但 JCA transformation 不含 keysize。
 * 用 AsyKeyGenerator.convertKey 尝试常见 keysize (RSA: 1024/2048/3072/4096/8192; ECC: 256/384/521),
 * 首个成功 convertKey 的 keysize 即为密钥实际大小 (DER 字节隐含 keysize)。
 */

// ===== 请求/响应接口定义 (与 Kotlin 侧 JSON 协议对齐) =====

interface CryptoBridgeRequest {
  requestId: number;
  action: string;
  payload: string;
}

interface CryptoResponse {
  ok: boolean;
  data?: string;
  result?: boolean;
  error?: string;
}

interface AsyCryptoPayload {
  algorithm: string;
  usePublicKey: boolean;
  privateKey?: string;
  publicKey?: string;
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
interface HmacPayload {
  algorithm: string;                   // HmacMD5/HmacSHA256/HMAC-SHA512 等 (ArkTS 侧归一化)
  key: string;                         // base64 编码的 HMAC 密钥
  data: string;                        // base64 编码的待认证数据
}

// aesEncrypt/aesDecrypt 请求 payload (Kotlin NativeAesOps → action="aesEncrypt"/"aesDecrypt")
interface AesPayload {
  key: string;                         // base64 编码的 AES 密钥 (16/24/32 字节 → AES128/192/256)
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
  let req: CryptoBridgeRequest;
  try {
    req = JSON.parse(requestJson) as CryptoBridgeRequest;
  } catch (e) {
    console.error('[CryptoBridge] parse request failed: ' + requestJson);
    return;
  }

  // 异步处理, 完成后回调 (与 ImageBridgeHandler.ets 同模式)
  handleAction(req).then((resp: CryptoResponse) => {
    callback(req.requestId, JSON.stringify(resp));
  }).catch((err: Error) => {
    const resp: CryptoResponse = { ok: false, error: err.message ?? String(err) };
    callback(req.requestId, JSON.stringify(resp));
  });
}

// 根据 action 分发到具体处理函数
async function handleAction(req: CryptoBridgeRequest): Promise<CryptoResponse> {
  switch (req.action) {
    case 'encrypt': return await handleEncryptDecrypt(req.payload, true);
    case 'decrypt': return await handleEncryptDecrypt(req.payload, false);
    case 'sign': return await handleSign(req.payload);
    case 'verify': return await handleVerify(req.payload);
    case 'digest': return await handleDigest(req.payload);
    case 'hmac': return await handleHmac(req.payload);
    case 'aesEncrypt': return await handleAesEncryptDecrypt(req.payload, true);
    case 'aesDecrypt': return await handleAesEncryptDecrypt(req.payload, false);
    default: return { ok: false, error: 'unknown action: ' + req.action };
  }
}

// ===== encrypt/decrypt: cryptoFramework.createCipher + init + doFinal =====
async function handleEncryptDecrypt(payload: string, isEncrypt: boolean): Promise<CryptoResponse> {
  const p = JSON.parse(payload) as AsyCryptoPayload;
  // 公钥加密场景需 publicKey; 私钥解密场景需 privateKey; 私钥加密/公钥解密按 usePublicKey 取对应 key
  const keyB64 = p.usePublicKey ? p.publicKey : p.privateKey;
  if (keyB64 == null) {
    return { ok: false, error: (p.usePublicKey ? 'publicKey' : 'privateKey') + ' not set' };
  }
  const keyDer = base64ToUint8(keyB64);
  const dataBytes = base64ToUint8(p.data);

  // 探测 RSA keysize (加解密仅支持 RSA, ECC 无 Cipher)
  const keySize = await detectRsaKeySize(keyDer, !p.usePublicKey);
  // 映射 JCA transformation → cryptoFramework Cipher spec
  const spec = mapAsyCodecSpec(p.algorithm, keySize);

  // 创建 Cipher + 转换密钥 + init + doFinal (RSA 加解密用 createCipher, OHOS 无独立 AsyCodec API)
  const cipher = cryptoFramework.createCipher(spec);
  const keyPair = await convertKey('RSA' + keySize, keyDer, !p.usePublicKey);
  const key = p.usePublicKey ? keyPair.pubKey : keyPair.priKey;
  await initAsyCipher(cipher, isEncrypt, key);
  const resultBytes = await doFinalAsyCipher(cipher, dataBytes);

  return { ok: true, data: uint8ToBase64(resultBytes) };
}

// ===== sign: cryptoFramework.createSign + init + sign =====
async function handleSign(payload: string): Promise<CryptoResponse> {
  const p = JSON.parse(payload) as SignPayload;
  const keyDer = base64ToUint8(p.privateKey);
  const dataBytes = base64ToUint8(p.data);

  const algUpper = p.algorithm.toUpperCase();
  let spec: string;
  let keyPair: cryptoFramework.KeyPair;
  if (algUpper.includes('ECDSA')) {
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
}

// ===== verify: cryptoFramework.createVerify + init + verify =====
async function handleVerify(payload: string): Promise<CryptoResponse> {
  const p = JSON.parse(payload) as VerifyPayload;
  const keyDer = base64ToUint8(p.publicKey);
  const dataBytes = base64ToUint8(p.data);
  const sigBytes = base64ToUint8(p.signature);

  const algUpper = p.algorithm.toUpperCase();
  let spec: string;
  let keyPair: cryptoFramework.KeyPair;
  if (algUpper.includes('ECDSA')) {
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
}

// ===== digest: cryptoFramework.createMd + update + doFinal =====
// KMP NativeDigestOps.digest → action="digest"
async function handleDigest(payload: string): Promise<CryptoResponse> {
  const p = JSON.parse(payload) as DigestPayload;
  const dataBytes = base64ToUint8(p.data);

  // 归一化算法名: MD5/SHA-1/SHA1/SHA-256/SHA256 → MD5/SHA1/SHA256/SHA512 (OHOS createMd 无连字符)
  const algName = normalizeDigestAlg(p.algorithm);
  const md = cryptoFramework.createMd(algName);
  await updateMd(md, dataBytes);
  const resultBytes = await doFinalMd(md);
  return { ok: true, data: uint8ToBase64(resultBytes) };
}

// ===== hmac: cryptoFramework.createMac + SymKeyGenerator.convertKey + init + update + doFinal =====
// KMP NativeHmacOps.hmac → action="hmac"
async function handleHmac(payload: string): Promise<CryptoResponse> {
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
}

// ===== aesEncrypt/aesDecrypt: cryptoFramework.createCipher + SymKeyGenerator.convertKey + init + doFinal =====
// KMP NativeAesOps.encryptEcbPkcs7/decryptEcbPkcs7 → action="aesEncrypt"/"aesDecrypt"
async function handleAesEncryptDecrypt(payload: string, isEncrypt: boolean): Promise<CryptoResponse> {
  const p = JSON.parse(payload) as AesPayload;
  const keyBytes = base64ToUint8(p.key);
  const dataBytes = base64ToUint8(p.data);

  // AES key size 由 key 字节长度推断: 16→AES128, 24→AES192, 32→AES256
  const keySize = detectAesKeySize(keyBytes.length);
  // ECB + PKCS7 (PKCS7 在 AES 块大小 16 下与 PKCS5 等价, 对齐 KMP NativeAesOps)
  const cipherSpec = `AES${keySize}|ECB|PKCS7`;
  const keyGenSpec = `AES${keySize}`;

  const cipher = cryptoFramework.createCipher(cipherSpec);
  const symKey = await convertSymKey(keyGenSpec, keyBytes);
  // ECB 模式无 IV, params 传 null
  await initCipher(cipher, isEncrypt, symKey);
  const resultBytes = await doFinalCipher(cipher, dataBytes);
  return { ok: true, data: uint8ToBase64(resultBytes) };
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

// hmac: HmacMD5/HmacSHA256/HMAC-SHA512 等 → MD5/SHA1/SHA256/SHA512 (OHOS createMac algName 无连字符, 与 createMd 一致)
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

// AES key size 探测: 16→128, 24→192, 32→256 (对齐 KMP NativeAesOps key 字节长度推断)
function detectAesKeySize(keyByteLength: number): number {
  switch (keyByteLength) {
    case 16: return 128;
    case 24: return 192;
    case 32: return 256;
    default: throw new Error('invalid AES key length: ' + keyByteLength + ' (must be 16/24/32)');
  }
}

// ===== cryptoFramework 对称密钥/摘要/HMAC/Cipher 异步回调包装为 Promise =====

// 对称密钥转换 (AES/HMAC): SymKeyGenerator.convertKey(DataBlob) → SymKey
// 注意: 与非对称 convertKey(AsyKeyGenerator, KeyData) 不同, 对称用 DataBlob (无 type 字段)
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

// Md: update(data) + doFinal() (流式 API, doFinal 无 data 参数)
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

function doFinalMd(md: cryptoFramework.Md): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    md.doFinal((err: BusinessError, dataBlob: cryptoFramework.DataBlob) => {
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

// Cipher: init(mode, key, params=null) + doFinal(data) (ECB 模式无 IV, params 传 null)
function initCipher(cipher: cryptoFramework.Cipher, isEncrypt: boolean, key: cryptoFramework.SymKey): Promise<void> {
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

// ===== 密钥大小探测 (尝试常见 keysize, 首个 convertKey 成功的即为实际大小) =====

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
    const keyData: cryptoFramework.KeyData = {
      type: isPrivate ? 'PKCS#8' : 'X.509',
      data: der
    };
    gen.convertKey(keyData, (err: BusinessError, keyPair: cryptoFramework.KeyPair) => {
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

function initSign(signer: cryptoFramework.Sign, key: cryptoFramework.Key): Promise<void> {
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

function initVerify(verifier: cryptoFramework.Verify, key: cryptoFramework.Key): Promise<void> {
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

// encrypt/decrypt: JCA transformation → cryptoFramework Cipher spec
// spec 格式参考 OHOS 官方文档 (无 ECB, OAEP 用 PKCS1_OAEP|<md>|MGF1_<md>):
//   RSA2048|PKCS1 / RSA2048|PKCS1_OAEP|SHA256|MGF1_SHA256 / RSA2048|NoPadding
function mapAsyCodecSpec(jcaAlg: string, keySize: number): string {
  const alg = jcaAlg.toUpperCase();
  // "RSA" / "RSA/ECB/PKCS1Padding" → PKCS1 (JCA "RSA" 是 PKCS1Padding 的别名, 与 hutool/BC 一致)
  if (alg === 'RSA' || alg === 'RSA/ECB/PKCS1PADDING') {
    return `RSA${keySize}|PKCS1`;
  }
  // "RSA/ECB/OAEPWithSHA-<N>AndMGF1Padding" → PKCS1_OAEP, md 与 mgf1Md 都用 SHA<N> (对齐 BC 默认)
  const oaepMatch = alg.match(/^RSA\/ECB\/OAEPWithSHA-(\d+)AndMGF1PADDING$/);
  if (oaepMatch) {
    const md = 'SHA' + oaepMatch[1];
    return `RSA${keySize}|PKCS1_OAEP|${md}|MGF1_${md}`;
  }
  // "RSA/ECB/NoPadding" → NoPadding (cryptoFramework 部分版本不支持, 错误由 ArkTS 透传)
  if (alg === 'RSA/ECB/NOPADDING') {
    return `RSA${keySize}|NoPadding`;
  }
  throw new Error('unsupported encrypt/decrypt algorithm: ' + jcaAlg);
}

// sign/verify: JCA Signature algorithm → Sign/Verify algName
function mapSignSpec(jcaAlg: string, keySize: number, isEc: boolean): string {
  const alg = jcaAlg.toUpperCase();
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

// ===== base64 / Uint8Array 互转工具 (与 ImageBridgeHandler.ets 同模式, 用 @ohos.buffer) =====

function base64ToUint8(b64: string): Uint8Array {
  const buf = buffer.from(b64, 'base64');
  // 复制到独立 ArrayBuffer, 避免 buffer 视图偏移影响 cryptoFramework 读取
  const ab = new ArrayBuffer(buf.byteLength);
  const view = new Uint8Array(ab);
  view.set(new Uint8Array(buf.buffer, 0, buf.byteLength));
  return view;
}

function uint8ToBase64(bytes: Uint8Array): string {
  // 复制到独立 ArrayBuffer, 避免 Uint8Array 视图偏移 (doFinal/sign 返回的可能是大 buffer 的子视图)
  const ab = new ArrayBuffer(bytes.byteLength);
  const view = new Uint8Array(ab);
  view.set(bytes);
  return buffer.from(ab).toString('base64');
}
