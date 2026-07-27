// legado_napi module ArkTS 类型声明
// 在 ArkTS 侧用 `import legado from 'liblegado_napi.so'` 引入后调用
//
// 函数列表 (与 legado_napi.cpp + LegadoNativeExports.kt @CName 一一对应):
//
// 工具类 (KP4 已存在):
// - chineseT2S(text: string): string        繁体 → 简体
// - chineseS2T(text: string): string        简体 → 繁体
// - md5Encode(text: string): string          MD5 摘要 (32 字符 hex)
// - formatPercentUs(value: number): string  百分比格式化 (US locale)
// - isProvidersRegistered(): boolean        KMP provider 是否已注册
// - registerOhosProviders(): void           显式触发 provider 注册
//
// 业务类 (KP5 新增, 接入真实 KMP 数据):
// - bookshelfList(): string                  返回书架书籍 JSON 数组
// - searchBook(query: string): string        返回书架内搜索结果 JSON 数组
// - loadChapter(bookUrl: string, chapterIndex: number): string
//                                            返回章节正文文本 (缓存未命中时返回空字符串)
// - chapterList(bookUrl: string): string     返回章节目录 JSON 数组 (仅含 index/title/url 字段)
// - importBookSource(json: string): number   导入书源 JSON 数组, 返回导入数量
//
// 同步语义注意: 业务类函数内部用 runBlocking 转 suspend, 应在 TaskPool/Worker 中调用

export interface LegadoNativeBridge {
  // ===== 工具类 (KP4) =====
  chineseT2S(text: string): string;
  chineseS2T(text: string): string;
  md5Encode(text: string): string;
  formatPercentUs(value: number): string;
  isProvidersRegistered(): boolean;
  registerOhosProviders(): void;

  // ===== 业务类 (KP5 新增) =====
  /**
   * 获取书架书籍列表。
   * @return JSON 数组字符串, 形如 [{"bookUrl":"...","name":"...","author":"...",...}, ...]
   *         异常时返回 "[]" (空数组)
   */
  bookshelfList(): string;

  /**
   * 在书架内搜索书籍 (按 name/author/originName/kind/intro 模糊匹配)。
   * @param query 搜索关键词
   * @return JSON 数组字符串, 形如 [{"bookUrl":"...","name":"...",...}, ...]
   *         异常时返回 "[]" (空数组)
   */
  searchBook(query: string): string;

  /**
   * 加载章节内容 (仅读本地缓存, 不联网拉取)。
   * @param bookUrl 书籍 URL (与 Book.bookUrl 一致)
   * @param chapterIndex 章节索引 (0-based)
   * @return 章节正文文本; 缓存未命中或异常时返回空字符串 ""
   */
  loadChapter(bookUrl: string, chapterIndex: number): string;

  /**
   * 获取章节目录列表 (返回 JSON 数组字符串)。
   * @param bookUrl 书籍 URL (与 Book.bookUrl 一致)
   * @return JSON 数组字符串, 形如 [{"index":0,"title":"...","url":"..."}, ...]
   *         异常时返回 "[]" (空数组)
   */
  chapterList(bookUrl: string): string;

  /**
   * 加载漫画章节图片 URL 列表 (对应 Android 端 BookHelp.flowImages)。
   *
   * 内部复用 BookStorageProviders.getContent 获取正文 + MangaImageExtractor.flowImages
   * 提取图片 URL (与 shared MangaReaderViewModelShared.getManageChapter 一致),
   * 返回 ArkTS 侧渲染所需的图片 URL 数组。
   *
   * @param bookUrl 书籍 URL (与 Book.bookUrl 一致)
   * @param chapterIndex 章节索引 (0-based)
   * @return JSON 字符串, 形如 {"images":["url1","url2",...]}
   *         异常或无图片时返回 "{\"images\":[]}"
   */
  loadMangaChapter(bookUrl: string, chapterIndex: number): string;

  /**
   * 导入书源 (JSON 数组格式)。
   * @param json 书源 JSON 数组字符串, 形如 [{"bookSourceUrl":"...","bookSourceName":"...",...}, ...]
   * @return 导入数量; 异常或空数组时返回 0
   */
  importBookSource(json: string): number;

