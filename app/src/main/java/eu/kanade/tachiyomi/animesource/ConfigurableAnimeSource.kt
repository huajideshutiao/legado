// Copyright The Aniyomi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.animesource

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface ConfigurableAnimeSource : AnimeSource {

    /**
     * Implementations must override [setupPreferenceScreen] to add the user preferences.
     * The [PreferenceScreen][androidx.preference.PreferenceScreen] is stubbed by the host
     * (androidx.preference shim), so implementations can use stubbed inheritors safely.
     */
    fun setupPreferenceScreen(screen: PreferenceScreen)

    /**
     * Gets instance of [SharedPreferences] scoped to the specific source.
     *
     * @since extensions-lib 1.5
     */
    fun getSourcePreferences(): SharedPreferences =
        Injekt.get<Application>().getSharedPreferences(preferenceKey(), Context.MODE_PRIVATE)
}

fun ConfigurableAnimeSource.preferenceKey(): String = "source_$id"

fun ConfigurableAnimeSource.sourcePreferences(): SharedPreferences =
    Injekt.get<Application>().getSharedPreferences(preferenceKey(), Context.MODE_PRIVATE)
