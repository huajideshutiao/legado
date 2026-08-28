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

// CPF 渲染控制器接口 (与 @cpf-kmp-cmp/compose 的 libcompose_arkui_utils.so 声明结构一致)。
// 本文件是 .d.ts, ArkTS 禁止 TS 文件 import .ets/.d.ets, 而 @cpf-kmp-cmp/compose 入口是 Index.d.ets,
// 其 libcompose_arkui_utils.so 又只在该包自身 oh_modules 内可解析, 故就地声明同形接口 (结构类型兼容)。
export interface ArkUIViewController {
  onPageShow(): void;

  onPageHide(): void;

  onSurfaceShow(): void;

  onSurfaceHide(): void;

  onBackPress(): boolean;

  setParentScrollBridge(scroller: Object): void;

  /** 外层可滚动容器 onScrollFrameBegin 同步钩子, 返回 Compose 消费的 vp 偏移。 */
  consumeOuterScroll(offsetVp: number, sourceInt: number): number;
}

export interface LegadoNativeBridge {
  /** 创建由 CPF 融合渲染承载的 Legado Compose 根控制器。 */
  MainArkUIViewController(): ArkUIViewController;

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

  // ===== 屏幕尺寸注入 (ArkTS → Kotlin 同步推送, 同 FileDir 模式) =====
  /**
   * 注入显示物理像素尺寸 (EntryAbility.onWindowStageCreate 中 loadContent 之前调用)。
   *
   * 供 shared ScreenInfoProviders / AppDialogSizes 计算对话框尺寸;
   * 未注册时 AppDialogSizes 兜底 get() 直接 error 导致所有 shared 对话框崩溃。
   * @param widthPx 显示物理像素宽度 (vp 尺寸 × densityPixels)
   * @param heightPx 显示物理像素高度
   */
  registerScreenSize(widthPx: number, heightPx: number): void;

  // ===== legado:// deep link 投递 (ArkTS → Kotlin 同步推送) =====
  /**
   * legado:// deep link 投递 (ArkTS → Kotlin 同步推送)。
   *
   * Kotlin 侧 LegadoDeepLinkHandler 解析 URL 后写入 pending (StateFlow),
   * Compose 侧 DeepLinkImportHost 消费并弹勾选导入对话框 (书源/替换规则/目录规则/字典/语音/主题)。
   * 冷启动 (onCreate) 先于 UI 组合投递也不丢。
   *
   * @param uri deep link URL, 如 legado://import/bookSource?src=https://...
   * @return true=已识别并记录待导入; false=非 legado/yuedu scheme 或缺 src 参数 (调用方可自行处理)
   */
  handleDeepLink(uri: string): boolean;

  /**
   * 外部启动请求投递 (ArkTS → Kotlin 同步推送, 文件关联 / 通知 route / 其他 scheme deep link)。
   *
   * Kotlin 侧 OhosLaunchRequests.parse 把 Want.uri 映射为 LaunchRequest (ImportFile /
   * DeepLink / NavigateTo 等) 并经 LaunchRequestBus.dispatch 投递, Compose 侧 LegadoApp 消费
   * 路由导航。与 [handleDeepLink] 的分工: 后者只认 legado:// / yuedu:// 导入链接并弹勾选对话框;
   * 本函数覆盖完整的 Want → LaunchRequest 映射 (文件关联打开 txt/epub 等、通知点击 route:xxx)。
   *
   * @param uri Want.uri (UTF-8 字符串); 通知点击可传 `route:<AppRoute 序列化串>`
   * @return true=已识别并投递到 LaunchRequestBus; false=无法识别 (ArkTS 可自行处理)
   */
  handleLaunchRequest(uri: string): boolean;

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