  // ===== FileDir / CacheDir 路径注入 (KP7+ 新增) =====
  /**
   * 注入鸿蒙应用沙盒 filesDir 路径 (EntryAbility.onCreate 调用, 须在 registerOhosProviders 之前)。
   * @param path filesDir 绝对路径, 如 context.filesDir
   */
  registerFileDir(path: string): void;

  /**
   * 注入鸿蒙应用沙盒 cacheDir 路径。
   * @param path cacheDir 绝对路径, 如 context.cacheDir
   */
  registerCacheDir(path: string): void;

  // ===== legado:// deep link 投递 (ArkTS → Kotlin 同步推送) =====
  /**
   * 投递 legado:// / yuedu:// 一键导入链接 (EntryAbility.onCreate / onNewWant 调用)。
   *
   * Kotlin 侧 LegadoDeepLinkHandler 解析 URL 后写入 pending (StateFlow),
   * Compose 侧 DeepLinkImportHost 消费并弹勾选导入对话框 (书源/替换规则/目录规则/字典/语音/主题)。
   * 冷启动 (onCreate) 先于 UI 组合投递也不丢。
   *
   * @param uri deep link URL, 如 legado://import/bookSource?src=https://...
   * @return true=已识别并记录待导入; false=非 legado/yuedu scheme 或缺 src 参数 (调用方可自行处理)
   */
  handleDeepLink(uri: string): boolean;

  // ===== Toast / Notification tsfn 回调注册 (KP7+ 新增, KMP → ArkTS 跨线程 dispatch) =====
  /**
   * 注册 Toast 回调 (KMP → ArkTS 跨线程 dispatch)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_toast_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.toastTsfn。此后 KMP 调用
   * OhosNativeBridge.showToast 时, JSON payload 跨线程 dispatch 到此 [callback]
   * 执行 promptAction.showToast (KMP 无 ArkTS API 访问能力, 需 tsfn 桥接)。
   *
   * @param callback 接收 JSON payload `{ message: string, durationMs: number }`,
   *                 内部调 promptAction.showToast
   */
  registerToastCallback(callback: (json: string) => void): void;

  /**
   * 注册 Notification 回调 (KMP → ArkTS 跨线程 dispatch)。
   *
   * 同 registerToastCallback, 注入到 OhosNativeBridge.notificationTsfn,
   * 使 KMP OhosNativeBridge.showNotification / cancelNotification 跨线程 dispatch 到此 [callback]。
   *
   * @param callback 接收 JSON payload:
   *   - SHOW:    `{ action: 'SHOW', id, title, content, progress, max }`
   *   - CANCEL:  `{ action: 'CANCEL', id }`
   *   内部分支调 notificationManager.publish / notificationManager.cancel
   */
  registerNotificationCallback(callback: (json: string) => void): void;

  // ===== Image / Media tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增) =====

  /**
   * 注册 Image 回调 (KMP → ArkTS 跨线程 dispatch, 图片操作请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_image_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.imageTsfn。此后 KMP 调用
   * OhosNativeBridge.invokeImageSync 时, JSON 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.multimedia.image (createImageSource/createPixelMap/createImagePacker 等)。
   * 处理完成后通过 [imageCallback] 回送结果给 Kotlin。
   *
   * @param callback 接收 JSON 请求 `{ requestId, action, payload }`:
   *   - action='decode':  payload=`{ bytes: '<base64>' }` → 创建 PixelMap, 回送 `{ ok, pixelMapId }`
   *   - action='encode':  payload=`{ pixelMapId, format, quality }` → packing, 回送 `{ ok, data: '<base64>' }`
   *   - action='size':    payload=`{ pixelMapId }` → getImageInfo, 回送 `{ ok, width, height }`
   *   - action='crop':    payload=`{ pixelMapId, x, y, w, h }` → crop, 回送 `{ ok, pixelMapId }`
   *   - action='split':   payload=`{ pixelMapId, rows, cols }` → 循环 crop, 回送 `{ ok, pixelMapIds: [] }`
   *   - action='stitch':  payload=`{ pixelMapIds: [], direction }` → 合并, 回送 `{ ok, pixelMapId }`
   *   - action='release': payload=`{ pixelMapId }` → 释放 PixelMap, 回送 `{ ok }`
   */
  registerImageCallback(callback: (json: string) => void): void;

