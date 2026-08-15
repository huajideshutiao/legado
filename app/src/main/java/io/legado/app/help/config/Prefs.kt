package io.legado.app.help.config

import io.legado.app.App
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal class PrefDelegate<T>(
    private val getter: () -> T,
    private val setter: (T) -> Unit,
) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = getter()
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = setter(value)
}

internal fun boolPref(key: String, default: Boolean = false) = PrefDelegate(
    { App.instance.getPrefBoolean(key, default) },
    { App.instance.putPrefBoolean(key, it) },
)

internal fun intPref(key: String, default: Int = 0, range: IntRange? = null) = PrefDelegate(
    {
        val v = App.instance.getPrefInt(key, default)
        if (range == null) v else v.coerceIn(range)
    },
    { App.instance.putPrefInt(key, if (range == null) it else it.coerceIn(range)) },
)

internal fun longPref(key: String, default: Long = 0L) = PrefDelegate(
    { App.instance.getPrefLong(key, default) },
    { App.instance.putPrefLong(key, it) },
)

internal fun stringPref(key: String, default: String? = null) = PrefDelegate<String?>(
    { App.instance.getPrefString(key, default) },
    { App.instance.putPrefString(key, it) },
)

internal fun nonNullStringPref(key: String, default: String) = PrefDelegate(
    { App.instance.getPrefString(key) ?: default },
    { App.instance.putPrefString(key, it) },
)

internal fun stringPrefClearOnEmpty(key: String) = PrefDelegate<String?>(
    { App.instance.getPrefString(key) },
    {
        if (it.isNullOrEmpty()) App.instance.removePref(key) else App.instance.putPrefString(
            key,
            it
        )
    },
)

private val cachedReloaders = HashMap<String, () -> Unit>()

internal fun reloadCachedPref(key: String) {
    cachedReloaders[key]?.invoke()
}

internal class CachedPref<T>(
    key: String,
    private val load: () -> T,
    private val store: (T) -> Unit,
) : ReadWriteProperty<Any?, T> {
    @Volatile
    private var current: T = load()

    init {
        cachedReloaders[key] = { current = load() }
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = current
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        store(value)
        current = value
    }
}

internal fun cachedBoolPref(key: String, default: Boolean = false) = CachedPref(
    key,
    { App.instance.getPrefBoolean(key, default) },
    { App.instance.putPrefBoolean(key, it) },
)

internal fun cachedIntPref(key: String, default: Int = 0) = CachedPref(
    key,
    { App.instance.getPrefInt(key, default) },
    { App.instance.putPrefInt(key, it) },
)

internal fun cachedStringPref(key: String, default: String? = null) = CachedPref(
    key,
    { App.instance.getPrefString(key, default) },
    { App.instance.putPrefString(key, it) },
)

internal fun <T> cachedPref(key: String, load: () -> T, store: (T) -> Unit) =
    CachedPref(key, load, store)