  // ===== Window tsfn 回调注册 (KMP → ArkTS 窗口策略命令) =====
  /**
   * 注册 Window 回调 (KMP → ArkTS 跨线程 dispatch, 窗口策略命令)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_window_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.windowTsfn。此后 KMP WindowController
   * 的命令跨线程 dispatch 到此 [callback], 由 ArkTS 在窗口上执行 @ohos.window API。
   * fire-and-forget, 无 ArkTS → Kotlin 结果回调。
   *
   * @param callback 接收 JSON 命令 `{ action: 'setFullScreenLayout'|'setKeepScreenOn'|
   *                 'setPreferredOrientation'|'setSystemBarEnable'|'exitApplication',
   *                 enabled?: boolean, orientation?: number }`
   */
  registerWindowCallback(callback: (json: string) => void): void;

  // ===== TextAction tsfn 回调注册 + ArkTS → Kotlin 回调 (长按选字浮动菜单) =====
  /**
   * 注册 TextAction 回调 (KMP → ArkTS 跨线程 dispatch, 文本/图片操作菜单请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_text_action_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.textActionTsfn。此后 KMP 长按选字完成 →
   * OhosNativeBridge.showTextActionMenu 时, JSON payload 跨线程 dispatch 到此 [callback],
   * 由 ArkTS TextActionBridgeHandler 更新 Index.ets 叠层浮动菜单。
   *
   * @param callback 接收 JSON payload `{ text, x, y, src?, type, menuItems }`
   */
  registerTextActionCallback(callback: (json: string) => void): void;

  /**
   * TextAction 菜单结果回调 (ArkTS → Kotlin)。
   *
   * ArkTS 菜单项点击 / 点遮罩收起后调用, C++ 转发 @CName legado_text_action_callback →
   * KMP OhosNativeBridge.onTextActionResult。
   *
   * @param requestId 请求 ID (菜单为单例通道, 固定传 0)
   * @param result 结果 JSON, 如 `{ action: 'copy'|'__dismiss'|..., text: '...', src: '...' }`
   */
  textActionCallback(requestId: number, result: string): void;

  // ===== Image / Media tsfn 回调注册 + ArkTS → Kotlin 回调 (KP8+ 新增) =====