  /**
   * 注册 Media 回调 (KMP → ArkTS 跨线程 dispatch, AVPlayer 命令)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_media_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.mediaTsfn。此后 KMP 调用
   * OhosNativeBridge.sendMediaCommand 时, JSON 命令跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.multimedia.media AVPlayer。AVPlayer 事件通过 [mediaEvent] 回送。
   *
   * @param callback 接收 JSON 命令 `{ playerId?, action, path?, url?, headers?, position?, speed? }`:
   *   - playerId:            播放实例 id ("audioBook"/"httpTts"/"video"), 缺省 = "default"
   *   - action='setSource':    path=本地文件路径 → 创建 AVPlayer + fd:// 设源 + prepare
   *   - action='setSourceUrl': url=网络流地址, headers=请求头 → createMediaSourceWithUrl + setMediaSource + prepare
   *   - action='play':      AVPlayer.play()
   *   - action='pause':     AVPlayer.pause()
   *   - action='stop':      AVPlayer.stop()
   *   - action='seekTo':    position=毫秒 → AVPlayer.seek()
   *   - action='setSpeed':  speed=浮点倍速 → AVPlayer.setSpeed(枚举档位)
   *   - action='release':   释放 AVPlayer
   */
  registerMediaCallback(callback: (json: string) => void): void;

  /**
   * Image 操作结果回调 (ArkTS → Kotlin)。
   *
   * ArkTS 侧处理完 decode/encode/size/crop/split/stitch 后, 通过此方法把结果 JSON
   * 回送给 Kotlin, 唤醒 OhosNativeBridge.invokeImageSync 中阻塞的 CompletableDeferred。
   *
   * @param requestId 请求 ID (与 invokeImageSync 生成的 requestId 对应)
   * @param result 结果 JSON, 如 `{ ok: true, pixelMapId: 1 }` 或 `{ ok: false, error: '...' }`
   */
  imageCallback(requestId: number, result: string): void;

  /**
   * Media 事件回调 (ArkTS → Kotlin)。
   *
   * ArkTS AVPlayer 状态变化 / 播放结束 / 错误 / 缓冲进度等事件通过此方法推送给 Kotlin,
   * 由 OhosNativeBridge 按 playerId 转发给对应的 MediaEventListener
   * (OhosHttpTtsPlayer / OhosAudioPlayCommander)。
   *
   * @param event 事件 JSON (必带 playerId), 如:
   *   `{ playerId: 'httpTts', event: 'onReady' }` / `{ playerId: 'audioBook', event: 'onEndOfMedia' }` /
   *   `{ playerId: '...', event: 'onError', message: '...' }` / `{ ..., event: 'onBufferingUpdate', percent: 50 }` /
   *   `{ ..., event: 'onDuration', duration: 30000 }` / `{ ..., event: 'onPosition', position: 5000 }` /
   *   `{ ..., event: 'onPlaying' }` / `{ ..., event: 'onPaused' }`
   */
  mediaEvent(event: string): void;

  // ===== TTS tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增) =====

  /**
   * 注册 TTS 回调 (KMP → ArkTS 跨线程 dispatch, TTS 命令)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_tts_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.ttsTsfn。此后 KMP 调用
   * OhosNativeBridge.sendTtsCommand 时, JSON 命令跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.textToSpeech。TTS 事件通过 [ttsEvent] 回送。
   *
   * @param callback 接收 JSON 命令 `{ action, text?, utteranceId?, rate?, lang? }`:
   *   - action='createEngine': 创建 TTS 引擎
   *   - action='speak':        text=播报文本, utteranceId, rate, lang → 朗读
   *   - action='pause':        暂停
   *   - action='resume':       恢复
   *   - action='stop':         停止
   *   - action='shutdown':     释放引擎
   */
  registerTtsCallback(callback: (json: string) => void): void;

