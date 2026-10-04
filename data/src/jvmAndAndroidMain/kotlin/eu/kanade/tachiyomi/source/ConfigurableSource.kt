// Copyright The Keiyoushi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.source

import androidx.preference.PreferenceScreen

interface ConfigurableSource {

    fun setupPreferenceScreen(screen: PreferenceScreen)
}