  /**
   * 注册 Image 回调 (KMP → ArkTS 跨线程 dispatch, 图片操作请求, 混合协议)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_image_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.imageTsfn。此后 KMP 调用
   * OhosNativeBridge.invokeImageSync 时, JSON 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.multimedia.image (createImageSource/createPixelMap/createImagePacker 等)。
   * 处理完成后通过 [imageCallback] 回送结果给 Kotlin。
   *
   * # 混合协议 (KP9+, 同 WebView 桥思路)
   * 大字节面走裸参数: decode 的图片字节由 C++ 用 napi external ArrayBuffer 零拷贝包装,
   * 作为第二参数 [bytes] 传入 (其余 action 为 undefined); 控制面小字段留在 JSON。
   *
   * @param callback 接收 (json, bytes?): json 为请求 JSON `{ requestId, action, payload }`:
   *   - action='decode':  bytes=图片字节 ArrayBuffer → 创建 PixelMap, 回送 `{ ok, pixelMapId }`
   *   - action='encode':  payload=`{ pixelMapId, format, quality }` → packing, 回送 `{ ok }` + packed 字节 (imageCallback 第三参)
   *   - action='size':    payload=`{ pixelMapId }` → getImageInfo, 回送 `{ ok, width, height }`
   *   - action='crop':    payload=`{ pixelMapId, x, y, w, h }` → crop, 回送 `{ ok, pixelMapId }`
   *   - action='split':   payload=`{ pixelMapId, rows, cols }` → 循环 crop, 回送 `{ ok, pixelMapIds: [] }`
   *   - action='stitch':  payload=`{ pixelMapIds: [], direction }` → 合并, 回送 `{ ok, pixelMapId }`
   *   - action='release': payload=`{ pixelMapId }` → 释放 PixelMap, 回送 `{ ok }`
   */
  registerImageCallback(callback: (json: string, bytes: ArrayBuffer | undefined) => void): void;

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
   * Image 操作结果回调 (ArkTS → Kotlin, 混合协议)。
   *
   * ArkTS 侧处理完 decode/encode/size/crop/split/stitch 后, 通过此方法把结果
   * 回送给 Kotlin, 唤醒 OhosNativeBridge.invokeImageSync 中阻塞的 CompletableDeferred。
   *
   * @param requestId 请求 ID (与 invokeImageSync 生成的 requestId 对应)
   * @param result 控制面结果 JSON, 如 `{ ok: true, pixelMapId: 1 }` 或 `{ ok: false, error: '...' }`
   *   (encode 成功为 `{ ok: true }`, packed 字节走 [body] 裸参)
   * @param body 数据面裸字节 (encode 的 packed 图片; 无字节面时可省略)
   */
  imageCallback(requestId: number, result: string, body?: ArrayBuffer): void;

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
   * 由 ArkTS 调 @kit.CoreSpeechKit textToSpeech。TTS 事件通过 [ttsEvent] 回送。
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
   * ArkTS @kit.CoreSpeechKit textToSpeech 的 onStart/onComplete/onStop/onError 事件通过此方法推送给 Kotlin,
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
   * 注册 Http 回调 (KMP → ArkTS 跨线程 dispatch, HTTP 请求, 混合协议)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_http_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.httpTsfn。此后 KMP 调用
   * OhosNativeBridge.invokeHttpSync 时, JSON 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.net.http (http.createHttp().request)。
   * 处理完成后通过 [httpCallback] 回送结果给 Kotlin。
   *
   * # 混合协议 (KP9+, 同 WebView 桥思路)
   * 请求 body 字节由 C++ 用 napi external ArrayBuffer 零拷贝包装, 作为第二参数 [body] 传入
   * (无 body 时为 undefined); 控制面小字段 (url/method/headers/超时/代理) 留在 JSON。
   *
   * @param callback 接收 (json, body?): json 为请求 JSON `{ requestId, action, payload }`:
   *   - action='execute': payload=`{ url, method, headers, contentType?, timeoutMs }` (不含 body)
   *                       + body=请求体 ArrayBuffer → http.createHttp().request(url, options, cb),
   *                       回送 HttpResponsePayload (控制面) + 响应 body (httpCallback 第三参)
   *   - action='cancel':  payload=空 → 销毁对应 httpRequest, 回送 `{ ok: true }`
   */
  registerHttpCallback(callback: (json: string, body: ArrayBuffer | undefined) => void): void;

  /**
   * Http 请求结果回调 (ArkTS → Kotlin, 混合协议)。
   *
   * ArkTS 侧处理完 execute/cancel 后, 通过此方法把结果
   * 回送给 Kotlin, 唤醒 OhosNativeBridge.invokeHttpSync 中阻塞的 CompletableDeferred。
   *
   * @param requestId 请求 ID (与 invokeHttpSync 生成的 requestId 对应)
   * @param result 控制面结果 JSON:
   *   - execute 成功: `{ ok: true, code: <int>, message: '<string>', headers: [...] }`
   *     (响应 body 走 [body] 裸参, 不经 base64)
   *   - 失败: `{ ok: false, error: '<string>' }`
   *   - cancel: `{ ok: true }`
   * @param body 数据面响应 body 裸字节 (二进制保真; 无 body 时可省略)
   */
  httpCallback(requestId: number, result: string, body?: ArrayBuffer): void;

  /**
   * 统一平台事件推送 (ArkTS → Kotlin 单向, 单通道复用)。
   *
   * C++ 侧经 dlsym("legado_platform_event") 转发到 Kotlin OhosNativeBridge.onPlatformEvent,
   * 由 OhosPlatformEventChannel 按 type 分发 (httpProgress → OhosDownloadProgressEvents,
   * lifecycle → OhosAppLifecycle)。事件方向恒为 ArkTS → Kotlin, 无 tsfn。
   *
   * @param event 事件 JSON `{ type: 'httpProgress' | 'lifecycle', ... }`
   */
  platformEvent(event: string): void;

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