  /**
   * TTS 事件回调 (ArkTS → Kotlin)。
   *
   * ArkTS @ohos.textToSpeech 的 onStart/onComplete/onStop/onError 事件通过此方法推送给 Kotlin,
   * 转发给 OhosNativeBridge.TtsEventListener (即 OhosSystemTtsEngine)。
   *
   * @param event 事件 JSON, 如 `{ event: 'onStart', utteranceId: 'xxx' }`
   */
  ttsEvent(event: string): void;

  // ===== Crypto tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image 模式) =====

  /**
   * 注册 Crypto 回调 (KMP → ArkTS 跨线程 dispatch, crypto 操作请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_crypto_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.cryptoTsfn。此后 KMP 调用
   * OhosNativeBridge.invokeCryptoSync 时, JSON 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.security.cryptoFramework (createAsyCodec/createSign/createVerify)。
   * 处理完成后通过 [cryptoCallback] 回送结果给 Kotlin。
   *
   * @param callback 接收 JSON 请求 `{ requestId, action, payload }`:
   *   - action='encrypt': payload=`{ algorithm, usePublicKey, privateKey?, publicKey?, data }` (base64)
   *   - action='decrypt': 同 encrypt
   *   - action='sign':    payload=`{ algorithm, privateKey, data }` (base64)
   *   - action='verify':   payload=`{ algorithm, publicKey, data, signature }` (base64)
   */
  registerCryptoCallback(callback: (json: string) => void): void;

  /**
   * Crypto 操作结果回调 (ArkTS → Kotlin)。
   *
   * ArkTS 侧处理完 encrypt/decrypt/sign/verify 后, 通过此方法把结果 JSON
   * 回送给 Kotlin, 唤醒 OhosNativeBridge.invokeCryptoSync 中阻塞的 CompletableDeferred。
   *
   * @param requestId 请求 ID (与 invokeCryptoSync 生成的 requestId 对应)
   * @param result 结果 JSON:
   *   - encrypt/decrypt/sign 成功: `{ ok: true, data: '<base64>' }`
   *   - verify 成功: `{ ok: true, result: true/false }`
   *   - 失败: `{ ok: false, error: '...' }`
   */
  cryptoCallback(requestId: number, result: string): void;

  // ===== Http tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto 模式) =====

  /**
   * 注册 Http 回调 (KMP → ArkTS 跨线程 dispatch, HTTP 请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_http_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.httpTsfn。此后 KMP 调用
   * OhosNativeBridge.invokeHttpSync 时, JSON 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.net.http (http.createHttp().request)。
   * 处理完成后通过 [httpCallback] 回送结果给 Kotlin。
   *
   * @param callback 接收 JSON 请求 `{ requestId, action, payload }`:
   *   - action='execute': payload=`{ url, method, headers, body?, contentType?, timeoutMs }` (body base64)
   *                       → http.createHttp().request(url, options, cb), 回送 HttpResponsePayload
   *   - action='cancel':  payload=空 → 销毁对应 httpRequest, 回送 `{ ok: true }`
   */
  registerHttpCallback(callback: (json: string) => void): void;

  /**
   * Http 请求结果回调 (ArkTS → Kotlin)。
   *
   * ArkTS 侧处理完 execute/cancel 后, 通过此方法把结果 JSON
   * 回送给 Kotlin, 唤醒 OhosNativeBridge.invokeHttpSync 中阻塞的 CompletableDeferred。
   *
   * @param requestId 请求 ID (与 invokeHttpSync 生成的 requestId 对应)
   * @param result 结果 JSON:
   *   - execute 成功: `{ ok: true, code: <int>, message: '<string>', headers: [...], body: '<base64>' }`
   *   - 失败: `{ ok: false, error: '<string>' }`
   *   - cancel: `{ ok: true }`
   */
  httpCallback(requestId: number, result: string): void;

