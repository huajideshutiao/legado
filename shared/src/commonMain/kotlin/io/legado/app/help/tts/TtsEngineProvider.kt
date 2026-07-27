package io.legado.app.help.tts

/**
 * 系统 TTS 引擎的进程级 Provider（KMP commonMain 版）。
 *
 * # 设计动机
 *
 * [SystemTtsEngine] 接口的具体实现由各平台 actual 提供:
 * - app (Android): `TextToSpeechEngine` (基于 android.speech.tts.TextToSpeech)
 * - desktop (JVM): `DesktopSystemTtsEngine` (Windows SAPI / Linux espeak / macOS say)
 * - ios: AVSpeechSynthesizer 封装 (待补)
 * - ohos: 鸿蒙 @ohos.textToSpeech 封装 (待补)
 *
 * commonMain 中的 [ReadAloudControllerShared] 需要拿到当前平台的 [SystemTtsEngine]
 * 实例来驱动朗读, 但不能直接 new 平台实现 (会引入平台依赖)。采用 Provider 模式:
 *
 * ```
 *   desktop Main.kt 启动时:
 *       TtsEngineProvider.register(DesktopSystemTtsEngine())
 *
 *   commonMain ReadAloudControllerShared:
 *       val engine = TtsEngineProvider.get()  // 取 desktop 注册的实例
 * ```
 *
 * # 与其他 Provider 的一致性
 *
 * 复用项目内既有 Provider 模式风格:
 * - [io.legado.app.help.book.BookStorageProviders] / [io.legado.app.help.book.BookHelpProviders]
 * - [io.legado.app.data.AppDbProviders] / [io.legado.app.data.AppDatabaseProviders]
 * - [io.legado.app.help.config.PreferenceProviders]
 * - [io.legado.app.help.http.OkHttpClientProviders]
 *
 * 上述 Provider 均为 `object + @Volatile var impl` 风格, 本类保持一致便于阅读维护。
 *
 * # 线程安全
 *
 * - [impl] 用 `@Volatile` 保证可见性
 * - register / get / unregister 都不做同步, 假定在应用启动阶段单线程调用 register,
 *   后续任意线程只读 get (与上述其他 Provider 行为一致)
 * - 若需运行时切换引擎 (如用户在设置里切 TTS 引擎), 应由调用方自行同步, 或后续
 *   改为 AtomicReference
 *
 * KP2-D P0-9 新增。
 */
object TtsEngineProvider {

    /**
     * 当前平台注册的 [SystemTtsEngine] 实例。
     *
     * - null: 未注册 (应用启动早期或平台不支持 TTS)
     * - 非 null: 已注册, 可被 [ReadAloudControllerShared] 等调用方取用
     */
    @Volatile
    private var implField: SystemTtsEngine? = null

    /**
     * 取已注册的 [SystemTtsEngine] 实例。
     *
     * @return 已注册的引擎实例; 未注册时返回 null (调用方应处理降级, 如显示"不支持 TTS")
     */
    fun get(): SystemTtsEngine? = implField

    /**
     * 注册 [SystemTtsEngine] 实例。重复注册会覆盖前一个实例, 调用方负责 shutdown 旧的。
     *
     * @param engine 平台 actual 实例 (如 `DesktopSystemTtsEngine`)
     */
    fun register(engine: SystemTtsEngine) {
        implField = engine
    }

    /**
     * 注销 [SystemTtsEngine] 实例 (用于应用退出 / 平台切换)。
     *
     * 注意: 仅清空引用, 不调用 [SystemTtsEngine.shutdown]; 调用方需自行 shutdown。
     */
    fun unregister() {
        implField = null
    }
}