  // ===== Network tsfn 回调注册 + ArkTS → Kotlin 回调 (同 Pasteboard 模式, 网络状态查询) =====

  /**
   * 注册 Network 回调 (KMP → ArkTS 跨线程 dispatch, 网络状态查询请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_network_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.networkTsfn。此后 KMP 调用
   * OhosNativeBridge.invokeNetworkSync 时, JSON 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.net.connection getDefaultNetSync + getNetCapabilitiesSync
   * (需 ohos.permission.GET_NETWORK_INFO, normal + system_grant) 查询网络可用性与是否 WiFi,
   * 完成后通过 [networkCallback] 回送结果给 Kotlin。未注册时 KMP 侧降级 true (不拦截)。
   *
   * @param callback 接收 JSON 请求 `{ requestId, action, payload }`:
   *   - action='query': payload=`{}` → 回送 `{ ok, network, wifi }`
   */
  registerNetworkCallback(callback: (json: string) => void): void;

  /**
   * Network 操作结果回调 (ArkTS → Kotlin)。
   *
   * ArkTS 侧处理完网络状态查询后, 通过此方法把结果 JSON
   * 回送给 Kotlin, 唤醒 OhosNativeBridge.invokeNetworkSync 中阻塞的 CompletableDeferred。
   *
   * @param requestId 请求 ID (与 invokeNetworkSync 生成的 requestId 对应)
   * @param result 结果 JSON:
   *   - 成功:  `{ ok: true, network: <boolean>, wifi: <boolean> }`
   *   - 失败:  `{ ok: false, error: '<string>' }` (Kotlin 侧降级 true 不拦截)
   */
  networkCallback(requestId: number, result: string): void;

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

  // ===== WebView tsfn 回调注册 + ArkTS → Kotlin 回调 (后台 WebView 抓取, 混合协议) =====

  /**
   * 注册 WebView 回调 (KMP → ArkTS 跨线程 dispatch, 后台 WebView 抓取请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_webview_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.webViewTsfn。此后 KMP 调用
   * OhosNativeBridge.invokeWebViewSync 时, 混合协议双参数跨线程 dispatch 到此 [callback]:
   * 控制面 JSON + 数据面裸 html (大段 HTML 不经 JSON 转义, 避免转义膨胀与双端编解码拷贝)。
   * 处理完成后通过 [webViewCallback] 回送结果给 Kotlin。
   *
   * @param callback 接收两个参数:
   *   - jsonControl (string): JSON 请求 `{ requestId, action: 'request', payload: '<WebViewRequestPayload JSON, 不含 html>' }`
   *     payload 字段: url/encode/tag/headers/sourceRegex/overrideUrlRegex/js/delayTime/cookie
   *   - htmlRaw (string): 数据面裸 HTML 入参 (与 url 二选一, 空串=无)
   */
  registerWebViewCallback(callback: (jsonControl: string, htmlRaw: string) => void): void;

  /**
   * WebView 后台抓取结果回调 (ArkTS → Kotlin, 混合协议三参数)。
   *
   * ArkTS 侧完成页面加载 + JS 执行 (或嗅探命中) 后, 通过此方法把结果回送给 Kotlin,
   * 唤醒 OhosNativeBridge.invokeWebViewSync 中阻塞的 CompletableDeferred。
   *
   * @param requestId 请求 ID (与 invokeWebViewSync 生成的 requestId 对应)
   * @param result 控制面结果 JSON (不含 body):
   *   - 成功:      `{ ok: true, url: '<finalUrl>', cookie: '<k=v; ...>' }`
   *   - 命中嗅探:  `{ ok: true, url: '<原始url>' }`
   *   - 失败:      `{ ok: false, error: '<原因>' }`
   * @param bodyRaw 数据面裸字符串: 源码 HTML / 命中 URL (失败或空结果时为空串 '')
   */
  webViewCallback(requestId: number, result: string, bodyRaw: string): void;