  // ===== OpenUrl tsfn 回调注册 (KP8+ 新增, 同 Toast 模式, fire-and-forget dispatch) =====

  /**
   * 注册 OpenUrl 回调 (KMP → ArkTS 跨线程 dispatch, 打开 URL)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_open_url_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.openUrlTsfn。此后 KMP 调用
   * OhosNativeBridge.openUrl 时, JSON payload 跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 context.startAbility(Want) 打开 URL (KMP 无 ArkTS API 访问能力, 需 tsfn 桥接)。
   *
   * 与 registerToastCallback 同模式: fire-and-forget, 无需 ArkTS → Kotlin 结果回调
   * (openUrl 无返回值, 与 toast 同样不注册 openUrlCallback)。
   *
   * @param callback 接收 JSON payload `{ url, mimeType?, sourceKey?, sourceTag?, sourceType }`,
   *                 内部调 context.startAbility({ uri: url, action: 'ohos.want.action.viewData', type: mimeType? })
   */
  registerOpenUrlCallback(callback: (json: string) => void): void;

  // ===== FilePicker tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增, 同 Image/Crypto/Http 模式) =====

  /**
   * 注册 FilePicker 回调 (KMP → ArkTS 跨线程 dispatch, 文件选择请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_file_picker_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.filePickerTsfn。此后 KMP 调用
   * OhosNativeBridge.invokeFilePickerSync 时, JSON 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.file.picker.DocumentViewPicker (选文件) / @ohos.file.fs (读内容)。
   * 处理完成后通过 [filePickerCallback] 回送结果给 Kotlin。
   *
   * @param callback 接收 JSON 请求 `{ requestId, action, payload }`:
   *   - action='pickDocuments':     payload=`{ contentTypes: ['public.json', ...], allowsMultiple }` → 选文件, 回送 `{ ok, uris }` 或 `{ ok, cancelled: true }`
   *   - action='pickDocumentContent': payload=`{ uri }` → 读字节, 回送 `{ ok, data: '<base64>' }`
   */
  registerFilePickerCallback(callback: (json: string) => void): void;

  /**
   * FilePicker 操作结果回调 (ArkTS → Kotlin)。
   *
   * ArkTS 侧处理完 pickDocuments/pickDocumentContent 后, 通过此方法把结果 JSON
   * 回送给 Kotlin, 唤醒 OhosNativeBridge.invokeFilePickerSync 中阻塞的 CompletableDeferred。
   *
   * @param requestId 请求 ID (与 invokeFilePickerSync 生成的 requestId 对应)
   * @param result 结果 JSON:
   *   - pickDocuments 成功: `{ ok: true, uris: ['<uri1>', '<uri2>', ...] }`
   *   - pickDocuments 用户取消: `{ ok: true, cancelled: true }`
   *   - pickDocumentContent 成功: `{ ok: true, data: '<base64>' }`
   *   - 失败: `{ ok: false, error: '<string>' }`
   */
  filePickerCallback(requestId: number, result: string): void;

  // ===== Pasteboard tsfn 回调注册 + ArkTS → Kotlin 回调 (同 FilePicker 模式) =====

  /**
   * 注册 Pasteboard 回调 (KMP → ArkTS 跨线程 dispatch, 剪贴板读写请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_pasteboard_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.pasteboardTsfn。此后 KMP 调用
   * OhosNativeBridge.invokePasteboardSync 时, JSON 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.pasteboard.getSystemPasteboard() getData/setData。
   * 处理完成后通过 [pasteboardCallback] 回送结果给 Kotlin。
   *
   * @param callback 接收 JSON 请求 `{ requestId, action, payload }`:
   *   - action='read':  payload=`{}` → getData().getPrimaryText(), 回送 `{ ok: true, text: '...' }`
   *   - action='write': payload=`{ text }` → createData(MIMETYPE_TEXT_PLAIN).setData(), 回送 `{ ok: true }`
   */
  registerPasteboardCallback(callback: (json: string) => void): void;

