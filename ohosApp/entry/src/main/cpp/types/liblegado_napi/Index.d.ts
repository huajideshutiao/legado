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
   * @param callback 接收 JSON 命令 `{ action, path?, position? }`:
   *   - action='setSource': path=文件路径 → 创建 AVPlayer + 设源 + prepare
   *   - action='play':      AVPlayer.play()
   *   - action='pause':     AVPlayer.pause()
   *   - action='stop':      AVPlayer.stop()
   *   - action='seekTo':    position=毫秒 → AVPlayer.seek()
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
   * 转发给 OhosNativeBridge.MediaEventListener (即 OhosHttpTtsPlayer)。
   *
   * @param event 事件 JSON, 如:
   *   `{ event: 'onReady' }` / `{ event: 'onEndOfMedia' }` /
   *   `{ event: 'onError', message: '...' }` / `{ event: 'onBufferingUpdate', percent: 50 }` /
   *   `{ event: 'onDuration', duration: 30000 }` / `{ event: 'onPosition', position: 5000 }` /
   *   `{ event: 'onPlaying' }` / `{ event: 'onPaused' }`
   */
  mediaEvent(event: string): void;
}

declare const legado: LegadoNativeBridge;
export default legado;