  // ===== Markdown 查看器 tsfn 回调注册 + ArkTS → Kotlin 事件回调 (composeResources 直读 Web 渲染) =====

  /**
   * 注册 Markdown 回调 (KMP → ArkTS 跨线程 dispatch, Markdown 渲染请求)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_markdown_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.markdownTsfn。此后 KMP MarkdownContent
   * (ArkUIView2 混排的 Web 组件, viewer HTML 由 buildMarkdownViewerHtml 从 composeResources 直读
   * 内联拼装) 调 OhosNativeBridge.sendMarkdown 时, JSON 渲染请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS MarkdownBridgeHandler runJavaScript 注入 renderMarkdown (marked.parse +
   * hljs.highlightAll + github-markdown 亮/暗主题) 渲染。
   * fire-and-forget (同 Toast 模式), 无 ArkTS → Kotlin 结果回调。
   *
   * @param callback 接收 JSON 渲染请求 `{ content: '<markdown 原文>', isDark: <boolean>, fontSize: <vp 字号> }`
   */
  registerMarkdownCallback(callback: (json: string) => void): void;

  /**
   * Markdown 查看器事件回调 (ArkTS → Kotlin)。
   *
   * viewer 页面内 <a> 链接点击经 javaScriptProxy (legadoMarkdownBridge.openLink) 调回 ArkTS,
   * 由 MarkdownBridgeHandler 通过此方法推送给 Kotlin, 走系统浏览器打开。
   *
   * @param event 事件 JSON, 如 `{ action: 'openLink', url: '<链接>' }`
   */
  markdownEvent(event: string): void;

  /**
   * 构建 Markdown 查看器完整 HTML (ArkTS → Kotlin 同步调用)。
   *
   * 鸿蒙端 composeResources 打包进 liblegado_shared.so 内嵌资源, Web 组件无法直接按路径访问;
   * 本函数运行时从 composeResources 直读模板 + marked/highlight/github-markdown 亮暗 css,
   * 内联拼成完整 HTML (单一数据源, 无平台端资源副本), 供 WebviewController.loadData 加载。
   * 结果进程内缓存; 主线程同步调用安全 (纯内存读取)。
   *
   * @return 完整 viewer HTML 字符串; 读取失败时为空串 (调用方跳过 loadData)
   */
  buildMarkdownViewerHtml(): string;

  // ===== Battery tsfn 回调注册 + ArkTS → Kotlin 回调 (同 Crypto 模式) =====

  /**
   * 注册 Battery 回调 (KMP → ArkTS 跨线程 dispatch, 阅读页电池电量查询)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_battery_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.batteryTsfn。此后 KMP 调
   * OhosNativeBridge.invokeBatterySync("getLevel") 时, 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.batteryInfo.batterySOC 读取电量, 通过 [batteryCallback] 回送结果。
   *
   * @param callback 接收 JSON 请求 `{ requestId, action: 'getLevel' }`
   */
  registerBatteryCallback(callback: (json: string) => void): void;

  /**
   * Battery 查询结果回调 (ArkTS → Kotlin)。
   *
   * @param requestId 请求 ID (与 invokeBatterySync 生成的 requestId 对应)
   * @param result 结果 JSON: 成功 `{ ok: true, level: <0-100> }`; 失败 `{ ok: false, error: '<string>' }`
   */
  batteryCallback(requestId: number, result: string): void;

  // ===== Share tsfn 回调注册 (同 Toast 模式, fire-and-forget dispatch) =====

