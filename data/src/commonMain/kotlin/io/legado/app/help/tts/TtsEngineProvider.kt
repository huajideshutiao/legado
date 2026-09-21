package io.legado.app.help.tts

import io.legado.app.data.entities.HttpTTS
import kotlin.concurrent.Volatile

/**
 * 系统 TTS 引擎的进程级 Provider (commonMain)。
 *
 * [SystemTtsEngine] 具体实现由各平台 actual 提供 (app: TextToSpeechEngine / desktop:
 * Windows SAPI·espeak·say / ios: AVSpeechSynthesizer / ohos: napi textToSpeech);
 * [ReadAloudControllerShared] 需要拿到实例但不能直接 new 平台实现 (会引入平台依赖),
 * 故用 Provider 模式: 宿主启动时 register, commonMain 取 get()。
 *
 * 风格与 [BookStorageProviders]/[AppDbProviders]/[PreferenceProviders] 等一致
 * (`object + @Volatile var impl`)。线程安全: impl @Volatile 保证可见性;
 * register/get/unregister 不做同步, 假定启动阶段单线程 register、后续只读 get;
 * 若需运行时切换引擎由调用方自行同步 (或后续改 AtomicReference)。
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

    // region HttpTtsPlayer 工厂注册点 (三端朗读 HttpTTS 路径)

    /**
     * 当前平台注册的 [HttpTtsPlayer] 工厂。
     *
     * - 工厂模式而非单例: HttpTTS 播放器实例每次开始朗读重新创建 (与 app 端
     *   ExoPlayer 重建语义对齐, 避免上一次播放的 player 状态残留)
     * - null: 未注册 (平台不支持 HttpTTS 或未启动时)
     */
    @Volatile
    private var httpTtsPlayerFactoryField: ((HttpTTS) -> HttpTtsPlayer)? = null

    /**
     * 注册 [HttpTtsPlayer] 工厂。
     *
     * @param factory 接收 [HttpTTS] 源配置, 返回平台 actual 实例
     *                 (如 desktop `DesktopHttpTtsPlayer` / iOS `IosHttpTtsPlayer`)
     */
    fun registerHttpTtsPlayerFactory(factory: (HttpTTS) -> HttpTtsPlayer) {
        httpTtsPlayerFactoryField = factory
    }

    /**
     * 取已注册工厂创建 [HttpTtsPlayer] 实例, 未注册返回 null。
     *
     * @param httpTTS TTS 源配置 (工厂可能用其初始化播放器)
     */
    fun getHttpTtsPlayer(httpTTS: HttpTTS): HttpTtsPlayer? =
        httpTtsPlayerFactoryField?.invoke(httpTTS)

    /** 注销 [HttpTtsPlayer] 工厂 (用于应用退出)。 */
    fun unregisterHttpTtsPlayerFactory() {
        httpTtsPlayerFactoryField = null
    }
    // endregion
}
