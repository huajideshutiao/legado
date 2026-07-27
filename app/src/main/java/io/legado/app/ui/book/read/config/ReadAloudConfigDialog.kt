package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.runBlocking
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.compose.platform.AndroidAppConfigProvider
import io.legado.app.ui.compose.platform.AndroidEventBusProvider
import io.legado.app.ui.compose.platform.AndroidPreferenceStoreProvider
import io.legado.app.ui.compose.platform.AndroidThemeStoreProvider
import io.legado.app.ui.compose.platform.LocalAppConfigProvider
import io.legado.app.ui.compose.platform.LocalEventBusProvider
import io.legado.app.ui.compose.platform.LocalPreferenceStoreProvider
import io.legado.app.ui.compose.platform.LocalThemeStoreProvider
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment

/**
 * 朗读设置（迁 pref_config_aloud.xml → Compose）。
 * SpeakEngineDialog 经 parentFragment 找 CallBack，故本 Dialog 实现 CallBack 并用自身
 * childFragmentManager 弹出；prefs 变更监听承接事件广播，与原 ReadAloudPreferenceFragment 一致。
 */
class ReadAloudConfigDialog : BasePrefDialogFragment(),
    SpeakEngineDialog.CallBack,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var pausePhoneCallsEnabled by mutableStateOf(AppConfig.ignoreAudioFocus)
    private var speakEngineSummaryState by mutableStateOf("")

    private val speakEngineSummary: String
        get() {
            val ttsEngine = ReadAloud.ttsEngine
                ?: return getString(R.string.system_tts)
            if (StringUtils.isNumeric(ttsEngine)) {
                return runBlocking { appDb.httpTTSDao.getName(ttsEngine.toLong()) }
                    ?: getString(R.string.system_tts)
            }
            return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
                ?: getString(R.string.system_tts)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        pausePhoneCallsEnabled = AppConfig.ignoreAudioFocus
        speakEngineSummaryState = speakEngineSummary
        return ComposeView(requireContext()).apply {
            setBackgroundColor(requireContext().backgroundColor)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // 注入 Android actual Provider，供 Screen 内部 AppTheme 通过 LocalXxx 取依赖
                val themeStoreProvider = remember { AndroidThemeStoreProvider() }
                val appConfigProvider = remember { AndroidAppConfigProvider() }
                val eventBusProvider = remember { AndroidEventBusProvider() }
                val preferenceStoreProvider = remember { AndroidPreferenceStoreProvider() }
                CompositionLocalProvider(
                    LocalThemeStoreProvider provides themeStoreProvider,
                    LocalAppConfigProvider provides appConfigProvider,
                    LocalEventBusProvider provides eventBusProvider,
                    LocalPreferenceStoreProvider provides preferenceStoreProvider,
                ) {
                    ReadAloudConfigScreen(
                        pausePhoneCallsEnabled = pausePhoneCallsEnabled,
                        speakEngineSummary = speakEngineSummaryState,
                        onTtsEngine = { showDialogFragment(SpeakEngineDialog()) },
                        onSysTtsConfig = { IntentHelp.openTTSSetting() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().defaultSharedPreferences
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        requireContext().defaultSharedPreferences
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        when (key) {
            PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                if (BaseReadAloudService.isRun) {
                    postEvent(EventBus.MEDIA_BUTTON, false)
                }
            }

            PreferKey.ignoreAudioFocus -> {
                pausePhoneCallsEnabled = AppConfig.ignoreAudioFocus
            }
        }
    }

    override fun upSpeakEngineSummary() {
        speakEngineSummaryState = speakEngineSummary
    }
}
