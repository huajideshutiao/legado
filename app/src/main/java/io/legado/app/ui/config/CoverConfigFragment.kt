package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.number.showNumberPicker
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment

class CoverConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_cover)
        upPreferenceSummary(PreferKey.defaultCover, null)
        upPreferenceSummary(PreferKey.defaultCoverDark, null)
        upPreferenceSummary(PreferKey.bookshelfCoverWidth, null)
        findPreference<SwitchPreference>(PreferKey.coverShowAuthor)
            ?.isEnabled = getPrefBoolean(PreferKey.coverShowName)
        findPreference<SwitchPreference>(PreferKey.coverShowAuthorN)
            ?.isEnabled = getPrefBoolean(PreferKey.coverShowNameN)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.cover_config)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> upPreferenceSummary(key, null)

            PreferKey.bookshelfCoverWidth -> upPreferenceSummary(key, null)

            PreferKey.coverShowName -> {
                findPreference<SwitchPreference>(PreferKey.coverShowAuthor)
                    ?.isEnabled = getPrefBoolean(key)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowNameN -> {
                findPreference<SwitchPreference>(PreferKey.coverShowAuthorN)
                    ?.isEnabled = getPrefBoolean(key)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowAuthor,
            PreferKey.coverShowAuthorN -> {
                BookCover.upDefaultCover()
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "coverRule" -> showDialogFragment(CoverRuleConfigDialog())
            PreferKey.defaultCover ->
                showDialogFragment(DefaultCoverGalleryDialog(isNight = false))

            PreferKey.defaultCoverDark ->
                showDialogFragment(DefaultCoverGalleryDialog(isNight = true))

            PreferKey.bookshelfCoverWidth -> showNumberPicker(
                requireContext(),
                titleResId = R.string.bookshelf_cover_width,
                max = 160, min = 70, value = AppConfig.bookshelfCoverWidth,
                neutralButton = R.string.btn_default_s to {
                    AppConfig.bookshelfCoverWidth = 90
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
            ) {
                AppConfig.bookshelfCoverWidth = it
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> {
                val count = BookCover.listDefaultCovers(preferenceKey).size
                preference.summary = if (count == 0) {
                    getString(R.string.select_image)
                } else {
                    getString(R.string.default_cover_count, count)
                }
            }

            PreferKey.bookshelfCoverWidth -> {
                val width = value ?: "${AppConfig.bookshelfCoverWidth}dp"
                preference.summary = getString(R.string.bookshelf_cover_width_summary, width)
            }

            else -> preference.summary = value
        }
    }

}
