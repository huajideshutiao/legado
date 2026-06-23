package io.legado.app.utils

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import splitties.init.appCtx

fun SharedPreferences.getString(key: String): String? {
    return getString(key, null)
}

fun SharedPreferences.putString(key: String, value: String) {
    edit {
        putString(key, value)
    }
}

fun SharedPreferences.getBoolean(key: String): Boolean {
    return getBoolean(key, false)
}

fun SharedPreferences.putBoolean(key: String, value: Boolean) {
    edit {
        putBoolean(key, value)
    }
}

fun SharedPreferences.getInt(key: String): Int {
    return getInt(key, 0)
}

fun SharedPreferences.putInt(key: String, value: Int) {
    edit {
        putInt(key, value)
    }
}

fun SharedPreferences.getLong(key: String): Long {
    return getLong(key, 0)
}

fun SharedPreferences.putLong(key: String, value: Long) {
    edit {
        putLong(key, value)
    }
}

fun SharedPreferences.getFloat(key: String): Float {
    return getFloat(key, 0f)
}

fun SharedPreferences.putFloat(key: String, value: Float) {
    edit {
        putFloat(key, value)
    }
}

fun SharedPreferences.remove(key: String) {
    edit {
        remove(key)
    }
}

fun LifecycleOwner.observeSharedPreferences(
    prefs: SharedPreferences = appCtx.defaultSharedPreferences,
    l: SharedPreferences.OnSharedPreferenceChangeListener
) {
    val observer = object : DefaultLifecycleObserver {
        override fun onCreate(owner: LifecycleOwner) {
            prefs.registerOnSharedPreferenceChangeListener(l)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            prefs.unregisterOnSharedPreferenceChangeListener(l)
            lifecycle.removeObserver(this)
        }

        override fun onPause(owner: LifecycleOwner) {
            prefs.unregisterOnSharedPreferenceChangeListener(l)
        }

        override fun onResume(owner: LifecycleOwner) {
            prefs.registerOnSharedPreferenceChangeListener(l)
        }
    }
    lifecycle.addObserver(observer)
}