  /**
   * 注册 Share 回调 (KMP → ArkTS 跨线程 dispatch, 系统分享面板)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_share_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.shareTsfn。此后 KMP 调
   * OhosNativeBridge.shareText/shareFile 时, payload 跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @kit.ShareKit systemShare.ShareController.show 弹系统分享面板。
   * fire-and-forget (与 Toast 同模式), 无 ArkTS → Kotlin 结果回调。
   *
   * @param callback 接收 JSON payload `{ action: 'text'|'file', text?, filePath?, mimeType? }`
   *   - action='text': 分享纯文本 (text)
   *   - action='file': 分享文件 (filePath 沙盒绝对路径, ArkTS 转 file:// URI; mimeType)
   */
  registerShareCallback(callback: (json: string) => void): void;

  // ===== Keyboard tsfn 回调注册 (同 Window 模式, fire-and-forget dispatch) =====

  /**
   * 注册 Keyboard 回调 (KMP → ArkTS 跨线程 dispatch, 软键盘显隐/避让)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_keyboard_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.keyboardTsfn。此后 KMP 调
   * OhosNativeBridge.hideSoftInput/showSoftInput/setKeyboardAvoidMode 时, 命令跨线程
   * dispatch 到此 [callback], 由 ArkTS 调 @ohos.inputMethod.getController。
   * fire-and-forget (同 Window 模式), 无 ArkTS → Kotlin 结果回调。
   *
   * @param callback 接收 JSON 命令 `{ action: 'hide'|'show'|'setAvoidMode', mode?: <0-2> }`
   */
  registerKeyboardCallback(callback: (json: string) => void): void;

  // ===== Permission tsfn 回调注册 + ArkTS → Kotlin 回调 (同 Pasteboard 模式) =====

  /**
   * 注册 Permission 回调 (KMP → ArkTS 跨线程 dispatch, 权限查询/申请)。
   *
   * C++ 侧创建 napi_threadsafe_function 包装 [callback], 并通过 @CName legado_register_permission_fn
   * 把 dispatch 函数指针注入 Kotlin OhosNativeBridge.permissionTsfn。此后 KMP 调
   * OhosNativeBridge.invokePermissionSync 时, 请求跨线程 dispatch 到此 [callback],
   * 由 ArkTS 调 @ohos.abilityAccessCtrl.checkAccessTokenSync / requestPermissionsFromUser,
   * 通过 [permissionCallback] 回送结果。
   *
   * @param callback 接收 JSON 请求 `{ requestId, action: 'check'|'request', payload: '{"permission":"ohos.permission.XXX"}' }`
   */
  registerPermissionCallback(callback: (json: string) => void): void;

  /**
   * Permission 查询/申请结果回调 (ArkTS → Kotlin)。
   *
   * @param requestId 请求 ID (与 invokePermissionSync 生成的 requestId 对应)
   * @param result 结果 JSON: 成功 `{ ok: true, granted: <boolean> }`; 失败 `{ ok: false, error: '<string>' }`
   */
  permissionCallback(requestId: number, result: string): void;

  // ===== 图片下载管线 (ArkTS 保存到相册复用, 带书源 header 防盗链) =====

  /**
   * 下载图片字节并返回 base64 (复用 shared 下载管线: AnalyzeUrlCore 带书源 header/cookie/charset/JS
   * + ImageUtils.decode 解密, 解决 ArkTS 裸下载拿不到书源 header 导致的防盗链失败)。
   *
   * **必须从 TaskPool/Worker 线程调用**: 本函数内部 runBlocking 转同步且下载走 HTTP 桥
   * (tsfn → ArkTS 主线程处理回调), 主线程直接调用会因主线程被阻塞而无法处理 HTTP 回调 → 死锁超时。
   *
   * @param url 图片地址
   * @return base64 编码的图片字节; 下载失败/解密失败/无活动阅读书时返回空串 (调用方回退裸下载或提示)
   */
  downloadImageBytes(url: string): string;

}

declare const legado: LegadoNativeBridge;
export default legado;