  /**
   * Pasteboard 操作结果回调 (ArkTS → Kotlin)。
   *
   * @param requestId 请求 ID (与 invokePasteboardSync 生成的 requestId 对应)
   * @param result 结果 JSON:
   *   - read 成功:  `{ ok: true, text: '<剪贴板纯文本, 空剪贴板为空串>' }`
   *   - write 成功: `{ ok: true }`
   *   - 失败:       `{ ok: false, error: '<string>' }`
   */
  pasteboardCallback(requestId: number, result: string): void;

  // ===== TextCodec tsfn 回调注册 + ArkTS → Kotlin 回调 (同 Pasteboard 模式, decode/encode) =====

  /**
   * 注册 TextCodec 回调 (KMP → ArkTS 跨线程 dispatch, 文本编解码请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_text_codec_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.textCodecTsfn。此后 KMP 调用
   * OhosNativeBridge.invokeTextCodecSync 时, JSON 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.util.TextDecoder/TextEncoder 完成 GB18030/Big5 等非 UTF-8 字符集编解码
   * (TXT 分章场景, Kotlin/Native 侧无原生 ICU 转换能力)。处理完成后通过 [textCodecCallback] 回送结果给 Kotlin。
   *
   * @param callback 接收 JSON 请求 `{ requestId, action, payload }`:
   *   - action='decode': payload=`{ charset: 'gb18030'|'big5'|..., data: '<base64>' }` → 回送 `{ ok, text }`
   *   - action='encode': payload=`{ charset: 'gb18030'|'big5'|..., text: '<string>' }` → 回送 `{ ok, data: '<base64>' }`
   */
  registerTextCodecCallback(callback: (json: string) => void): void;

  /**
   * TextCodec 操作结果回调 (ArkTS → Kotlin)。
   *
   * ArkTS 侧处理完 decode/encode 后, 通过此方法把结果 JSON
   * 回送给 Kotlin, 唤醒 OhosNativeBridge.invokeTextCodecSync 中阻塞的 CompletableDeferred。
   *
   * @param requestId 请求 ID (与 invokeTextCodecSync 生成的 requestId 对应)
   * @param result 结果 JSON:
   *   - decode 成功: `{ ok: true, text: '<解码后的字符串>' }`
   *   - encode 成功: `{ ok: true, data: '<base64>' }`
   *   - 失败:       `{ ok: false, error: '<string>' }`
   */
  textCodecCallback(requestId: number, result: string): void;

  // ===== 发现页 (DiscoverTab) 桥接函数 =====
  // C++ 侧已实现 (KP5+):
  // - exploreList: 返回 enabledExplore=true 且 hasExploreUrl=1 的 BookSourcePart JSON 数组
  // - topExploreSource/deleteExploreSource: 调 ExploreViewModelShared.topSource/deleteSource (经 getBookSourcePart 取 part)
  // - openExplore/editExploreSource: stub no-op (页面跳转属 ArkTS 路由范畴, 应由 ArkTS 端直接 router.pushUrl)

  /**
   * 获取发现源列表 (仅 enabledExplore=true 的 BookSource)。
   * @return JSON 数组字符串, 形如 [{"bookSourceUrl":"...","bookSourceName":"...",...}, ...]
   *         桥接未实现时抛异常, 由调用方 try/catch 降级为空数组
   */
  exploreList(): string;

  /**
   * 打开发现源详情/书籍列表 (跳转 ExploreShow)。
   * @param sourceUrl 书源 URL
   * @param exploreUrl 发现分类 URL (可为空)
   */
  openExplore(sourceUrl: string, exploreUrl: string): void;

  /**
   * 编辑书源 (跳转 BookSourceEdit)。
   * @param sourceUrl 书源 URL
   */
  editExploreSource(sourceUrl: string): void;

  /**
   * 置顶书源 (调 ExploreViewModelShared.topSource)。
   * @param sourceUrl 书源 URL
   */
  topExploreSource(sourceUrl: string): void;

  /**
   * 删除书源 (调 ExploreViewModelShared.deleteSource)。
   * @param sourceUrl 书源 URL
   */
  deleteExploreSource(sourceUrl: string): void;
}

declare const legado: LegadoNativeBridge;
export default legado;
